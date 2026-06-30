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

detect_primary_abi() {
  arch=$(getprop ro.product.cpu.abi 2>/dev/null)
  if [ -z "$arch" ]; then
    abilist64=$(getprop ro.product.cpu.abilist64 2>/dev/null)
    if [ -n "$abilist64" ]; then
      arch=$(echo "$abilist64" | awk -F',' '{print $1}')
    else
      abilist=$(getprop ro.product.cpu.abilist 2>/dev/null)
      if [ -n "$abilist" ]; then
        arch=$(echo "$abilist" | awk -F',' '{print $1}')
      else
        arch=$(uname -m)
      fi
    fi
  fi

  case "$arch" in
    arm64-v8a|aarch64)
      echo "arm64-v8a"
      ;;
    x86_64|x86-64)
      echo "x86_64"
      ;;
    *)
      echo ""
      ;;
  esac
}

start_srx_daemon() {
  abi=$(detect_primary_abi)
  daemon_bin="$MODDIR/bin/$abi/srx_daemon"
  if [ -z "$abi" ] || [ ! -x "$daemon_bin" ]; then
    log -p w -t Boot "skip srx daemon: missing binary abi=$abi path=$daemon_bin"
    return 0
  fi

  "$daemon_bin" >/dev/null 2>&1 &
  daemon_pid="$!"
  echo "$daemon_pid" > "$DAEMON_PID_FILE"
  chmod 644 "$DAEMON_PID_FILE"
  log -p i -t Boot "srx daemon started pid=$daemon_pid abi=$abi"
}

ensure_srx_daemon_running() {
  daemon_pid=$(cat "$DAEMON_PID_FILE" 2>/dev/null)
  if [ -n "$daemon_pid" ] && kill -0 "$daemon_pid" 2>/dev/null; then
    return 0
  fi
  start_srx_daemon
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
  if [ "$entry_count" -gt 0 ] && is_uid_map_complete "$tmp_uids_file"; then
    mv "$tmp_uids_file" "$SYSTEM_WRITER_UIDS_FILE"
    chmod 644 "$SYSTEM_WRITER_UIDS_FILE"
    echo "$now_sec" > "$UID_MAP_LAST_REFRESH_FILE"
    return 0
  else
    log -p w -t Boot "skip uid map refresh: incomplete package list entries=$entry_count"
    rm -f "$tmp_uids_file"
    return 1
  fi
}

is_uid_map_complete() {
  uid_map_file="$1"
  if ! grep -Eq '^(com\.android\.providers\.media\.module|com\.google\.android\.providers\.media\.module|com\.android\.providers\.media):[0-9]+' "$uid_map_file" 2>/dev/null; then
    return 1
  fi

  [ -d "$APPS_CONFIG_DIR" ] || return 0
  for config_file in "$APPS_CONFIG_DIR"/*.json; do
    [ -f "$config_file" ] || continue
    package_name=$(basename "$config_file" .json)
    [ -n "$package_name" ] || continue
    [ "$package_name" = "com.storage.redirect.x" ] && continue

    if ! cmd package path "$package_name" >/dev/null 2>&1; then
      continue
    fi

    awk -F':' -v target="$package_name" '$1 == target { found = 1 } END { exit found ? 0 : 1 }' "$uid_map_file" || {
      log -p w -t Boot "uid map missing configured package: pkg=$package_name"
      return 1
    }
  done

  return 0
}
