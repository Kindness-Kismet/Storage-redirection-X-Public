use super::super::{context, runtime};
use libc::c_void;

unsafe fn call_clear_calling_identity(this: *mut c_void) -> i64 {
    let self_ptr = hooked_clear_calling_identity as *mut c_void;
    runtime::call_prev_lazy(
        self_ptr,
        || 0i64,
        |prev| {
            let f: unsafe extern "C" fn(*mut c_void) -> i64 = std::mem::transmute(prev);
            f(this)
        },
    )
}

// 清除身份前抢先快照 UID，后续 hook 仍能溯源真实调用方
pub unsafe extern "C" fn hooked_clear_calling_identity(this: *mut c_void) -> i64 {
    super::super::caller::capture_binder_caller_uid_if_available();
    context::set_binder_identity_cleared(true);
    let saved_uid = context::get_binder_saved_caller_uid();
    log::debug!("binder identity cleared saved_uid={}", saved_uid);
    call_clear_calling_identity(this)
}

unsafe fn call_restore_calling_identity(this: *mut c_void, token: i64) {
    let self_ptr = hooked_restore_calling_identity as *mut c_void;
    runtime::call_prev_lazy(
        self_ptr,
        || {},
        |prev| {
            let f: unsafe extern "C" fn(*mut c_void, i64) = std::mem::transmute(prev);
            f(this, token)
        },
    );
}

pub unsafe extern "C" fn hooked_restore_calling_identity(this: *mut c_void, token: i64) {
    let saved_uid = context::get_binder_saved_caller_uid();
    let age_ms = context::get_binder_saved_caller_uid_age_ms();
    call_restore_calling_identity(this, token);
    context::clear_binder_saved_caller_uid();
    context::set_binder_identity_cleared(false);
    log::debug!(
        "binder identity restored saved_uid={} age_ms={}",
        saved_uid,
        age_ms
    );
}
