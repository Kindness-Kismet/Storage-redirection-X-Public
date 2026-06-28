// CursorWindow.nativeClear Hook：窗口复用前清理 filtered rows 缓存

use super::cache::clear_filtered_window;
use super::native_hook::load_original;
use super::types::CursorWindowWindowFn;
use jni_sys::{JNIEnv, jlong, jobject};
use std::sync::atomic::AtomicPtr;

pub(super) static CURSOR_WINDOW_CLEAR_BACKUP: AtomicPtr<std::ffi::c_void> =
    AtomicPtr::new(std::ptr::null_mut());

pub(super) unsafe extern "C" fn hooked_cursor_window_native_clear(
    env: *mut JNIEnv,
    clazz_or_obj: jobject,
    window_ptr: jlong,
) {
    clear_filtered_window(window_ptr);
    let backup = load_original(&CURSOR_WINDOW_CLEAR_BACKUP);
    if backup.is_null() {
        return;
    }
    let original_fn: CursorWindowWindowFn = unsafe { std::mem::transmute(backup) };
    unsafe { original_fn(env, clazz_or_obj, window_ptr) };
}
