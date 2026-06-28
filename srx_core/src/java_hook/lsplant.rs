use crate::platform::elf_img::ElfImg;
use jni_sys::{JNIEnv, jobject};
use once_cell::sync::Lazy;
use std::ffi::{CStr, c_char, c_void};
use std::sync::Mutex;

unsafe extern "C" {
    fn srx_lsplant_init(env: *mut JNIEnv) -> bool;
    fn srx_lsplant_hook(
        env: *mut JNIEnv,
        target_method: jobject,
        hooker_object: jobject,
        callback_method: jobject,
    ) -> jobject;
    fn srx_lsplant_unhook(env: *mut JNIEnv, target_method: jobject) -> bool;
}

static ART_ELF: Lazy<Mutex<Option<ElfImg>>> = Lazy::new(|| Mutex::new(None));

pub fn init(env: *mut JNIEnv) -> bool {
    if env.is_null() {
        return false;
    }
    unsafe { srx_lsplant_init(env) }
}

pub fn hook(
    env: *mut JNIEnv,
    target_method: jobject,
    hooker_object: jobject,
    callback_method: jobject,
) -> jobject {
    if env.is_null()
        || target_method.is_null()
        || hooker_object.is_null()
        || callback_method.is_null()
    {
        return std::ptr::null_mut();
    }
    unsafe { srx_lsplant_hook(env, target_method, hooker_object, callback_method) }
}

pub fn unhook(env: *mut JNIEnv, target_method: jobject) -> bool {
    if env.is_null() || target_method.is_null() {
        return false;
    }
    unsafe { srx_lsplant_unhook(env, target_method) }
}

#[unsafe(no_mangle)]
pub extern "C" fn srx_art_symbol_resolve(name: *const c_char) -> *mut c_void {
    let Some(name) = (unsafe { cstr_from_ptr(name) }) else {
        return std::ptr::null_mut();
    };
    resolve_art_symbol(name)
}

#[unsafe(no_mangle)]
pub extern "C" fn srx_art_symbol_resolve_prefix(prefix: *const c_char) -> *mut c_void {
    let Some(prefix) = (unsafe { cstr_from_ptr(prefix) }) else {
        return std::ptr::null_mut();
    };
    let Ok(prefix) = prefix.to_str() else {
        return std::ptr::null_mut();
    };
    with_art_elf(|elf| elf.find_prefix(prefix))
}

fn resolve_art_symbol(name: &CStr) -> *mut c_void {
    let handle = unsafe { srx_inline_hook::sh_dlopen(c"libart.so") };
    if !handle.is_null() {
        let found = unsafe { srx_inline_hook::sh_dlsym(handle, name) };
        unsafe { srx_inline_hook::sh_dlclose(handle) };
        if !found.is_null() {
            return found;
        }
    }

    let Ok(name) = name.to_str() else {
        return std::ptr::null_mut();
    };
    with_art_elf(|elf| elf.find(name))
}

fn with_art_elf<F>(f: F) -> *mut c_void
where
    F: FnOnce(&ElfImg) -> *mut c_void,
{
    let Ok(mut guard) = ART_ELF.lock() else {
        return std::ptr::null_mut();
    };
    if guard.is_none() {
        let candidates = [
            "/lib64/libart.so",
            "/lib/libart.so",
            "/apex/com.android.art/lib64/libart.so",
            "/apex/com.android.art/lib/libart.so",
        ];
        for path in candidates {
            if let Some(img) = ElfImg::load(path) {
                *guard = Some(img);
                break;
            }
        }
        if guard.is_none() {
            log::warn!("art elf load failed");
        }
    }
    guard.as_ref().map_or(std::ptr::null_mut(), f)
}

unsafe fn cstr_from_ptr<'a>(ptr: *const c_char) -> Option<&'a CStr> {
    if ptr.is_null() {
        return None;
    }
    Some(unsafe { CStr::from_ptr(ptr) })
}
