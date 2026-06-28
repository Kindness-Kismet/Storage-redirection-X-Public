get_media_provider_pid() {
  pidof com.google.android.providers.media.module 2>/dev/null | awk '{print $1}'
  pidof com.android.providers.media.module 2>/dev/null | awk '{print $1}'
  pidof com.android.providers.media 2>/dev/null | awk '{print $1}'
}

append_media_state_output() {
  now_text="$1"
  while IFS= read -r line; do
    printf '%s %s\n' "$now_text" "$line" >> "$MEDIA_STATE_LOG_FILE"
  done
}

should_write_media_detail() {
  reason="$1"
  now_sec=$(now_epoch_seconds)
  last_sec=$(cat "$MEDIA_STATE_DETAIL_TS_FILE" 2>/dev/null)
  last_sec=${last_sec:-0}

  if [ "$reason" = "pid_change" ] || [ "$reason" = "state_D" ]; then
    echo "$now_sec" > "$MEDIA_STATE_DETAIL_TS_FILE"
    return 0
  fi
  if [ $((now_sec - last_sec)) -ge 300 ]; then
    echo "$now_sec" > "$MEDIA_STATE_DETAIL_TS_FILE"
    return 0
  fi
  return 1
}

read_media_proc_value() {
  status_file="$1"
  key="$2"
  awk -v target="$key" '$1 == target":" { print $2; exit }' "$status_file" 2>/dev/null
}

append_media_proc_detail() {
  now_text="$1"
  pid="$2"
  reason="$3"

  echo "$now_text -- media detail begin pid=$pid reason=$reason" >> "$MEDIA_STATE_LOG_FILE"
  echo "$now_text -- /proc/$pid/cmdline" >> "$MEDIA_STATE_LOG_FILE"
  tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null |
    append_media_state_output "$now_text"

  echo "$now_text -- /proc/$pid/status" >> "$MEDIA_STATE_LOG_FILE"
  sed 's/^/  /' "/proc/$pid/status" 2>/dev/null |
    append_media_state_output "$now_text"

  echo "$now_text -- /proc/$pid/task/*/status" >> "$MEDIA_STATE_LOG_FILE"
  for status_file in "/proc/$pid"/task/*/status; do
    [ -r "$status_file" ] || continue
    tid=${status_file%/status}
    tid=${tid##*/}
    echo "$now_text ---- task $tid status" >> "$MEDIA_STATE_LOG_FILE"
    sed 's/^/  /' "$status_file" 2>/dev/null |
      append_media_state_output "$now_text"
  done

  echo "$now_text -- /proc/$pid/fd" >> "$MEDIA_STATE_LOG_FILE"
  ls -l "/proc/$pid/fd" 2>/dev/null |
    append_media_state_output "$now_text"

  echo "$now_text -- /proc/$pid/mountinfo storage" >> "$MEDIA_STATE_LOG_FILE"
  grep -E ' /storage| /mnt/user| /mnt/media_rw| /sdcard|storage.redirect.x' "/proc/$pid/mountinfo" 2>/dev/null |
    append_media_state_output "$now_text"

  echo "$now_text -- dumpsys meminfo $pid" >> "$MEDIA_STATE_LOG_FILE"
  dumpsys meminfo "$pid" 2>/dev/null |
    append_media_state_output "$now_text"

  echo "$now_text -- recent media logcat" >> "$MEDIA_STATE_LOG_FILE"
  logcat -d -t 120 -v threadtime 2>/dev/null |
    grep -E 'MediaProvider|MediaProviderWrapper|FuseDaemon|ExternalStorage|StorageRedirect|FileMonitorOp|SQLite|CursorWindow|binder' |
    append_media_state_output "$now_text"

  echo "$now_text -- recent oom/fuse dmesg" >> "$MEDIA_STATE_LOG_FILE"
  dmesg 2>/dev/null |
    tail -n 300 |
    grep -Ei 'oom|lowmem|killed process|fuse|media' |
    append_media_state_output "$now_text"

  echo "$now_text -- media detail end pid=$pid" >> "$MEDIA_STATE_LOG_FILE"
}

append_media_state_snapshot() {
  now_text=$(date '+%m/%d %H:%M:%S' 2>/dev/null)
  now_text=${now_text:-unknown}
  pid_list=$(get_media_provider_pid | sort -u)

  echo "$now_text -- media_state snapshot begin" >> "$MEDIA_STATE_LOG_FILE"
  echo "$now_text -- pidof media providers" >> "$MEDIA_STATE_LOG_FILE"
  {
    pidof com.google.android.providers.media.module 2>/dev/null
    pidof com.android.providers.media.module 2>/dev/null
    pidof com.android.providers.media 2>/dev/null
  } | append_media_state_output "$now_text"

  echo "$now_text -- ps -A -o PID,USER,ARGS media" >> "$MEDIA_STATE_LOG_FILE"
  ps -A -o PID,USER,ARGS 2>/dev/null |
    awk 'NR == 1 || $0 ~ /providers\.media|android\.process\.media/' |
    append_media_state_output "$now_text"

  if [ -z "$pid_list" ]; then
    echo "$now_text -- media provider missing" >> "$MEDIA_STATE_LOG_FILE"
  else
    last_pid=$(cat "$MEDIA_STATE_LAST_PID_FILE" 2>/dev/null)
    for pid in $pid_list; do
      status_file="/proc/$pid/status"
      if [ ! -r "$status_file" ]; then
        echo "$now_text -- media process gone pid=$pid" >> "$MEDIA_STATE_LOG_FILE"
        continue
      fi

      echo "$now_text -- /proc/$pid/status summary" >> "$MEDIA_STATE_LOG_FILE"
      grep -E '^(Name|State|Pid|PPid|Threads|VmRSS|VmSize):' "$status_file" 2>/dev/null |
        sed 's/^/  /' |
        append_media_state_output "$now_text"

      detail_reason="periodic"
      state=$(read_media_proc_value "$status_file" State)
      threads=$(read_media_proc_value "$status_file" Threads)
      vmrss=$(read_media_proc_value "$status_file" VmRSS)
      if [ -n "$last_pid" ] && [ "$last_pid" != "$pid" ]; then
        detail_reason="pid_change"
      elif [ "$state" = "D" ]; then
        detail_reason="state_D"
      elif [ "${vmrss:-0}" -ge 524288 ]; then
        detail_reason="rss_high"
      elif [ "${threads:-0}" -ge 256 ]; then
        detail_reason="threads_high"
      fi

      echo "$pid" > "$MEDIA_STATE_LAST_PID_FILE"
      if should_write_media_detail "$detail_reason"; then
        append_media_proc_detail "$now_text" "$pid" "$detail_reason"
      fi
    done
  fi

  echo "$now_text -- media_state snapshot end" >> "$MEDIA_STATE_LOG_FILE"
}

start_media_state_collector() {
  (
    line_count=$(get_line_count "$MEDIA_STATE_LOG_FILE")
    while true; do
      before_count=$(get_line_count "$MEDIA_STATE_LOG_FILE")
      append_media_state_snapshot
      after_count=$(get_line_count "$MEDIA_STATE_LOG_FILE")
      added_lines=$((after_count - before_count))
      if [ "$added_lines" -gt 0 ]; then
        line_count=$((line_count + added_lines))
      fi
      if [ "$line_count" -gt "$MAX_MEDIA_STATE_LOG_LINES" ]; then
        drop_lines=$((line_count - MAX_MEDIA_STATE_LOG_LINES + MEDIA_STATE_TRIM_BATCH_LINES))
        if [ "$drop_lines" -gt "$line_count" ]; then
          drop_lines="$line_count"
        fi
        trim_log_file_drop_head "$MEDIA_STATE_LOG_FILE" "$drop_lines"
        line_count=$((line_count - drop_lines))
      fi
      sleep 30
    done
  ) &
  echo "$!" > "$MEDIA_STATE_COLLECTOR_PID_FILE"
  chmod 644 "$MEDIA_STATE_COLLECTOR_PID_FILE"
}
