resolve_service_primary_arch() {
  arch=$(getprop ro.product.cpu.abi 2>/dev/null)
  if [ -z "$arch" ]; then
    arch=$(getprop ro.product.cpu.abilist64 2>/dev/null | awk -F',' '{print $1}')
  fi
  if [ -z "$arch" ]; then
    arch=$(getprop ro.product.cpu.abilist 2>/dev/null | awk -F',' '{print $1}')
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

resolve_logd_bin_path() {
  primary_arch=$(resolve_service_primary_arch)
  if [ -z "$primary_arch" ]; then
    return 1
  fi
  echo "$LOGD_BIN_ROOT/$primary_arch/$LOGD_BIN_NAME"
}

start_log_daemon() {
  ensure_log_files
  stop_collector_by_pid_file "$LOGD_PID_FILE"

  logd_bin=$(resolve_logd_bin_path) || {
    log -p e -t Boot "resolve log daemon abi failed"
    return 1
  }
  if [ ! -f "$logd_bin" ]; then
    log -p e -t Boot "missing log daemon: $logd_bin"
    return 1
  fi

  chmod 755 "$logd_bin" 2>/dev/null
  LOGD_BIN_PATH="$logd_bin"
  "$logd_bin" >/dev/null 2>&1 &
  echo "$!" > "$LOGD_PID_FILE"
  chmod 644 "$LOGD_PID_FILE"
  return 0
}

start_diagnostics_workers() {
  ensure_log_files
  stop_collector_by_pid_file "$CONFIG_EVENT_COLLECTOR_PID_FILE"
  stop_collector_by_pid_file "$PACKAGE_EVENT_COLLECTOR_PID_FILE"
  start_config_event_collector
  start_package_event_collector
}
