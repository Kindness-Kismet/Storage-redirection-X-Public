// 启动摘要：每次开机记录一次模块状态
use crate::config::SettingsHub;
use crate::logging::Logger;
use crate::platform::fs;
use crate::platform::module_paths;
use crate::platform::unique_fd::UniqueFd;
use libc::{EEXIST, O_CLOEXEC, O_CREAT, O_EXCL, O_RDONLY, O_WRONLY, open};
use std::ffi::CString;

const BOOT_ID_PATH: &str = "/proc/sys/kernel/random/boot_id";

// 每次开机仅记录一次启动摘要，通过 marker 文件去重
pub fn log_boot_summary_once() {
    Logger::init(None);
    if !fs::create_directory(module_paths::LOG_DIR, -1) {
        log::info!("log dir create failed, skip boot summary");
        return;
    }

    let boot_id = read_boot_id();
    if boot_id.is_empty() {
        log::info!("boot_id empty, skip summary");
    }

    if let Ok(entries) = std::fs::read_dir(module_paths::LOG_DIR) {
        let mut marker_paths = Vec::new();
        let current_marker = if boot_id.is_empty() {
            String::new()
        } else {
            format!("{}/boot_{}.marker", module_paths::LOG_DIR, boot_id)
        };

        for entry in entries.flatten() {
            let name = entry.file_name();
            let name = name.to_string_lossy();
            if !name.starts_with("boot_") || !name.ends_with(".marker") {
                continue;
            }
            let marker_path = format!("{}/{}", module_paths::LOG_DIR, name);
            if !current_marker.is_empty() && marker_path == current_marker {
                continue;
            }
            marker_paths.push(marker_path);
        }

        for marker_path in marker_paths {
            let _ = std::fs::remove_file(marker_path);
        }
    }

    if boot_id.is_empty() {
        return;
    }

    let marker_path = format!("{}/boot_{}.marker", module_paths::LOG_DIR, boot_id);
    let marker_path_display = marker_path.clone();
    let Ok(c_path) = CString::new(marker_path) else {
        log::info!("boot marker path invalid");
        return;
    };
    let fd = unsafe {
        open(
            c_path.as_ptr(),
            O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC,
            0o600,
        )
    };
    if fd < 0 {
        let error_no = unsafe { *libc::__errno() };
        // EEXIST 表示标记已存在，属于正常情况，无需打印
        if error_no != EEXIST {
            log::info!(
                "boot marker create failed path={} errno={}",
                marker_path_display,
                error_no
            );
        }
        return;
    }
    let mut marker_fd = UniqueFd::new(fd);
    marker_fd.reset();

    let settings = SettingsHub::instance();
    settings.init(None);

    let is_file_monitor_enabled = settings.is_file_monitor_enabled();
    let app_count = settings.get_app_count();

    log::info!(
        "boot ok monitor={} apps={}",
        is_file_monitor_enabled,
        app_count
    );
}

fn read_boot_id() -> String {
    let Ok(c_path) = CString::new(BOOT_ID_PATH) else {
        return String::new();
    };
    let fd = unsafe { open(c_path.as_ptr(), O_RDONLY | O_CLOEXEC) };
    if fd < 0 {
        return String::new();
    }

    let file = UniqueFd::new(fd);
    let mut buffer = [0u8; 128];
    let n = unsafe { libc::read(file.get(), buffer.as_mut_ptr() as *mut _, buffer.len() - 1) };
    if n <= 0 {
        return String::new();
    }
    buffer[n as usize] = 0;
    let text = String::from_utf8_lossy(&buffer[..n as usize]);
    trim_ascii(&text)
}

fn trim_ascii(value: &str) -> String {
    let trimmed = value.trim_matches(|c| c == ' ' || c == '\n' || c == '\r' || c == '\t');
    trimmed.to_string()
}
