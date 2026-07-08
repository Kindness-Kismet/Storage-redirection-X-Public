use super::super::diagnostic;
use super::super::media_fuse;
use super::super::monitor;
use super::super::path as path_utils;
use super::super::runtime;
use super::super::stats::InterceptHub;
use super::super::util::c_str_to_string;
use crate::platform::paths;
use crate::redirect::{policy, process_redirect_path, record_redirect_hit};
use libc::{AT_FDCWD, c_char, c_int, c_void, mode_t, off_t, timespec};
use std::ffi::CString;

pub unsafe extern "C" fn hooked_mkdir(pathname: *const c_char, mode: mode_t) -> c_int {
    let self_ptr = hooked_mkdir as *mut c_void;
    runtime::with_hook_guard(
        || {
            runtime::call_prev(
                self_ptr,
                || libc::mkdir(pathname, mode),
                |prev| {
                    let f: unsafe extern "C" fn(*const c_char, mode_t) -> c_int =
                        std::mem::transmute(prev);
                    f(pathname, mode)
                },
            )
        },
        |hub| {
            hub.increment_mkdir_calls();
            if runtime::should_resolve_caller_context(hub) {
                super::super::caller::update_caller_package_for_current_thread(hub);
            }

            handle_mkdir_like(hub, "mkdir", AT_FDCWD, pathname, mode, |call_path| {
                runtime::call_prev(
                    self_ptr,
                    || libc::mkdir(call_path, mode),
                    |prev| {
                        let f: unsafe extern "C" fn(*const c_char, mode_t) -> c_int =
                            std::mem::transmute(prev);
                        f(call_path, mode)
                    },
                )
            })
        },
    )
}

pub unsafe extern "C" fn hooked_mkdirat(
    dirfd: c_int,
    pathname: *const c_char,
    mode: mode_t,
) -> c_int {
    let self_ptr = hooked_mkdirat as *mut c_void;
    runtime::with_hook_guard(
        || {
            runtime::call_prev(
                self_ptr,
                || libc::mkdirat(dirfd, pathname, mode),
                |prev| {
                    let f: unsafe extern "C" fn(c_int, *const c_char, mode_t) -> c_int =
                        std::mem::transmute(prev);
                    f(dirfd, pathname, mode)
                },
            )
        },
        |hub| {
            hub.increment_mkdir_calls();
            if runtime::should_resolve_caller_context(hub) {
                super::super::caller::update_caller_package_for_current_thread(hub);
            }

            handle_mkdir_like(hub, "mkdirat", dirfd, pathname, mode, |call_path| {
                runtime::call_prev(
                    self_ptr,
                    || libc::mkdirat(dirfd, call_path, mode),
                    |prev| {
                        let f: unsafe extern "C" fn(c_int, *const c_char, mode_t) -> c_int =
                            std::mem::transmute(prev);
                        f(dirfd, call_path, mode)
                    },
                )
            })
        },
    )
}

pub unsafe extern "C" fn hooked_unlink(pathname: *const c_char) -> c_int {
    let self_ptr = hooked_unlink as *mut c_void;
    runtime::with_hook_guard(
        || {
            runtime::call_prev(
                self_ptr,
                || libc::unlink(pathname),
                |prev| {
                    let f: unsafe extern "C" fn(*const c_char) -> c_int = std::mem::transmute(prev);
                    f(pathname)
                },
            )
        },
        |hub| {
            hub.increment_unlink_calls();
            if runtime::should_resolve_caller_context(hub) {
                super::super::caller::update_caller_package_for_current_thread(hub);
            }

            handle_unlink_like(hub, "unlink", AT_FDCWD, pathname, -1, false, |call_path| {
                runtime::call_prev(
                    self_ptr,
                    || libc::unlink(call_path),
                    |prev| {
                        let f: unsafe extern "C" fn(*const c_char) -> c_int =
                            std::mem::transmute(prev);
                        f(call_path)
                    },
                )
            })
        },
    )
}

pub unsafe extern "C" fn hooked_unlinkat(
    dirfd: c_int,
    pathname: *const c_char,
    flags: c_int,
) -> c_int {
    let self_ptr = hooked_unlinkat as *mut c_void;
    let call_original = |call_path: *const c_char| -> c_int {
        runtime::call_prev(
            self_ptr,
            || unsafe { libc::syscall(libc::SYS_unlinkat, dirfd, call_path, flags) as c_int },
            |prev| {
                let f: unsafe extern "C" fn(c_int, *const c_char, c_int) -> c_int =
                    unsafe { std::mem::transmute(prev) };
                unsafe { f(dirfd, call_path, flags) }
            },
        )
    };

    runtime::with_hook_guard(
        || call_original(pathname),
        |hub| {
            hub.increment_unlink_calls();
            if runtime::should_resolve_caller_context(hub) {
                super::super::caller::update_caller_package_for_current_thread(hub);
            }

            handle_unlink_like(hub, "unlinkat", dirfd, pathname, flags, true, call_original)
        },
    )
}

fn handle_mkdir_like<F>(
    hub: &InterceptHub,
    op_name: &str,
    dirfd: c_int,
    pathname: *const c_char,
    mode: mode_t,
    call_original: F,
) -> c_int
where
    F: FnOnce(*const c_char) -> c_int,
{
    if pathname.is_null() {
        return call_original(pathname);
    }

    let path_text = unsafe { c_str_to_string(pathname) };
    if path_text.is_empty() || !path_text.starts_with('/') {
        diagnostic::log_relative_path_bypass(hub, op_name, dirfd, &path_text, mode as i32);
        return call_original(pathname);
    }

    diagnostic::log_diag_path_event(hub, op_name, "input", &path_text, mode as i32);

    if hub.is_monitor_only() {
        let result = call_original(pathname);
        let error_no = if result < 0 {
            unsafe { *libc::__errno() }
        } else {
            0
        };
        monitor::record_mkdir_result(hub, op_name, &path_text, result, error_no);
        return result;
    }

    if !path_utils::is_relevant_storage_path(hub, &path_text) {
        diagnostic::record_fast_bypass(op_name, &path_text);
        return call_original(pathname);
    }

    let redirect_result = process_redirect_path(hub, &path_text);
    diagnostic::log_diag_redirect_decision(hub, op_name, &path_text, &redirect_result);

    let result = if redirect_result.is_redirect() {
        record_redirect_hit(hub, op_name, &path_text, &redirect_result.new_path);
        runtime::ensure_redirect_parent_dirs(&redirect_result.new_path, mode);
        if let Ok(c_path) = CString::new(redirect_result.new_path) {
            call_original(c_path.as_ptr())
        } else {
            call_original(pathname)
        }
    } else {
        call_original(pathname)
    };
    let error_no = if result < 0 {
        unsafe { *libc::__errno() }
    } else {
        0
    };
    monitor::record_mkdir_result(hub, op_name, &path_text, result, error_no);
    result
}

fn handle_unlink_like<F>(
    hub: &InterceptHub,
    op_name: &str,
    dirfd: c_int,
    pathname: *const c_char,
    flags: i32,
    should_preserve_errno: bool,
    call_original: F,
) -> c_int
where
    F: FnOnce(*const c_char) -> c_int,
{
    if pathname.is_null() {
        return call_original(pathname);
    }

    let path_text = unsafe { c_str_to_string(pathname) };
    if path_text.is_empty() || !path_text.starts_with('/') {
        diagnostic::log_relative_path_bypass(hub, op_name, dirfd, &path_text, flags);
        return call_original(pathname);
    }

    diagnostic::log_diag_path_event(hub, op_name, "input", &path_text, flags);

    if hub.is_monitor_only() {
        let result = call_original(pathname);
        let current_errno = unsafe { *libc::__errno() };
        if should_preserve_errno {
            unsafe { *libc::__errno() = current_errno };
        }
        return result;
    }

    if !path_utils::is_relevant_storage_path(hub, &path_text) {
        diagnostic::record_fast_bypass(op_name, &path_text);
        return call_original(pathname);
    }

    let redirect_result = process_redirect_path(hub, &path_text);
    diagnostic::log_diag_redirect_decision(hub, op_name, &path_text, &redirect_result);

    let result = if redirect_result.is_redirect() {
        record_redirect_hit(hub, op_name, &path_text, &redirect_result.new_path);
        if let Ok(c_path) = CString::new(redirect_result.new_path) {
            call_original(c_path.as_ptr())
        } else {
            call_original(pathname)
        }
    } else {
        call_original(pathname)
    };
    let current_errno = unsafe { *libc::__errno() };
    if should_preserve_errno {
        unsafe { *libc::__errno() = current_errno };
    }
    result
}

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
                super::super::caller::update_caller_package_for_current_thread(hub);
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
                super::super::caller::update_caller_package_for_current_thread(hub);
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
                super::super::caller::update_caller_package_for_current_thread(hub);
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
        || !path_utils::is_relevant_storage_path(hub, &path_for_decision)
    {
        return call_original();
    }

    diagnostic::log_diag_path_event(hub, op_name, "input", &path_for_decision, fd);
    let mut result = call_original();
    let original_errno = current_errno();
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
        || !path_utils::is_relevant_storage_path(hub, &path_for_decision)
    {
        return call_original();
    }

    diagnostic::log_diag_path_event(hub, op_name, "input", &path_for_decision, fd);
    let mut result = call_original();
    let original_errno = current_errno();
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

// FuseDaemon 通过 mknod 创建文件节点，必须 hook
pub unsafe extern "C" fn hooked_mknod(
    pathname: *const c_char,
    mode: mode_t,
    dev: libc::dev_t,
) -> c_int {
    let self_ptr = hooked_mknod as *mut c_void;
    runtime::with_hook_guard(
        || {
            runtime::call_prev(
                self_ptr,
                || libc::mknod(pathname, mode, dev),
                |prev| {
                    let f: unsafe extern "C" fn(*const c_char, mode_t, libc::dev_t) -> c_int =
                        std::mem::transmute(prev);
                    f(pathname, mode, dev)
                },
            )
        },
        |hub| {
            hub.increment_mkdir_calls();
            if runtime::should_resolve_caller_context(hub) {
                super::super::caller::update_caller_package_for_current_thread(hub);
            }

            handle_mknod_like(hub, "mknod", pathname, mode, |call_path| {
                runtime::call_prev(
                    self_ptr,
                    || libc::mknod(call_path, mode, dev),
                    |prev| {
                        let f: unsafe extern "C" fn(*const c_char, mode_t, libc::dev_t) -> c_int =
                            std::mem::transmute(prev);
                        f(call_path, mode, dev)
                    },
                )
            })
        },
    )
}

pub unsafe extern "C" fn hooked_mknodat(
    dirfd: c_int,
    pathname: *const c_char,
    mode: mode_t,
    dev: libc::dev_t,
) -> c_int {
    let self_ptr = hooked_mknodat as *mut c_void;
    runtime::with_hook_guard(
        || {
            runtime::call_prev(
                self_ptr,
                || libc::syscall(libc::SYS_mknodat, dirfd, pathname, mode, dev) as c_int,
                |prev| {
                    let f: unsafe extern "C" fn(
                        c_int,
                        *const c_char,
                        mode_t,
                        libc::dev_t,
                    ) -> c_int = std::mem::transmute(prev);
                    f(dirfd, pathname, mode, dev)
                },
            )
        },
        |hub| {
            hub.increment_mkdir_calls();
            if runtime::should_resolve_caller_context(hub) {
                super::super::caller::update_caller_package_for_current_thread(hub);
            }

            handle_mknod_like(hub, "mknodat", pathname, mode, |call_path| {
                runtime::call_prev(
                    self_ptr,
                    || libc::syscall(libc::SYS_mknodat, dirfd, call_path, mode, dev) as c_int,
                    |prev| {
                        let f: unsafe extern "C" fn(
                            c_int,
                            *const c_char,
                            mode_t,
                            libc::dev_t,
                        ) -> c_int = std::mem::transmute(prev);
                        f(dirfd, call_path, mode, dev)
                    },
                )
            })
        },
    )
}

// 统一按文件创建事件记录
fn handle_mknod_like<F>(
    hub: &InterceptHub,
    op_name: &str,
    pathname: *const c_char,
    mode: mode_t,
    call_original: F,
) -> c_int
where
    F: FnOnce(*const c_char) -> c_int,
{
    if pathname.is_null() {
        return call_original(pathname);
    }

    let path_text = unsafe { c_str_to_string(pathname) };
    if path_text.is_empty() || !path_text.starts_with('/') {
        return call_original(pathname);
    }

    diagnostic::log_diag_path_event(hub, op_name, "input", &path_text, mode as i32);

    if hub.is_monitor_only() {
        let result = call_original(pathname);
        let error_no = if result < 0 {
            unsafe { *libc::__errno() }
        } else {
            0
        };
        monitor::record_mkdir_result(hub, op_name, &path_text, result, error_no);
        return result;
    }

    if !path_utils::is_relevant_storage_path(hub, &path_text) {
        diagnostic::record_fast_bypass(op_name, &path_text);
        return call_original(pathname);
    }

    let redirect_result = process_redirect_path(hub, &path_text);
    diagnostic::log_diag_redirect_decision(hub, op_name, &path_text, &redirect_result);

    let result = if redirect_result.is_redirect() {
        record_redirect_hit(hub, op_name, &path_text, &redirect_result.new_path);
        runtime::ensure_redirect_parent_dirs(&redirect_result.new_path, mode);
        if let Ok(c_path) = CString::new(redirect_result.new_path) {
            call_original(c_path.as_ptr())
        } else {
            call_original(pathname)
        }
    } else {
        call_original(pathname)
    };
    let error_no = if result < 0 {
        unsafe { *libc::__errno() }
    } else {
        0
    };
    monitor::record_mkdir_result(hub, op_name, &path_text, result, error_no);
    result
}
