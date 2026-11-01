use crate::hook::diagnostic;
use crate::hook::media_fuse;
use crate::hook::path as path_utils;
use crate::hook::runtime;
use crate::hook::stats::InterceptHub;
use crate::platform::paths;
use crate::redirect::policy;
use libc::{c_int, c_void, off_t, timespec};
use std::ffi::CString;

pub unsafe extern "C" fn hooked_ftruncate(fd: c_int, length: off_t) -> c_int {
    let self_ptr = hooked_ftruncate as *mut c_void;
    runtime::with_hook_guard(
        || {
            runtime::call_prev(
                self_ptr,
                || libc::ftruncate(fd, length),
                |prev| {
                    let f: unsafe extern "C" fn(c_int, off_t) -> c_int = std::mem::transmute(prev);
                    f(fd, length)
                },
            )
        },
        |hub| {
            hub.increment_open_calls();
            if runtime::should_resolve_caller_context(hub) {
                crate::hook::caller::update_caller_package_for_current_thread(hub);
            }

            handle_ftruncate_fd(hub, "ftruncate", fd, length, || {
                runtime::call_prev(
                    self_ptr,
                    || libc::ftruncate(fd, length),
                    |prev| {
                        let f: unsafe extern "C" fn(c_int, off_t) -> c_int =
                            std::mem::transmute(prev);
                        f(fd, length)
                    },
                )
            })
        },
    )
}

pub unsafe extern "C" fn hooked_ftruncate64(fd: c_int, length: off_t) -> c_int {
    let self_ptr = hooked_ftruncate64 as *mut c_void;
    runtime::with_hook_guard(
        || {
            runtime::call_prev(
                self_ptr,
                || libc::ftruncate(fd, length),
                |prev| {
                    let f: unsafe extern "C" fn(c_int, off_t) -> c_int = std::mem::transmute(prev);
                    f(fd, length)
                },
            )
        },
        |hub| {
            hub.increment_open_calls();
            if runtime::should_resolve_caller_context(hub) {
                crate::hook::caller::update_caller_package_for_current_thread(hub);
            }

            handle_ftruncate_fd(hub, "ftruncate64", fd, length, || {
                runtime::call_prev(
                    self_ptr,
                    || libc::ftruncate(fd, length),
                    |prev| {
                        let f: unsafe extern "C" fn(c_int, off_t) -> c_int =
                            std::mem::transmute(prev);
                        f(fd, length)
                    },
                )
            })
        },
    )
}

pub unsafe extern "C" fn hooked_futimens(fd: c_int, times: *const timespec) -> c_int {
    let self_ptr = hooked_futimens as *mut c_void;
    runtime::with_hook_guard(
        || {
            runtime::call_prev(
                self_ptr,
                || libc::futimens(fd, times),
                |prev| {
                    let f: unsafe extern "C" fn(c_int, *const timespec) -> c_int =
                        std::mem::transmute(prev);
                    f(fd, times)
                },
            )
        },
        |hub| {
            hub.increment_open_calls();
            if runtime::should_resolve_caller_context(hub) {
                crate::hook::caller::update_caller_package_for_current_thread(hub);
            }

            handle_futimens_fd(hub, "futimens", fd, times, || {
                runtime::call_prev(
                    self_ptr,
                    || libc::futimens(fd, times),
                    |prev| {
                        let f: unsafe extern "C" fn(c_int, *const timespec) -> c_int =
                            std::mem::transmute(prev);
                        f(fd, times)
                    },
                )
            })
        },
    )
}

fn handle_ftruncate_fd<F>(
    hub: &InterceptHub,
    op_name: &str,
    fd: c_int,
    length: off_t,
    call_original: F,
) -> c_int
where
    F: FnOnce() -> c_int,
{
    let path_for_decision = path_utils::resolve_dirfd_path(fd);
    if path_for_decision.is_empty()
        || !path_for_decision.starts_with('/')
        || !path_utils::is_relevant_storage_path(&path_for_decision)
    {
        return call_original();
    }

    diagnostic::log_diag_path_event(hub, op_name, "input", &path_for_decision, fd);
    let mut result = call_original();
    let original_errno = current_errno();
    if !paths::is_sqlite_database_or_sidecar_path(&path_for_decision) {
        set_errno(original_errno);
        return result;
    }
    if let Some(retry_result) = confirm_private_owner_sqlite_ftruncate(
        hub,
        op_name,
        fd,
        length,
        &path_for_decision,
        original_errno,
    ) {
        if result < 0 {
            result = retry_result;
        }
        set_errno(0);
        return result;
    }
    set_errno(original_errno);
    result
}

fn confirm_private_owner_sqlite_ftruncate(
    hub: &InterceptHub,
    op_name: &str,
    fd: c_int,
    length: off_t,
    path_for_decision: &str,
    original_errno: i32,
) -> Option<c_int> {
    let (storage_path, backend_path, effective_uid) =
        resolve_private_owner_sqlite_backend(hub, path_for_decision)?;
    if !media_fuse::ensure_backend_parent_dir(&backend_path, &storage_path) {
        return None;
    }
    let Ok(c_path) = CString::new(backend_path.as_str()) else {
        return None;
    };

    let fd_flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
    let retry_fd = unsafe { libc::open(c_path.as_ptr(), libc::O_RDWR | libc::O_CLOEXEC) };
    if retry_fd < 0 {
        log::warn!(
            "{} private owner sqlite backend truncate open failed errno={} original_errno={} fd={} fd_flags=0x{:x} uid={} path={} storage={} backend={}",
            op_name,
            current_errno(),
            original_errno,
            fd,
            fd_flags,
            effective_uid,
            path_for_decision,
            storage_path,
            backend_path
        );
        set_errno(original_errno);
        return None;
    }

    let backend_length =
        media_fuse::adjusted_private_owner_sqlite_truncate_length(&storage_path, length);
    let result = unsafe { libc::ftruncate(retry_fd, backend_length) };
    let retry_errno = current_errno();
    let backend_size = backend_fd_size(retry_fd);
    unsafe {
        libc::close(retry_fd);
    }
    if result == 0 {
        log::info!(
            "{} private owner sqlite backend truncate ok original_errno={} fd={} fd_flags=0x{:x} uid={} length={} requested_length={} size={} path={} storage={} backend={}",
            op_name,
            original_errno,
            fd,
            fd_flags,
            effective_uid,
            backend_length,
            length,
            backend_size,
            path_for_decision,
            storage_path,
            backend_path
        );
        return Some(0);
    }

    log::warn!(
        "{} private owner sqlite backend truncate failed original_errno={} retry_errno={} fd={} fd_flags=0x{:x} uid={} length={} requested_length={} size={} path={} storage={} backend={}",
        op_name,
        original_errno,
        retry_errno,
        fd,
        fd_flags,
        effective_uid,
        backend_length,
        length,
        backend_size,
        path_for_decision,
        storage_path,
        backend_path
    );
    set_errno(original_errno);
    None
}

fn handle_futimens_fd<F>(
    hub: &InterceptHub,
    op_name: &str,
    fd: c_int,
    times: *const timespec,
    call_original: F,
) -> c_int
where
    F: FnOnce() -> c_int,
{
    let path_for_decision = path_utils::resolve_dirfd_path(fd);
    if path_for_decision.is_empty()
        || !path_for_decision.starts_with('/')
        || !path_utils::is_relevant_storage_path(&path_for_decision)
    {
        return call_original();
    }

    diagnostic::log_diag_path_event(hub, op_name, "input", &path_for_decision, fd);
    let mut result = call_original();
    let original_errno = current_errno();
    if !paths::is_sqlite_database_or_sidecar_path(&path_for_decision) {
        set_errno(original_errno);
        return result;
    }
    if let Some(retry_result) = confirm_private_owner_sqlite_futimens(
        hub,
        op_name,
        fd,
        times,
        &path_for_decision,
        result,
        original_errno,
    ) {
        if result < 0 {
            result = retry_result;
        }
        set_errno(0);
        return result;
    }
    set_errno(original_errno);
    result
}

fn confirm_private_owner_sqlite_futimens(
    hub: &InterceptHub,
    op_name: &str,
    fd: c_int,
    times: *const timespec,
    path_for_decision: &str,
    original_result: c_int,
    original_errno: i32,
) -> Option<c_int> {
    let (storage_path, backend_path, effective_uid) =
        resolve_private_owner_sqlite_backend(hub, path_for_decision)?;
    if !media_fuse::ensure_backend_parent_dir(&backend_path, &storage_path) {
        return None;
    }
    let Ok(c_path) = CString::new(backend_path.as_str()) else {
        return None;
    };

    let fd_flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
    let retry_fd = unsafe { libc::open(c_path.as_ptr(), libc::O_RDWR | libc::O_CLOEXEC) };
    if retry_fd < 0 {
        log::warn!(
            "{} private owner sqlite backend futimens open failed ret={} errno={} original_errno={} fd={} fd_flags=0x{:x} uid={} path={} storage={} backend={}",
            op_name,
            original_result,
            current_errno(),
            original_errno,
            fd,
            fd_flags,
            effective_uid,
            path_for_decision,
            storage_path,
            backend_path
        );
        set_errno(original_errno);
        return None;
    }

    let result = unsafe { libc::futimens(retry_fd, times) };
    let retry_errno = current_errno();
    unsafe {
        libc::close(retry_fd);
    }
    if result == 0 {
        log::info!(
            "{} private owner sqlite backend futimens ok ret={} original_errno={} fd={} fd_flags=0x{:x} uid={} path={} storage={} backend={}",
            op_name,
            original_result,
            original_errno,
            fd,
            fd_flags,
            effective_uid,
            path_for_decision,
            storage_path,
            backend_path
        );
        return Some(0);
    }

    if original_result < 0
        && is_permission_errno(original_errno)
        && is_permission_errno(retry_errno)
    {
        log::debug!(
            "{} private owner sqlite futimens permission failure suppressed original_errno={} retry_errno={} fd={} uid={} path={} storage={} backend={}",
            op_name,
            original_errno,
            retry_errno,
            fd,
            effective_uid,
            path_for_decision,
            storage_path,
            backend_path
        );
        return Some(0);
    }

    log::warn!(
        "{} private owner sqlite backend futimens failed ret={} original_errno={} retry_errno={} fd={} fd_flags=0x{:x} uid={} path={} storage={} backend={}",
        op_name,
        original_result,
        original_errno,
        retry_errno,
        fd,
        fd_flags,
        effective_uid,
        path_for_decision,
        storage_path,
        backend_path
    );
    set_errno(original_errno);
    None
}

fn is_permission_errno(error_no: i32) -> bool {
    error_no == libc::EPERM || error_no == libc::EACCES
}

fn resolve_private_owner_sqlite_backend(
    hub: &InterceptHub,
    path_for_decision: &str,
) -> Option<(String, String, i32)> {
    let storage_path = paths::normalize(path_for_decision);
    if !paths::is_sqlite_database_or_sidecar_path(&storage_path) {
        return None;
    }
    let process_uid = unsafe { libc::getuid() as i32 };
    let package_name = hub.get_package_name();
    let caller_uid = hub.get_current_caller_uid();
    let caller_package = hub.get_current_caller_package();
    let effective_uid = if media_fuse::should_allow_private_owner_sqlite_access_for_caller(
        &storage_path,
        caller_uid,
        &caller_package,
    ) {
        caller_uid
    } else if media_fuse::should_allow_private_owner_sqlite_access_for_caller(
        &storage_path,
        process_uid,
        &package_name,
    ) {
        process_uid
    } else if policy::is_system_writer_package(&package_name)
        && let Some(owner_uid) =
            media_fuse::should_allow_private_owner_sqlite_owner_backend(&storage_path)
    {
        owner_uid
    } else if let Some(recent_uid) =
        media_fuse::has_recent_private_owner_sqlite_access(&storage_path)
    {
        recent_uid
    } else {
        log::debug!(
            "private owner sqlite backend unresolved pkg={} uid={} caller_uid={} caller={} path={} storage={}",
            package_name,
            process_uid,
            caller_uid,
            caller_package,
            path_for_decision,
            storage_path
        );
        return None;
    };

    let backend_path = storage_to_data_media_path(&storage_path);
    if backend_path == storage_path || !backend_path.starts_with("/data/media/") {
        return None;
    }

    Some((storage_path, backend_path, effective_uid))
}

fn backend_fd_size(fd: c_int) -> i64 {
    let mut statbuf = std::mem::MaybeUninit::<libc::stat>::uninit();
    let result = unsafe { libc::fstat(fd, statbuf.as_mut_ptr()) };
    if result != 0 {
        return -1;
    }
    let statbuf = unsafe { statbuf.assume_init() };
    statbuf.st_size
}

fn storage_to_data_media_path(path: &str) -> String {
    const STORAGE_PREFIX: &str = "/storage/emulated/";
    const DATA_MEDIA_PREFIX: &str = "/data/media/";
    if !path.starts_with(STORAGE_PREFIX) {
        return path.to_string();
    }
    format!("{}{}", DATA_MEDIA_PREFIX, &path[STORAGE_PREFIX.len()..])
}

fn current_errno() -> i32 {
    unsafe { *libc::__errno() }
}

fn set_errno(error_no: i32) {
    unsafe { *libc::__errno() = error_no };
}
