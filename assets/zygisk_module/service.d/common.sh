ensure_log_files() {
  touch "$RUNNING_LOG_FILE" "$FILE_MONITOR_LOG_FILE" "$MEDIA_STATE_LOG_FILE" "$APP_STATUS_LOG_FILE" "$STATS_FILE"
  if [ ! -s "$STATS_FILE" ]; then
    echo "0" > "$STATS_FILE"
  fi
  chmod 666 "$RUNNING_LOG_FILE" "$FILE_MONITOR_LOG_FILE" "$MEDIA_STATE_LOG_FILE" "$APP_STATUS_LOG_FILE" "$STATS_FILE"
  rm -f "$LOGS_DIR/media_provider.log" "$LOGS_DIR/app_crash.log" "$MEDIA_STATE_LAST_PID_FILE" "$MEDIA_STATE_DETAIL_TS_FILE"
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

sync_shared_config_dir() {
  shared_config_dir="${SHARED_CONFIG_DIR:-/dev/srx_config}"
  shared_apps_dir="$shared_config_dir/apps"

  mkdir -p "$shared_apps_dir" 2>/dev/null || {
    log -p w -t Boot "shared config sync mkdir failed: $shared_apps_dir"
    return 1
  }
  chmod 755 "$shared_config_dir" "$shared_apps_dir" 2>/dev/null
  chcon -R u:object_r:shell_data_file:s0 "$shared_config_dir" 2>/dev/null

  if [ -f "$CONFIG_DIR/global.json" ]; then
    tmp_global="$shared_config_dir/global.json.tmp"
    cp "$CONFIG_DIR/global.json" "$tmp_global" 2>/dev/null && \
      chmod 644 "$tmp_global" 2>/dev/null && \
      chcon u:object_r:shell_data_file:s0 "$tmp_global" 2>/dev/null && \
      mv "$tmp_global" "$shared_config_dir/global.json" 2>/dev/null
  else
    rm -f "$shared_config_dir/global.json" "$shared_config_dir/global.json.tmp" 2>/dev/null
  fi

  if [ -f "$SYSTEM_WRITER_UIDS_FILE" ]; then
    tmp_uid="$shared_config_dir/system_writer_uids.list.tmp"
    cp "$SYSTEM_WRITER_UIDS_FILE" "$tmp_uid" 2>/dev/null && \
      chmod 644 "$tmp_uid" 2>/dev/null && \
      chcon u:object_r:shell_data_file:s0 "$tmp_uid" 2>/dev/null && \
      mv "$tmp_uid" "$shared_config_dir/system_writer_uids.list" 2>/dev/null
  fi

  for shared_file in "$shared_apps_dir"/*.json; do
    [ -f "$shared_file" ] || continue
    shared_name=$(basename "$shared_file")
    [ -f "$APPS_CONFIG_DIR/$shared_name" ] || rm -f "$shared_file" 2>/dev/null
  done

  if [ -d "$APPS_CONFIG_DIR" ]; then
    for config_file in "$APPS_CONFIG_DIR"/*.json; do
      [ -f "$config_file" ] || continue
      config_name=$(basename "$config_file")
      tmp_app="$shared_apps_dir/$config_name.tmp"
      cp "$config_file" "$tmp_app" 2>/dev/null && \
        chmod 644 "$tmp_app" 2>/dev/null && \
        chcon u:object_r:shell_data_file:s0 "$tmp_app" 2>/dev/null && \
        mv "$tmp_app" "$shared_apps_dir/$config_name" 2>/dev/null
    done
  fi

  chcon -R u:object_r:shell_data_file:s0 "$shared_config_dir" 2>/dev/null

  return 0
}
