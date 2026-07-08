use super::super::diagnostic;
use super::super::monitor;
use super::super::path as path_utils;
use super::super::runtime;
use super::super::stats::InterceptHub;
use super::super::util::c_str_to_string;
use crate::redirect::{policy, process_redirect_path, record_redirect_hit};
use libc::{AT_FDCWD, c_char, c_int, c_void, mode_t};
use std::borrow::Cow;
use std::ffi::CString;

#[repr(C)]
pub struct OpenHow {
    pub flags: u64,
    pub mode: u64,
    pub resolve: u64,
}

unsafe fn call_open(pathname: *const c_char, flags: c_int, mode: mode_t) -> c_int {
    let self_ptr = hooked_open as *mut c_void;
    runtime::call_prev(
        self_ptr,
        || libc::open(pathname, flags, mode),
        |prev| {
            let f: unsafe extern "C" fn(*const c_char, c_int, mode_t) -> c_int =
                std::mem::transmute(prev);
            f(pathname, flags, mode)
        },
    )
}

unsafe fn call_openat(dirfd: c_int, pathname: *const c_char, flags: c_int, mode: mode_t) -> c_int {
    let self_ptr = hooked_openat as *mut c_void;
    runtime::call_prev(
        self_ptr,
        || libc::openat(dirfd, pathname, flags, mode),
        |prev| {
            let f: unsafe extern "C" fn(c_int, *const c_char, c_int, mode_t) -> c_int =
                std::mem::transmute(prev);
            f(dirfd, pathname, flags, mode)
        },
    )
}

// libc 无 openat2 封装，fallback 走 SYS_openat2 直调
unsafe fn call_openat2(
    dirfd: c_int,
    pathname: *const c_char,
    how: *const OpenHow,
    how_size: usize,
) -> c_int {
    let self_ptr = hooked_openat2 as *mut c_void;
    runtime::call_prev(
        self_ptr,
        || libc::syscall(libc::SYS_openat2, dirfd, pathname, how, how_size) as c_int,
        |prev| {
            let f: unsafe extern "C" fn(c_int, *const c_char, *const OpenHow, usize) -> c_int =
                std::mem::transmute(prev);
            f(dirfd, pathname, how, how_size)
        },
    )
}

fn storage_to_data_media_path(path: &str) -> Option<String> {
    const STORAGE_PREFIX: &str = "/storage/emulated/";
    const DATA_MEDIA_PREFIX: &str = "/data/media/";
    path.strip_prefix(STORAGE_PREFIX)
        .map(|suffix| format!("{}{}", DATA_MEDIA_PREFIX, suffix))
}

fn maybe_retry_system_writer_backend_open<F>(
    op_name: &str,
    from_path: &str,
    redirected_path: &str,
    flags: c_int,
    initial_result: c_int,
    initial_errno: c_int,
    call_backend: F,
) -> c_int
where
    F: FnOnce(&CString) -> c_int,
{
    if initial_result >= 0
        || initial_errno != libc::ENOENT
        || !monitor::has_write_intent_flags(flags)
    {
        return initial_result;
    }

    let Some(backend_path) = storage_to_data_media_path(redirected_path) else {
        return initial_result;
    };
    if backend_path == redirected_path {
        return initial_result;
    }

    runtime::ensure_redirect_parent_dirs(&backend_path, 0o775);
    let Ok(c_backend_path) = CString::new(backend_path.as_str()) else {
        return initial_result;
    };

    let retry_result = call_backend(&c_backend_path);
    let retry_errno = if retry_result < 0 {
        unsafe { *libc::__errno() }
    } else {
        0
    };
    log::info!(
        "write op={} backend retry from={} to={} backend={} ret={} errno={} retry_ret={} retry_errno={}",
        op_name,
        from_path,
        redirected_path,
        backend_path,
        initial_result,
        initial_errno,
        retry_result,
        retry_errno
    );

    if retry_result >= 0 {
        return retry_result;
    }
    unsafe { *libc::__errno() = initial_errno };
    initial_result
}

pub unsafe extern "C" fn hooked_open(pathname: *const c_char, flags: c_int, mode: mode_t) -> c_int {
    runtime::with_hook_guard(
        || call_open(pathname, flags, mode),
        |hub| {
            hub.increment_open_calls();
            if runtime::should_resolve_caller_context(hub) {
                super::super::caller::update_caller_package_for_current_thread(hub);
            }

            if pathname.is_null() {
                return call_open(pathname, flags, mode);
            }

            let path_text = c_str_to_string(pathname);
            if path_text.is_empty() {
                return call_open(pathname, flags, mode);
            }

            if !path_utils::is_relevant_storage_path(hub, &path_text) {
                diagnostic::record_fast_bypass("open", &path_text);
                return call_open(pathname, flags, mode);
            }

            diagnostic::log_diag_path_event(hub, "open", "input", &path_text, flags);

            let is_system_writer = policy::is_system_writer_package(&hub.get_package_name());
            let mut is_redirected = false;
            let mut final_path: Cow<'_, str> = Cow::Borrowed(path_text.as_str());
            // 系统代写进程仅对写入操作重定向，读取探测保持原路径避免触发 MediaProvider 路径校验
            let should_redirect = !hub.is_monitor_only()
                && (!is_system_writer || monitor::has_write_intent_flags(flags));
            if should_redirect {
                let redirect_result = process_redirect_path(hub, &path_text);
                diagnostic::log_diag_redirect_decision(hub, "open", &path_text, &redirect_result);
                if redirect_result.is_redirect() {
                    let new_path = redirect_result.new_path;
                    record_redirect_hit(hub, "open", &path_text, &new_path);
                    final_path = Cow::Owned(new_path);
                    is_redirected = true;
                }
            }

            runtime::ensure_redirect_parent_directory(
                "open",
                &path_text,
                final_path.as_ref(),
                flags,
            );
            let result = if let Ok(c_path) = CString::new(final_path.as_ref()) {
                let result = call_open(c_path.as_ptr(), flags, mode);
                let error_no = if result < 0 { *libc::__errno() } else { 0 };
                if is_redirected && is_system_writer {
                    maybe_retry_system_writer_backend_open(
                        "open",
                        &path_text,
                        final_path.as_ref(),
                        flags,
                        result,
                        error_no,
                        |backend| call_open(backend.as_ptr(), flags, mode),
                    )
                } else {
                    result
                }
            } else {
                call_open(pathname, flags, mode)
            };
            let error_no = if result < 0 { *libc::__errno() } else { 0 };
            if is_redirected && monitor::has_write_intent_flags(flags) {
                log::info!(
                    "write op=open from={} to={} ret={} errno={}",
                    path_text,
                    final_path.as_ref(),
                    result,
                    error_no
                );
            }

            monitor::record_open_result(hub, "open", flags, final_path.as_ref(), result, error_no);
            result
        },
    )
}

pub unsafe extern "C" fn hooked_openat(
    dirfd: c_int,
    pathname: *const c_char,
    flags: c_int,
    mode: mode_t,
) -> c_int {
    runtime::with_hook_guard(
        || call_openat(dirfd, pathname, flags, mode),
        |hub| {
            hub.increment_openat_calls();
            if runtime::should_resolve_caller_context(hub) {
                super::super::caller::update_caller_package_for_current_thread(hub);
            }

            handle_openat_like(
                hub,
                "openat",
                dirfd,
                pathname,
                flags,
                |call_dirfd, call_path| call_openat(call_dirfd, call_path, flags, mode),
            )
        },
    )
}

pub unsafe extern "C" fn hooked_openat2(
    dirfd: c_int,
    pathname: *const c_char,
    how: *const OpenHow,
    how_size: usize,
) -> c_int {
    runtime::with_hook_guard(
        || call_openat2(dirfd, pathname, how, how_size),
        |hub| {
            hub.increment_openat_calls();
            if runtime::should_resolve_caller_context(hub) {
                super::super::caller::update_caller_package_for_current_thread(hub);
            }

            if pathname.is_null() || how.is_null() {
                return call_openat2(dirfd, pathname, how, how_size);
            }

            let open_flags = (*how).flags as i32;
            handle_openat_like(
                hub,
                "openat2",
                dirfd,
                pathname,
                open_flags,
                |call_dirfd, call_path| call_openat2(call_dirfd, call_path, how, how_size),
            )
        },
    )
}

// openat/openat2 共用主体
fn handle_openat_like<F>(
    hub: &InterceptHub,
    op_name: &str,
    dirfd: c_int,
    pathname: *const c_char,
    flags: c_int,
    mut call_original: F,
) -> c_int
where
    F: FnMut(c_int, *const c_char) -> c_int,
{
    if pathname.is_null() {
        return call_original(dirfd, pathname);
    }

    let path_text = unsafe { c_str_to_string(pathname) };
    if path_text.is_empty() {
        return call_original(dirfd, pathname);
    }

    let is_relative = !path_text.starts_with('/');
    let mut path_for_decision: Cow<'_, str> = Cow::Borrowed(path_text.as_str());

    if is_relative {
        diagnostic::log_relative_path_bypass(hub, op_name, dirfd, &path_text, flags);
        let resolved = path_utils::resolve_path_for_dirfd(dirfd, &path_text);
        if resolved.is_empty() {
            return call_original(dirfd, pathname);
        }
        if !path_utils::is_relevant_storage_path(hub, &resolved) {
            return call_original(dirfd, pathname);
        }
        path_for_decision = Cow::Owned(resolved);
    }

    if !path_utils::is_relevant_storage_path(hub, path_for_decision.as_ref()) {
        diagnostic::record_fast_bypass(op_name, path_for_decision.as_ref());
        return call_original(dirfd, pathname);
    }

    diagnostic::log_diag_path_event(hub, op_name, "input", path_for_decision.as_ref(), flags);

    let is_system_writer = policy::is_system_writer_package(&hub.get_package_name());
    let mut is_redirected = false;
    let mut final_path: Cow<'_, str> = Cow::Borrowed(path_for_decision.as_ref());
    let mut should_call_with_absolute = false;

    // 系统代写进程仅对写入操作重定向，读取探测保持原路径避免触发 MediaProvider 路径校验
    let should_redirect =
        !hub.is_monitor_only() && (!is_system_writer || monitor::has_write_intent_flags(flags));
    if should_redirect {
        let redirect_result = process_redirect_path(hub, path_for_decision.as_ref());
        diagnostic::log_diag_redirect_decision(
            hub,
            op_name,
            path_for_decision.as_ref(),
            &redirect_result,
        );
        if redirect_result.is_redirect() {
            let new_path = redirect_result.new_path;
            record_redirect_hit(hub, op_name, path_for_decision.as_ref(), &new_path);
            final_path = Cow::Owned(new_path);
            is_redirected = true;
            should_call_with_absolute = is_relative;
        }
    }

    let call_dirfd = if should_call_with_absolute {
        AT_FDCWD
    } else {
        dirfd
    };
    let call_path = if is_relative && !should_call_with_absolute {
        pathname
    } else {
        match CString::new(final_path.as_ref()) {
            Ok(c_path) => c_path.into_raw(),
            Err(_) => pathname,
        }
    };

    runtime::ensure_redirect_parent_directory(
        op_name,
        path_for_decision.as_ref(),
        final_path.as_ref(),
        flags,
    );
    let result = call_original(call_dirfd, call_path);
    let error_no = if result < 0 {
        unsafe { *libc::__errno() }
    } else {
        0
    };
    let result = if is_redirected && is_system_writer {
        maybe_retry_system_writer_backend_open(
            op_name,
            path_for_decision.as_ref(),
            final_path.as_ref(),
            flags,
            result,
            error_no,
            |backend| call_original(AT_FDCWD, backend.as_ptr()),
        )
    } else {
        result
    };
    let error_no = if result < 0 {
        unsafe { *libc::__errno() }
    } else {
        0
    };

    if is_redirected && monitor::has_write_intent_flags(flags) {
        log::info!(
            "write op={} from={} to={} ret={} errno={}",
            op_name,
            path_for_decision.as_ref(),
            final_path.as_ref(),
            result,
            error_no
        );
    }

    let record_path = if is_relative {
        path_for_decision.as_ref()
    } else {
        final_path.as_ref()
    };
    monitor::record_open_result(hub, op_name, flags, record_path, result, error_no);

    if !call_path.is_null() && call_path != pathname {
        unsafe {
            let _ = CString::from_raw(call_path as *mut c_char);
        }
    }

    result
}

unsafe fn call_creat(pathname: *const c_char, mode: mode_t) -> c_int {
    let self_ptr = hooked_creat as *mut c_void;
    runtime::call_prev(
        self_ptr,
        || {
            libc::open(
                pathname,
                libc::O_CREAT | libc::O_WRONLY | libc::O_TRUNC,
                mode,
            )
        },
        |prev| {
            let f: unsafe extern "C" fn(*const c_char, mode_t) -> c_int = std::mem::transmute(prev);
            f(pathname, mode)
        },
    )
}

pub unsafe extern "C" fn hooked_creat(pathname: *const c_char, mode: mode_t) -> c_int {
    let creat_flags = libc::O_CREAT | libc::O_WRONLY | libc::O_TRUNC;
    runtime::with_hook_guard(
        || call_creat(pathname, mode),
        |hub| {
            hub.increment_open_calls();
            if runtime::should_resolve_caller_context(hub) {
                super::super::caller::update_caller_package_for_current_thread(hub);
            }

            if pathname.is_null() {
                return call_creat(pathname, mode);
            }

            let path_text = c_str_to_string(pathname);
            if path_text.is_empty() {
                return call_creat(pathname, mode);
            }

            if !path_utils::is_relevant_storage_path(hub, &path_text) {
                return call_creat(pathname, mode);
            }

            diagnostic::log_diag_path_event(hub, "creat", "input", &path_text, creat_flags);

            let mut final_path: Cow<'_, str> = Cow::Borrowed(path_text.as_str());
            if !hub.is_monitor_only() {
                let redirect_result = process_redirect_path(hub, &path_text);
                if redirect_result.is_redirect() {
                    final_path = Cow::Owned(redirect_result.new_path);
                }
            }

            let result = if let Ok(c_path) = CString::new(final_path.as_ref()) {
                call_creat(c_path.as_ptr(), mode)
            } else {
                call_creat(pathname, mode)
            };
            let error_no = if result < 0 { *libc::__errno() } else { 0 };
            monitor::record_open_result(
                hub,
                "creat",
                creat_flags,
                final_path.as_ref(),
                result,
                error_no,
            );
            result
        },
    )
}
