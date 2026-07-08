package com.storage.redirect.x.util

// 模块文件路径常量
object Paths {
    const val MODULE_DIR = "/data/adb/modules/storage.redirect.x"
    const val CONFIG_DIR = "$MODULE_DIR/config"
    const val GLOBAL_CONFIG_FILE = "$CONFIG_DIR/global.json"
    const val APPS_CONFIG_DIR = "$CONFIG_DIR/apps"
    const val SYSTEM_WRITER_UIDS_FILE = "$CONFIG_DIR/system_writer_uids.list"
    const val SHARED_CONFIG_DIR = "/dev/srx_config"
    const val SHARED_GLOBAL_CONFIG_FILE = "$SHARED_CONFIG_DIR/global.json"
    const val SHARED_APPS_CONFIG_DIR = "$SHARED_CONFIG_DIR/apps"
    const val BACKUP_DIR = "/data/local/tmp/storage_redirect_x"
    const val APPS_CONFIG_BACKUP_FILE = "$BACKUP_DIR/apps_config_backup.json"
    const val LOGS_DIR = "$MODULE_DIR/logs"
    const val RUNNING_LOG = "$LOGS_DIR/running.log"
    const val FILE_MONITOR_LOG = "$LOGS_DIR/file_monitor.log"
    const val MEDIA_PROVIDER_STATE_LOG = "$LOGS_DIR/media_provider_state.log"
    const val APP_STATUS_LOG = "$LOGS_DIR/app_status.log"
    const val GLOBAL_STATS_FILE = "/data/local/tmp/storage.redirect.x_stats"

    fun appConfigFile(packageName: String): String = "$APPS_CONFIG_DIR/$packageName.json"
    fun sharedAppConfigFile(packageName: String): String = "$SHARED_APPS_CONFIG_DIR/$packageName.json"
}
