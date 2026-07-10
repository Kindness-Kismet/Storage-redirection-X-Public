pub const MODULE_DIR: &str = "/data/adb/modules/storage.redirect.x";
pub const CONFIG_DIR: &str = "/data/adb/modules/storage.redirect.x/config";
// bind mount 到所有进程可访问的位置，系统代写进程降权后仍能读取
pub const SHARED_CONFIG_DIR: &str = "/dev/srx_config";
pub const SHARED_SYSTEM_WRITER_UIDS_FILE: &str = "/dev/srx_config/system_writer_uids.list";
pub const MODULE_SYSTEM_WRITER_UIDS_FILE: &str =
    "/data/adb/modules/storage.redirect.x/config/system_writer_uids.list";
pub const LOG_DIR: &str = "/data/adb/modules/storage.redirect.x/logs";
