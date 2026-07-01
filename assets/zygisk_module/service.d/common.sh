ensure_log_files() {
  touch "$RUNNING_LOG_FILE" "$FILE_MONITOR_LOG_FILE" "$MEDIA_STATE_LOG_FILE" "$APP_STATUS_LOG_FILE"
  chmod 666 "$RUNNING_LOG_FILE" "$FILE_MONITOR_LOG_FILE" "$MEDIA_STATE_LOG_FILE" "$APP_STATUS_LOG_FILE"
  if [ -f "$LOGS_DIR/app_crash.log" ] && [ ! -s "$APP_STATUS_LOG_FILE" ]; then
    cat "$LOGS_DIR/app_crash.log" >> "$APP_STATUS_LOG_FILE" 2>/dev/null
  fi
  rm -f "$LOGS_DIR/media_provider.log" "$LOGS_DIR/app_crash.log" "$MEDIA_STATE_LAST_PID_FILE" "$MEDIA_STATE_DETAIL_TS_FILE"
}

get_line_count() {
  file="$1"
  line_count=$(wc -l < "$file" 2>/dev/null)
  if [ -z "$line_count" ]; then
    echo 0
    return 0
  fi
  echo "$line_count"
}

trim_log_file_drop_head() {
  file="$1"
  drop_lines="$2"
  tmp_file="${file}.tmp"
  start_line=$((drop_lines + 1))

  if [ "$drop_lines" -le 0 ]; then
    return 0
  fi

  tail -n +"$start_line" "$file" > "$tmp_file" 2>/dev/null && mv "$tmp_file" "$file"
}

now_epoch_seconds() {
  date '+%s' 2>/dev/null || echo 0
}

stop_background_process() {
  target_pid="$1"
  if [ -z "$target_pid" ] || ! kill -0 "$target_pid" 2>/dev/null; then
    return 0
  fi

  children_file="/proc/$target_pid/task/$target_pid/children"
  if [ -r "$children_file" ]; then
    for child_pid in $(cat "$children_file" 2>/dev/null); do
      stop_background_process "$child_pid"
    done
  fi
  kill "$target_pid" 2>/dev/null
  wait "$target_pid" 2>/dev/null
}

stop_collector_by_pid_file() {
  pid_file="$1"
  if [ ! -f "$pid_file" ]; then
    return 0
  fi

  pid=$(cat "$pid_file" 2>/dev/null)
  stop_background_process "$pid"
  rm -f "$pid_file"
}

refresh_uid_map() {
  force_refresh="$1"
  now_sec=$(now_epoch_seconds)
  last_sec=$(cat "$UID_MAP_LAST_REFRESH_FILE" 2>/dev/null)
  last_sec=${last_sec:-0}
  if [ "$force_refresh" != "force" ] && [ -f "$SYSTEM_WRITER_UIDS_FILE" ] && [ $((now_sec - last_sec)) -lt 60 ]; then
    return 0
  fi

  mkdir -p "$CONFIG_DIR"
  tmp_uids_file="${SYSTEM_WRITER_UIDS_FILE}.tmp"

  {
    echo "# package:uid"
    cmd package list packages -U 2>/dev/null |
      sed -n 's/^package:\([^ ]*\).* uid:\([0-9][0-9]*\).*/\1:\2/p' |
      sort -u
  } > "$tmp_uids_file"

  entry_count=$(grep -c '^[^#].*:[0-9][0-9]*$' "$tmp_uids_file" 2>/dev/null)
  if [ "$entry_count" -gt 0 ]; then
    mv "$tmp_uids_file" "$SYSTEM_WRITER_UIDS_FILE"
    chmod 644 "$SYSTEM_WRITER_UIDS_FILE"
    echo "$now_sec" > "$UID_MAP_LAST_REFRESH_FILE"
  else
    rm -f "$tmp_uids_file"
  fi
}
