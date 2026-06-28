append_app_status_output() {
  now_text="$1"
  while IFS= read -r line; do
    printf '%s %s\n' "$now_text" "$line" >> "$APP_STATUS_LOG_FILE"
  done
}

get_package_pids() {
  package_name="$1"
  {
    pidof "$package_name" 2>/dev/null
    ps -A -o PID,NAME 2>/dev/null | awk -v target="$package_name" '
      NR > 1 && NF >= 2 {
        if ($2 == target || index($2, target ":") == 1) {
          print $1
        }
      }
    '
  } | tr ' ' '\n' | awk 'NF > 0 && $1 ~ /^[0-9]+$/ { print }' | sort -u
}

append_package_raw_state() {
  now_text="$1"
  package_name="$2"
  config_file="$3"

  echo "$now_text -- package begin $package_name" >> "$APP_STATUS_LOG_FILE"
  echo "$now_text -- config $config_file" >> "$APP_STATUS_LOG_FILE"
  sed 's/^/  /' "$config_file" 2>/dev/null | append_app_status_output "$now_text"

  echo "$now_text -- cmd package list packages -U | grep $package_name" >> "$APP_STATUS_LOG_FILE"
  cmd package list packages -U 2>/dev/null |
    grep -F "package:$package_name " |
    append_app_status_output "$now_text"

  echo "$now_text -- pidof $package_name" >> "$APP_STATUS_LOG_FILE"
  pidof "$package_name" 2>/dev/null |
    append_app_status_output "$now_text"

  echo "$now_text -- ps -A -o PID,NAME | grep $package_name" >> "$APP_STATUS_LOG_FILE"
  ps -A -o PID,NAME 2>/dev/null | awk -v target="$package_name" '
    NR == 1 { print; next }
    NF >= 2 && ($2 == target || index($2, target ":") == 1) { print }
  ' | append_app_status_output "$now_text"

  for pid in $(get_package_pids "$package_name"); do
    echo "$now_text -- /proc/$pid/cmdline" >> "$APP_STATUS_LOG_FILE"
    tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null |
      append_app_status_output "$now_text"

    echo "$now_text -- /proc/$pid/status" >> "$APP_STATUS_LOG_FILE"
    sed 's/^/  /' "/proc/$pid/status" 2>/dev/null |
      append_app_status_output "$now_text"

    echo "$now_text -- /proc/$pid/fd" >> "$APP_STATUS_LOG_FILE"
    ls -l "/proc/$pid/fd" 2>/dev/null |
      head -n 80 |
      append_app_status_output "$now_text"

    echo "$now_text -- /proc/$pid/mountinfo storage" >> "$APP_STATUS_LOG_FILE"
    grep -E ' /storage| /mnt/user| /mnt/media_rw| /sdcard' "/proc/$pid/mountinfo" 2>/dev/null |
      head -n 80 |
      append_app_status_output "$now_text"
  done

  echo "$now_text -- package end $package_name" >> "$APP_STATUS_LOG_FILE"
}

append_app_status_snapshot() {
  now_text=$(date '+%m/%d %H:%M:%S' 2>/dev/null)
  now_text=${now_text:-unknown}
  echo "$now_text -- app_status snapshot begin" >> "$APP_STATUS_LOG_FILE"
  for config_file in "$APPS_CONFIG_DIR"/*.json; do
    [ -f "$config_file" ] || continue
    package_name=$(basename "$config_file" .json)
    is_skipped_package "$package_name" && continue
    is_effective_package_config "$package_name" || continue
    append_package_raw_state "$now_text" "$package_name" "$config_file"
  done
  echo "$now_text -- app_status snapshot end" >> "$APP_STATUS_LOG_FILE"
}

start_app_status_snapshot_collector() {
  (
    line_count=$(get_line_count "$APP_STATUS_LOG_FILE")
    while true; do
      before_count=$(get_line_count "$APP_STATUS_LOG_FILE")
      append_app_status_snapshot
      after_count=$(get_line_count "$APP_STATUS_LOG_FILE")
      added_lines=$((after_count - before_count))
      if [ "$added_lines" -gt 0 ]; then
        line_count=$((line_count + added_lines))
      fi
      if [ "$line_count" -gt "$MAX_APP_STATUS_LOG_LINES" ]; then
        drop_lines=$((line_count - MAX_APP_STATUS_LOG_LINES + APP_STATUS_TRIM_BATCH_LINES))
        if [ "$drop_lines" -gt "$line_count" ]; then
          drop_lines="$line_count"
        fi
        trim_log_file_drop_head "$APP_STATUS_LOG_FILE" "$drop_lines"
        line_count=$((line_count - drop_lines))
      fi
      sleep 30
    done
  ) &
  echo "$!" > "$APP_STATUS_SNAPSHOT_PID_FILE"
  chmod 644 "$APP_STATUS_SNAPSHOT_PID_FILE"
}
