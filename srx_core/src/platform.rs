#[path = "platform/anti_detect.rs"]
pub mod anti_detect;
#[path = "platform/elf_img.rs"]
pub mod elf_img;
#[path = "platform/fs.rs"]
pub mod fs;
#[path = "platform/gnu_debugdata.rs"]
pub mod gnu_debugdata;
#[path = "platform/linker.rs"]
pub mod linker;
#[path = "platform/module_paths.rs"]
pub mod module_paths;
#[path = "platform/paths.rs"]
pub mod paths;
#[path = "platform/unique_fd.rs"]
pub mod unique_fd;

pub const ANDROID_USER_ID_OFFSET: i32 = 100000;
pub const ANDROID_APP_UID_START: i32 = 10000;
pub const MIN_SUPPORTED_API_LEVEL: i32 = 31;
const ISOLATED_APP_ID_START: i32 = 99000;
const ISOLATED_APP_ID_END: i32 = 99999;

pub fn android_api_level() -> i32 {
    unsafe { android_get_device_api_level() }
}

pub fn user_id_from_uid(uid: i32) -> i32 {
    if uid >= 0 {
        uid / ANDROID_USER_ID_OFFSET
    } else {
        0
    }
}

// 隔离进程 UID（app_id 99000-99999）无存储访问权限
pub fn is_isolated_uid(uid: i32) -> bool {
    if uid < 0 {
        return false;
    }
    let app_id = uid % ANDROID_USER_ID_OFFSET;
    (ISOLATED_APP_ID_START..=ISOLATED_APP_ID_END).contains(&app_id)
}

unsafe extern "C" {
    fn android_get_device_api_level() -> i32;
}
