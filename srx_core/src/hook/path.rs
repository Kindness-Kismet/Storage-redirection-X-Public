use super::stats::InterceptHub;
use crate::platform::paths;
use libc::{AT_FDCWD, readlink};
use std::ffi::CString;

// /proc/self/fd/<N> symlink 解析
pub fn resolve_dirfd_path(dirfd: i32) -> String {
    if dirfd == AT_FDCWD {
        return "AT_FDCWD".to_string();
    }

    let link_path = format!("/proc/self/fd/{}", dirfd);
    let Ok(c_path) = CString::new(link_path) else {
        return "<bad-dirfd>".to_string();
    };

    let mut buffer = [0u8; libc::PATH_MAX as usize + 1];
    let len = unsafe {
        readlink(
            c_path.as_ptr(),
            buffer.as_mut_ptr() as *mut _,
            buffer.len() - 1,
        )
    };
    if len <= 0 {
        return "<unresolved>".to_string();
    }
    buffer[len as usize] = 0;
    String::from_utf8_lossy(&buffer[..len as usize]).to_string()
}

pub fn resolve_path_for_dirfd(dirfd: i32, pathname: &str) -> String {
    if pathname.is_empty() {
        return String::new();
    }

    if pathname.starts_with('/') {
        return paths::normalize(pathname);
    }

    let dirfd_path = resolve_dirfd_path(dirfd);
    if dirfd_path.is_empty() || !dirfd_path.starts_with('/') {
        return String::new();
    }

    let mut merged = dirfd_path;
    if !merged.ends_with('/') {
        merged.push('/');
    }
    merged.push_str(pathname);
    paths::normalize(&merged)
}

pub fn is_storage_path_fast(pathname: &str) -> bool {
    if pathname.is_empty() || !pathname.starts_with('/') {
        return false;
    }

    let normalized = paths::normalize(pathname);
    paths::starts_with(&normalized, "/storage/emulated/")
}

pub fn is_data_media_path_fast(pathname: &str) -> bool {
    if pathname.is_empty() || !pathname.starts_with('/') {
        return false;
    }

    let normalized = paths::normalize(pathname);
    paths::starts_with(&normalized, "/data/media/")
}

// 启用重定向时 /data/media 也视为相关
pub fn is_relevant_storage_path(hub: &InterceptHub, pathname: &str) -> bool {
    if is_storage_path_fast(pathname) {
        return true;
    }

    if hub.is_redirect_enabled() && is_data_media_path_fast(pathname) {
        return true;
    }
    false
}
