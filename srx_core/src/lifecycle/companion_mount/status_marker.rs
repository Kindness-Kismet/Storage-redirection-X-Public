use super::sys::{errno_text, last_errno};
use crate::platform::fs;
use crate::platform::unique_fd::UniqueFd;
use libc::{O_CLOEXEC, O_CREAT, O_TRUNC, O_WRONLY, chmod, chown, open};
use std::ffi::CString;

pub(super) fn write_mount_status_marker(
    app_data_dir: &str,
    pid: i32,
    uid: i32,
    is_ok: bool,
) -> bool {
    if app_data_dir.is_empty() || pid <= 0 || uid < 0 {
        log::warn!(
            "marker args invalid dir={} pid={} uid={}",
            app_data_dir,
            pid,
            uid
        );
        return false;
    }

    let marker_path = format!("{}/.srx_mount_status_{}", app_data_dir, pid);
    let Ok(c_path) = CString::new(marker_path.clone()) else {
        log::warn!("marker path invalid {}", marker_path);
        return false;
    };
    let fd = unsafe {
        open(
            c_path.as_ptr(),
            O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC,
            0o600,
        )
    };
    if fd < 0 {
        let errno = last_errno();
        log::warn!(
            "marker open failed path={} pid={} errno={} {}",
            marker_path,
            pid,
            errno,
            errno_text(errno)
        );
        return false;
    }
    let file = UniqueFd::new(fd);

    let value = if is_ok { b'1' } else { b'0' };
    let mut marker_ok = true;
    if !fs::write_all(file.get(), &[value]) {
        marker_ok = false;
        log::warn!(
            "marker write failed path={} pid={} val={}",
            marker_path,
            pid,
            value as char
        );
    }
    if unsafe { libc::fsync(file.get()) } != 0 {
        marker_ok = false;
        let errno = last_errno();
        log::warn!(
            "marker fsync failed path={} pid={} errno={} {}",
            marker_path,
            pid,
            errno,
            errno_text(errno)
        );
    }

    unsafe {
        if chown(c_path.as_ptr(), uid as u32, uid as u32) != 0 {
            marker_ok = false;
            let errno = last_errno();
            log::warn!(
                "marker chown failed path={} pid={} uid={} errno={} {}",
                marker_path,
                pid,
                uid,
                errno,
                errno_text(errno)
            );
        }
        if chmod(c_path.as_ptr(), 0o600) != 0 {
            marker_ok = false;
            let errno = last_errno();
            log::warn!(
                "marker chmod failed path={} pid={} errno={} {}",
                marker_path,
                pid,
                errno,
                errno_text(errno)
            );
        }
    }
    if marker_ok {
        log::debug!(
            "marker ok path={} pid={} val={}",
            marker_path,
            pid,
            value as char
        );
    }
    marker_ok
}
