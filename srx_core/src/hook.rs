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

pub use jni_query::install_cursor_window_native_hook;
pub(crate) use jni_query::rewrite_cursor_storage_path_for_caller;
pub use stats::InterceptHub;

pub fn install_fuse_fixer_hook() {
    fuse_fixer::install();
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
