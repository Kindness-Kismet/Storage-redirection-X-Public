mod caller;
mod context;
mod diagnostic;
mod entries;
mod fuse_fixer;
mod jni_query;
mod media_fuse;
mod monitor;
mod ops;
mod path;
mod reload;
mod runtime;
pub mod stats;
mod util;

use std::ffi::{CStr, c_char};

pub(crate) use jni_query::{
    is_readonly_path_for_caller, resolve_redirect_target_for_caller,
    rewrite_cursor_storage_path_for_caller,
};
pub use stats::InterceptHub;

pub fn install_fuse_fixer_hook() {
    fuse_fixer::install();
}

pub(crate) fn capture_fuse_caller(uid: i32) {
    if context::get_fuse_caller_uid() != uid {
        context::set_fuse_caller_pid(-1);
    }
    context::set_fuse_caller_uid(uid);
}

pub(crate) fn refresh_runtime_config_throttled() {
    if context::ReentryGuard::is_reentrant() {
        let did_reload = reload::poll_or_check_throttled().did_reload();
        if did_reload {
            log::debug!("runtime config refreshed from native reentrant path");
        }
        return;
    }

    let _guard = context::ReentryGuard::enter();
    let _ = reload::poll_or_check_throttled();
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn srx_refresh_runtime_config_from_native() {
    refresh_runtime_config_throttled();
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn srx_should_force_fuse_userspace_private_owner_sqlite(
    path: *const c_char,
) -> bool {
    if path.is_null() {
        return false;
    }
    let Ok(path) = unsafe { CStr::from_ptr(path) }.to_str() else {
        return false;
    };
    media_fuse::should_force_userspace_for_private_owner_sqlite_path(path)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn srx_prepare_fuse_private_owner_sqlite_sidecar(
    path: *const c_char,
) -> bool {
    if path.is_null() {
        return false;
    }
    let Ok(path) = unsafe { CStr::from_ptr(path) }.to_str() else {
        return false;
    };
    media_fuse::prepare_private_owner_sqlite_sidecar(path)
}

pub fn refresh_provider_libraries() {
    if !InterceptHub::instance().is_hooks_installed() {
        return;
    }
    let _guard = context::ReentryGuard::enter();
    let (status, errors) = srx_hook::refresh();
    if !status.is_ok() || !errors.is_empty() {
        log::warn!(
            "provider library refresh err={:?} modules={}",
            status,
            errors.len()
        );
    }
}
