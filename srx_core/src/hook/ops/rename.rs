use super::super::{caller, diagnostic, path as path_utils, runtime, util::c_str_to_string};
use crate::redirect::{RedirectAction, RedirectDecision, process_redirect_path};
use libc::{O_CREAT, O_WRONLY, c_char, c_int, c_void};
use std::ffi::CString;

pub unsafe extern "C" fn hooked_rename(oldpath: *const c_char, newpath: *const c_char) -> c_int {
    let self_ptr = hooked_rename as *mut c_void;
    let call_rename = |old: *const c_char, new: *const c_char| -> c_int {
        runtime::call_prev(
            self_ptr,
            || libc::rename(old, new),
            |prev| {
                let f: unsafe extern "C" fn(*const c_char, *const c_char) -> c_int =
                    unsafe { std::mem::transmute(prev) };
                unsafe { f(old, new) }
            },
        )
    };

    runtime::with_hook_guard(
        || call_rename(oldpath, newpath),
        |hub| {
            hub.increment_rename_calls();
            if runtime::should_resolve_caller_context(hub) {
                caller::update_caller_package_for_current_thread(hub);
            }

            if oldpath.is_null() || newpath.is_null() {
                return call_rename(oldpath, newpath);
            }

            let old_text = c_str_to_string(oldpath);
            let new_text = c_str_to_string(newpath);
            let is_old_storage = path_utils::is_relevant_storage_path(hub, &old_text);
            let is_new_storage = path_utils::is_relevant_storage_path(hub, &new_text);
            if !is_old_storage && !is_new_storage {
                diagnostic::record_fast_bypass("rename", &old_text);
                return call_rename(oldpath, newpath);
            }

            diagnostic::log_diag_path_event(hub, "rename", "input-old", &old_text, -1);
            diagnostic::log_diag_path_event(hub, "rename", "input-new", &new_text, -1);

            let is_monitor_only = hub.is_monitor_only();
            let mut old_redirect = RedirectDecision {
                action: RedirectAction::Allow,
                new_path: String::new(),
            };
            let mut new_redirect = RedirectDecision {
                action: RedirectAction::Allow,
                new_path: String::new(),
            };
            if !is_monitor_only {
                if is_old_storage {
                    old_redirect = process_redirect_path(hub, &old_text);
                }
                if is_new_storage {
                    new_redirect = process_redirect_path(hub, &new_text);
                }
            }

            let final_old = if old_redirect.is_redirect() {
                old_redirect.new_path.as_str()
            } else {
                old_text.as_str()
            };
            let final_new = if new_redirect.is_redirect() {
                new_redirect.new_path.as_str()
            } else {
                new_text.as_str()
            };

            diagnostic::log_diag_rename_decision(hub, &old_text, &new_text, final_old, final_new);
            if !is_monitor_only && (final_old != old_text || final_new != new_text) {
                log::trace!(
                    "rename: {} -> {} (redirect {} -> {})",
                    old_text,
                    new_text,
                    final_old,
                    final_new
                );
                hub.increment_total_redirected();
                hub.increment_global_redirect_count();
            }

            runtime::ensure_redirect_parent_directory(
                "rename",
                &new_text,
                final_new,
                O_WRONLY | O_CREAT,
            );
            match (CString::new(final_old), CString::new(final_new)) {
                (Ok(c_old), Ok(c_new)) => call_rename(c_old.as_ptr(), c_new.as_ptr()),
                _ => call_rename(oldpath, newpath),
            }
        },
    )
}

pub unsafe extern "C" fn hooked_renameat2(
    olddirfd: c_int,
    oldpath: *const c_char,
    newdirfd: c_int,
    newpath: *const c_char,
    flags: u32,
) -> c_int {
    let self_ptr = hooked_renameat2 as *mut c_void;
    let call_original = |call_old: *const c_char, call_new: *const c_char| -> c_int {
        runtime::call_prev(
            self_ptr,
            || unsafe {
                libc::syscall(
                    libc::SYS_renameat2,
                    olddirfd,
                    call_old,
                    newdirfd,
                    call_new,
                    flags,
                ) as c_int
            },
            |prev| {
                let f: unsafe extern "C" fn(
                    c_int,
                    *const c_char,
                    c_int,
                    *const c_char,
                    u32,
                ) -> c_int = unsafe { std::mem::transmute(prev) };
                unsafe { f(olddirfd, call_old, newdirfd, call_new, flags) }
            },
        )
    };

    runtime::with_hook_guard(
        || call_original(oldpath, newpath),
        |hub| {
            hub.increment_rename_calls();
            if runtime::should_resolve_caller_context(hub) {
                caller::update_caller_package_for_current_thread(hub);
            }

            if oldpath.is_null() || newpath.is_null() {
                return call_original(oldpath, newpath);
            }

            let old_text = c_str_to_string(oldpath);
            let new_text = c_str_to_string(newpath);

            if !old_text.starts_with('/') {
                diagnostic::log_relative_path_bypass(
                    hub,
                    "renameat2-old",
                    olddirfd,
                    &old_text,
                    flags as i32,
                );
            }
            if !new_text.starts_with('/') {
                diagnostic::log_relative_path_bypass(
                    hub,
                    "renameat2-new",
                    newdirfd,
                    &new_text,
                    flags as i32,
                );
            }
            if !old_text.starts_with('/') || !new_text.starts_with('/') {
                return call_original(oldpath, newpath);
            }

            let is_old_storage = path_utils::is_relevant_storage_path(hub, &old_text);
            let is_new_storage = path_utils::is_relevant_storage_path(hub, &new_text);
            if !is_old_storage && !is_new_storage {
                diagnostic::record_fast_bypass("renameat2", &old_text);
                return call_original(oldpath, newpath);
            }

            diagnostic::log_diag_path_event(hub, "renameat2", "input-old", &old_text, flags as i32);
            diagnostic::log_diag_path_event(hub, "renameat2", "input-new", &new_text, flags as i32);

            let is_monitor_only = hub.is_monitor_only();
            let mut old_redirect = RedirectDecision {
                action: RedirectAction::Allow,
                new_path: String::new(),
            };
            let mut new_redirect = RedirectDecision {
                action: RedirectAction::Allow,
                new_path: String::new(),
            };
            if !is_monitor_only {
                if is_old_storage {
                    old_redirect = process_redirect_path(hub, &old_text);
                }
                if is_new_storage {
                    new_redirect = process_redirect_path(hub, &new_text);
                }
            }

            let final_old = if old_redirect.is_redirect() {
                old_redirect.new_path.as_str()
            } else {
                old_text.as_str()
            };
            let final_new = if new_redirect.is_redirect() {
                new_redirect.new_path.as_str()
            } else {
                new_text.as_str()
            };

            diagnostic::log_diag_rename_decision(hub, &old_text, &new_text, final_old, final_new);
            if !is_monitor_only && (final_old != old_text || final_new != new_text) {
                hub.increment_total_redirected();
                hub.increment_global_redirect_count();
            }

            runtime::ensure_redirect_parent_directory(
                "renameat2",
                &new_text,
                final_new,
                O_WRONLY | O_CREAT,
            );
            match (CString::new(final_old), CString::new(final_new)) {
                (Ok(c_old), Ok(c_new)) => call_original(c_old.as_ptr(), c_new.as_ptr()),
                _ => call_original(oldpath, newpath),
            }
        },
    )
}
