use super::super::diagnostic;
use super::super::monitor;
use super::super::path as path_utils;
use super::super::runtime;
use super::super::stats::InterceptHub;
use super::super::util::c_str_to_string;
use crate::platform::fs;
use crate::redirect::{process_redirect_path, record_redirect_hit};
use libc::{AT_FDCWD, c_char, c_int, c_void, mode_t};
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

    let mut redirected_path = String::new();
    let mut result = if redirect_result.is_redirect() {
        redirected_path = redirect_result.new_path.clone();
        record_redirect_hit(hub, op_name, &path_text, &redirected_path);
        runtime::ensure_redirect_parent_dirs(&redirected_path, mode);
        if let Ok(c_path) = CString::new(redirected_path.as_str()) {
            call_original(c_path.as_ptr())
        } else {
            call_original(pathname)
        }
    } else {
        call_original(pathname)
    };
    let mut error_no = if result < 0 {
        unsafe { *libc::__errno() }
    } else {
        0
    };
    normalize_existing_redirect_dir_result(&redirected_path, &mut result, &mut error_no);
    if !redirected_path.is_empty() {
        log::info!(
            "write op={} from={} to={} ret={} errno={}",
            op_name,
            path_text,
            redirected_path,
            result,
            error_no
        );
    }
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

    let mut redirected_path = String::new();
    let mut result = if redirect_result.is_redirect() {
        redirected_path = redirect_result.new_path.clone();
        record_redirect_hit(hub, op_name, &path_text, &redirected_path);
        runtime::ensure_redirect_parent_dirs(&redirected_path, mode);
        if let Ok(c_path) = CString::new(redirected_path.as_str()) {
            call_original(c_path.as_ptr())
        } else {
            call_original(pathname)
        }
    } else {
        call_original(pathname)
    };
    let mut error_no = if result < 0 {
        unsafe { *libc::__errno() }
    } else {
        0
    };
    normalize_existing_redirect_dir_result(&redirected_path, &mut result, &mut error_no);
    if !redirected_path.is_empty() {
        log::info!(
            "write op={} from={} to={} ret={} errno={}",
            op_name,
            path_text,
            redirected_path,
            result,
            error_no
        );
    }
    monitor::record_mkdir_result(hub, op_name, &path_text, result, error_no);
    result
}

fn normalize_existing_redirect_dir_result(
    redirected_path: &str,
    result: &mut c_int,
    error_no: &mut i32,
) {
    if redirected_path.is_empty() || *result >= 0 || *error_no != libc::EEXIST {
        return;
    }
    if !fs::is_directory(redirected_path) {
        return;
    }
    *result = 0;
    *error_no = 0;
    unsafe {
        *libc::__errno() = 0;
    }
}
