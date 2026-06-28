#!/system/bin/sh

MODDIR=${0%/*}

LOGS_DIR="$MODDIR/logs"
RUNNING_LOG_FILE="$LOGS_DIR/running.log"
FILE_MONITOR_LOG_FILE="$LOGS_DIR/file_monitor.log"
MEDIA_STATE_LOG_FILE="$LOGS_DIR/media_provider_state.log"
APP_STATUS_LOG_FILE="$LOGS_DIR/app_status.log"
STATS_FILE="$MODDIR/stats"
MAX_RUNNING_LOG_LINES=30000
MAX_MONITOR_LOG_LINES=30000
MAX_MEDIA_STATE_LOG_LINES=30000
MAX_APP_STATUS_LOG_LINES=30000
RUNNING_TRIM_BATCH_LINES=200
MONITOR_TRIM_BATCH_LINES=200
MEDIA_STATE_TRIM_BATCH_LINES=200
APP_STATUS_TRIM_BATCH_LINES=200
RUNNING_COLLECTOR_PID_FILE="$LOGS_DIR/.running_collector.pid"
MONITOR_COLLECTOR_PID_FILE="$LOGS_DIR/.monitor_collector.pid"
MEDIA_STATE_COLLECTOR_PID_FILE="$LOGS_DIR/.media_state_collector.pid"
APP_STATUS_COLLECTOR_PID_FILE="$LOGS_DIR/.app_status_collector.pid"
APP_STATUS_SNAPSHOT_PID_FILE="$LOGS_DIR/.app_status_snapshot.pid"
MEDIA_STATE_LAST_PID_FILE="$LOGS_DIR/.media_state_last_pid"
MEDIA_STATE_DETAIL_TS_FILE="$LOGS_DIR/.media_state_detail_ts"
STATS_COLLECTOR_PID_FILE="$LOGS_DIR/.stats_collector.pid"
CONFIG_EVENT_COLLECTOR_PID_FILE="$LOGS_DIR/.config_event_collector.pid"
PACKAGE_EVENT_COLLECTOR_PID_FILE="$LOGS_DIR/.package_event_collector.pid"
CONFIG_STATE_FILE="$LOGS_DIR/.config_apps_state"
UID_MAP_LAST_REFRESH_FILE="$LOGS_DIR/.uid_map_last_refresh"
CONFIG_DIR="$MODDIR/config"
SYSTEM_WRITER_UIDS_FILE="$CONFIG_DIR/system_writer_uids.list"
APPS_CONFIG_DIR="$CONFIG_DIR/apps"
BOOT_PENDING_FILE="$MODDIR/.boot_pending"
BOOT_OK_FILE="$MODDIR/.boot_ok"

mkdir -p "$LOGS_DIR"
chmod 755 "$LOGS_DIR"

SERVICE_DIR="$MODDIR/service.d"

SERVICE_PARTS="common.sh log_collectors.sh media_state.sh app_status.sh config_events.sh boot.sh"

for service_name in $SERVICE_PARTS; do
  service_part="$SERVICE_DIR/$service_name"
  if [ ! -r "$service_part" ]; then
    log -p e -t Boot "missing service part: $service_part"
    exit 1
  fi
  . "$service_part"
done

boot_guard_wait &
