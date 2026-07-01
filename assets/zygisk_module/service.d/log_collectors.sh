start_running_collector() {
  (
    line_count=$(get_line_count "$RUNNING_LOG_FILE")
    while true; do
      logcat -T 1 -v threadtime -s \
        StorageRedirect:V SRX:V 2>/dev/null |
      awk '
        function level_text(level_char) {
          if (level_char == "V") return "Verbose"
          if (level_char == "D") return "Debug"
          if (level_char == "I") return "Info"
          if (level_char == "W") return "Warn"
          if (level_char == "E") return "Error"
          if (level_char == "F" || level_char == "A") return "Fatal"
          return level_char
        }
        {
          if (NF < 6) {
            next
          }
          date_text = $1
          time_text = $2
          level_char = $5
          if (date_text !~ /^[0-9][0-9]-[0-9][0-9]$/) {
            next
          }
          if (time_text !~ /^[0-9][0-9]:[0-9][0-9]:[0-9][0-9](\.[0-9]+)?$/) {
            next
          }
          if (level_char !~ /^[VDIWEAF]$/) {
            next
          }
          is_java = $6 == "SRX:"
          source_prefix = is_java ? "Jv" : "Rs"
          message_start = is_java ? 6 : 7
          gsub("-", "/", date_text)
          sub(/\..*$/, "", time_text)
          line_text = ""
          for (i = message_start; i <= NF; i++) {
            if (line_text == "") {
              line_text = $i
            } else {
              line_text = line_text " " $i
            }
          }
          if (line_text == "") {
            next
          }
          if (line_text ~ /^\[(Rs|Kt)(Verbose|Debug|Info|Warn|Error|Fatal)\][[:space:]]/) {
            printf "%s %s %s\n", date_text, time_text, line_text
          } else {
            printf "%s %s [%s%s] %s\n", date_text, time_text, source_prefix, level_text(level_char), line_text
          }
        }
      ' |
      while IFS= read -r line; do
        [ -n "$line" ] || continue
        printf '%s\n' "$line" >> "$RUNNING_LOG_FILE"
        line_count=$((line_count + 1))
        if [ "$line_count" -gt "$MAX_RUNNING_LOG_LINES" ]; then
          drop_lines=$((line_count - MAX_RUNNING_LOG_LINES + RUNNING_TRIM_BATCH_LINES))
          if [ "$drop_lines" -gt "$line_count" ]; then
            drop_lines="$line_count"
          fi
          trim_log_file_drop_head "$RUNNING_LOG_FILE" "$drop_lines"
          line_count=$((line_count - drop_lines))
        fi
      done
      sleep 1
    done
  ) &
  echo "$!" > "$RUNNING_COLLECTOR_PID_FILE"
  chmod 644 "$RUNNING_COLLECTOR_PID_FILE"
}

start_monitor_collector() {
  (
    line_count=$(get_line_count "$FILE_MONITOR_LOG_FILE")
    while true; do
      logcat -T 1 -v raw -s FileMonitorOp:I 2>/dev/null |
      while IFS= read -r line; do
        [ -n "$line" ] || continue
        case "$line" in ---------*) continue ;; esac
        printf '%s\n' "$line" >> "$FILE_MONITOR_LOG_FILE"
        line_count=$((line_count + 1))
        if [ "$line_count" -gt "$MAX_MONITOR_LOG_LINES" ]; then
          drop_lines=$((line_count - MAX_MONITOR_LOG_LINES + MONITOR_TRIM_BATCH_LINES))
          if [ "$drop_lines" -gt "$line_count" ]; then
            drop_lines="$line_count"
          fi
          trim_log_file_drop_head "$FILE_MONITOR_LOG_FILE" "$drop_lines"
          line_count=$((line_count - drop_lines))
        fi
      done
      sleep 1
    done
  ) &
  echo "$!" > "$MONITOR_COLLECTOR_PID_FILE"
  chmod 644 "$MONITOR_COLLECTOR_PID_FILE"
}

start_app_status_collector() {
  (
    line_count=$(get_line_count "$APP_STATUS_LOG_FILE")
    while true; do
      # AndroidRuntime:E 抓 Java FATAL，DEBUG:F 抓 tombstone，libc:F 抓 abort/SIGSEGV。
      logcat -T 1 -v threadtime -s AndroidRuntime:E DEBUG:F libc:F 2>/dev/null |
      while IFS= read -r line; do
        [ -n "$line" ] || continue
        case "$line" in ---------*) continue ;; esac
        printf '%s\n' "$line" >> "$APP_STATUS_LOG_FILE"
        line_count=$((line_count + 1))
        if [ "$line_count" -gt "$MAX_APP_STATUS_LOG_LINES" ]; then
          drop_lines=$((line_count - MAX_APP_STATUS_LOG_LINES + APP_STATUS_TRIM_BATCH_LINES))
          if [ "$drop_lines" -gt "$line_count" ]; then
            drop_lines="$line_count"
          fi
          trim_log_file_drop_head "$APP_STATUS_LOG_FILE" "$drop_lines"
          line_count=$((line_count - drop_lines))
        fi
      done
      sleep 1
    done
  ) &
  echo "$!" > "$APP_STATUS_COLLECTOR_PID_FILE"
  chmod 644 "$APP_STATUS_COLLECTOR_PID_FILE"
}

start_stats_collector() {
  if [ ! -f "$STATS_FILE" ]; then
    echo "0" > "$STATS_FILE"
  fi
  chmod 644 "$STATS_FILE"

  (
    while true; do
      logcat -T 1 -v raw -s Stats:I 2>/dev/null |
      awk -v stats_file="$STATS_FILE" '
        BEGIN {
          total = 0
          if ((getline current < stats_file) > 0) total = current + 0
          close(stats_file)
        }
        /^\+[0-9]+$/ {
          total += substr($0, 2) + 0
          dirty = 1
          events++
          if (events >= 50) {
            print total > stats_file
            close(stats_file)
            events = 0
            dirty = 0
          }
        }
        END {
          if (dirty) {
            print total > stats_file
            close(stats_file)
          }
        }
      '
      sleep 1
    done
  ) &
  echo "$!" > "$STATS_COLLECTOR_PID_FILE"
  chmod 644 "$STATS_COLLECTOR_PID_FILE"
}

start_log_collectors() {
  ensure_log_files
  stop_collector_by_pid_file "$RUNNING_COLLECTOR_PID_FILE"
  stop_collector_by_pid_file "$MONITOR_COLLECTOR_PID_FILE"
  stop_collector_by_pid_file "$MEDIA_STATE_COLLECTOR_PID_FILE"
  stop_collector_by_pid_file "$APP_STATUS_COLLECTOR_PID_FILE"
  stop_collector_by_pid_file "$APP_STATUS_SNAPSHOT_PID_FILE"
  stop_collector_by_pid_file "$STATS_COLLECTOR_PID_FILE"
  stop_collector_by_pid_file "$CONFIG_EVENT_COLLECTOR_PID_FILE"
  stop_collector_by_pid_file "$PACKAGE_EVENT_COLLECTOR_PID_FILE"
  start_running_collector
  start_monitor_collector
  start_media_state_collector
  start_app_status_snapshot_collector
  start_app_status_collector
  start_stats_collector
  start_config_event_collector
  start_package_event_collector
}

