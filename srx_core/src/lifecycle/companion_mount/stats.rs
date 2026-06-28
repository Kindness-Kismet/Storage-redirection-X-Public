use crate::platform::unique_fd::UniqueFd;
use crate::platform::{fs, module_paths};
use libc::{O_CLOEXEC, O_CREAT, O_RDONLY, O_TRUNC, O_WRONLY, c_void, open, read};

pub(super) fn update_redirect_stats() {
    let stats_file = module_paths::MODULE_STATS_FILE;
    let mut current_count: u64 = 0;
    {
        let Ok(c_path) = std::ffi::CString::new(stats_file) else {
            return;
        };
        let fd = unsafe { open(c_path.as_ptr(), O_RDONLY | O_CLOEXEC) };
        if fd >= 0 {
            let file = UniqueFd::new(fd);
            let mut buf = [0u8; 32];
            let n = unsafe { read(file.get(), buf.as_mut_ptr() as *mut c_void, buf.len() - 1) };
            if n > 0
                && let Ok(text) = std::str::from_utf8(&buf[..n as usize])
            {
                current_count = text.trim().parse::<u64>().unwrap_or(0);
            }
        }
    }

    current_count += 1;
    let Ok(c_path) = std::ffi::CString::new(stats_file) else {
        return;
    };
    let fd = unsafe {
        open(
            c_path.as_ptr(),
            O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC,
            0o644,
        )
    };
    if fd < 0 {
        log::warn!("stats open failed");
        return;
    }
    let file = UniqueFd::new(fd);
    let text = current_count.to_string();
    if !fs::write_all(file.get(), text.as_bytes()) {
        log::warn!("stats write failed");
        return;
    }

    log::info!("stats count={}", current_count);
}
