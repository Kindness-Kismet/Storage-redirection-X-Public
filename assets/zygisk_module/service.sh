#!/system/bin/sh

MODDIR=${0%/*}

LOGS_DIR="$MODDIR/logs"
RUNNING_LOG_FILE="$LOGS_DIR/running.log"
FILE_MONITOR_LOG_FILE="$LOGS_DIR/file_monitor.log"
MEDIA_STATE_LOG_FILE="$LOGS_DIR/media_provider_state.log"
APP_STATUS_LOG_FILE="$LOGS_DIR/app_status.log"
STATS_FILE="$MODDIR/stats"
LOGD_PID_FILE="$LOGS_DIR/.logd.pid"
MEDIA_STATE_COLLECTOR_PID_FILE="$LOGS_DIR/.media_state_collector.pid"
APP_STATUS_SNAPSHOT_PID_FILE="$LOGS_DIR/.app_status_snapshot.pid"
MEDIA_STATE_LAST_PID_FILE="$LOGS_DIR/.media_state_last_pid"
MEDIA_STATE_DETAIL_TS_FILE="$LOGS_DIR/.media_state_detail_ts"
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
SERVICE_PARTS="common.sh logd.sh media_state.sh app_status.sh config_events.sh boot.sh"

for service_name in $SERVICE_PARTS; do
  service_part="$SERVICE_DIR/$service_name"
  if [ ! -r "$service_part" ]; then
    log -p e -t Boot "missing service part: $service_part"
    exit 1
  fi
  . "$service_part"
done

start_log_daemon || exit 1
boot_guard_wait &
