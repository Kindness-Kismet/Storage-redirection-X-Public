#!/system/bin/sh

MODDIR="/data/adb/modules/storage.redirect.x"
LOGD_PID_FILE="$MODDIR/logs/.logd.pid"
CONFIG_EVENT_COLLECTOR_PID_FILE="$MODDIR/logs/.config_event_collector.pid"
PACKAGE_EVENT_COLLECTOR_PID_FILE="$MODDIR/logs/.package_event_collector.pid"

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

if [ -r /dev/srx_file_monitor.pid ]; then
  monitor_pid=$(cat /dev/srx_file_monitor.pid)
  case "$(tr '\0' ' ' < "/proc/$monitor_pid/cmdline" 2>/dev/null)" in
    *org.srx.monitor.MonitorServer*) kill "$monitor_pid" 2>/dev/null ;;
  esac
  rm -f /dev/srx_file_monitor.pid
fi
rm -f /dev/srx_file_monitor_health.json
stop_collector_by_pid_file "$LOGD_PID_FILE"
stop_collector_by_pid_file "$CONFIG_EVENT_COLLECTOR_PID_FILE"
stop_collector_by_pid_file "$PACKAGE_EVENT_COLLECTOR_PID_FILE"

# 清理统计/临时文件
rm -f /data/local/tmp/storage.redirect.x_stats 2>/dev/null
rm -rf /data/local/tmp/storage.redirect.x 2>/dev/null

ui_print "-- Storage Redirect X uninstalled"
ui_print "-- temporary files cleaned"
