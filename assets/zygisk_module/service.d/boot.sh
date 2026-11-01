boot_guard_wait() {
  boot_id=""
  if [ -r /proc/sys/kernel/random/boot_id ]; then
    boot_id=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
  fi

  timeout=180
  i=0
  while [ $i -lt $timeout ]; do
    if [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ]; then
      if [ -n "$boot_id" ]; then
        echo "$boot_id" > "$BOOT_OK_FILE"
      else
        echo "unknown" > "$BOOT_OK_FILE"
      fi
      rm -f "$BOOT_PENDING_FILE" "$MODDIR/.boot_fail_count"
      refresh_uid_map
      start_diagnostics_workers
      return 0
    fi
    i=$((i + 1))
    sleep 1
  done

  rm -f "$BOOT_PENDING_FILE" "$MODDIR/.boot_fail_count"
  if [ -n "$boot_id" ]; then
    echo "$boot_id" > "$BOOT_OK_FILE"
  fi
  refresh_uid_map
  start_diagnostics_workers
  return 0
}
