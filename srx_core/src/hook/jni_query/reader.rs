use super::native_hook::{NativeHookEntry, install_native_methods, load_original};
use super::rewrite::rewrite_existing_cursor_storage_path;
use super::types::{
    CURSOR_WINDOW_CLASS, CURSOR_WINDOW_GET_STRING_NAME, CURSOR_WINDOW_GET_STRING_SIG,
    CursorWindowGetStringFn,
};
use crate::zygisk::abi;
use crate::zygisk::jni;
use jni_sys::{JNIEnv, jint, jlong, jobject, jstring};
use libc::c_void;
use std::ffi::CString;
use std::sync::atomic::{AtomicBool, AtomicPtr, Ordering};

static CURSOR_WINDOW_READER_HOOK_INSTALLED: AtomicBool = AtomicBool::new(false);
static CURSOR_WINDOW_GET_STRING_BACKUP: AtomicPtr<std::ffi::c_void> =
    AtomicPtr::new(std::ptr::null_mut());
// 安装 CursorWindow 的 nativeGetString Hook，用于改写可访问的重定向路径
pub fn install_cursor_window_native_hook(api: &abi::Api, env: *mut JNIEnv, package_name: &str) {
    if env.is_null() || CURSOR_WINDOW_READER_HOOK_INSTALLED.load(Ordering::Acquire) {
        return;
    }

    let Ok(class_name) = CString::new(CURSOR_WINDOW_CLASS) else {
        return;
    };

    let entries = [NativeHookEntry {
        name: CURSOR_WINDOW_GET_STRING_NAME,
        sig: CURSOR_WINDOW_GET_STRING_SIG,
        hook_fn: hooked_cursor_window_native_get_string as *mut c_void,
        backup_slot: &CURSOR_WINDOW_GET_STRING_BACKUP,
        required: true,
    }];

    if !unsafe { install_native_methods(api, env, &class_name, &entries, package_name) } {
        log::warn!("reader hook install failed pkg={}", package_name);
        return;
    }

    CURSOR_WINDOW_READER_HOOK_INSTALLED.store(true, Ordering::Release);
    log::info!("reader hook on pkg={}", package_name);
}

unsafe extern "C" fn hooked_cursor_window_native_get_string(
    env: *mut JNIEnv,
    clazz_or_obj: jobject,
    window_ptr: jlong,
    row: jint,
    column: jint,
) -> jstring {
    let backup = load_original(&CURSOR_WINDOW_GET_STRING_BACKUP);
    if backup.is_null() {
        return std::ptr::null_mut();
    }

    let original_fn: CursorWindowGetStringFn = unsafe { std::mem::transmute(backup) };
    let original = unsafe { original_fn(env, clazz_or_obj, window_ptr, row, column) };
    if original.is_null() {
        return original;
    }

    let original_text = jni::get_jstring_utf8(env, original);
    let Some(rewritten_text) = rewrite_existing_cursor_storage_path(&original_text) else {
        return original;
    };
    if rewritten_text == original_text {
        return original;
    }

    let replacement = jni::new_jstring_utf8(env, &rewritten_text);
    if replacement.is_null() {
        return original;
    }

    jni::delete_local_ref(env, original as jobject);
    replacement
}
