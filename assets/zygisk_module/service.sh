#!/system/bin/sh

MODDIR=${0%/*}

. "$MODDIR/persistent_data.sh"
prepare_persistent_logs "$MODDIR" "$MODDIR" || exit 1
prepare_persistent_stats "$MODDIR"

LOGS_DIR="$MODDIR/logs"
RUNNING_LOG_FILE="$LOGS_DIR/running.log"
STATS_FILE="$MODDIR/stats"
LOGD_PID_FILE="$LOGS_DIR/.logd.pid"
CONFIG_EVENT_COLLECTOR_PID_FILE="$LOGS_DIR/.config_event_collector.pid"
PACKAGE_EVENT_COLLECTOR_PID_FILE="$LOGS_DIR/.package_event_collector.pid"
CONFIG_STATE_FILE="$LOGS_DIR/.config_apps_state"
UID_MAP_LAST_REFRESH_FILE="$LOGS_DIR/.uid_map_last_refresh"
CONFIG_DIR="$MODDIR/config"
SYSTEM_WRITER_UIDS_FILE="$CONFIG_DIR/system_writer_uids.list"
APPS_CONFIG_DIR="$CONFIG_DIR/apps"
SHARED_CONFIG_DIR="/dev/srx_config"
BOOT_PENDING_FILE="$MODDIR/.boot_pending"
BOOT_OK_FILE="$MODDIR/.boot_ok"
LOGD_BIN_ROOT="$MODDIR/bin"
LOGD_BIN_NAME="srx_logd"

mkdir -p "$LOGS_DIR"
chmod 755 "$LOGS_DIR"

SERVICE_DIR="$MODDIR/service.d"
SERVICE_PARTS="common.sh logd.sh file_monitor.sh config_events.sh boot.sh"

for service_name in $SERVICE_PARTS; do
  service_part="$SERVICE_DIR/$service_name"
  if [ ! -r "$service_part" ]; then
    log -p e -t Boot "missing service part: $service_part"
    exit 1
  fi
  . "$service_part"
done

start_log_daemon
start_file_monitor_service
boot_guard_wait &
