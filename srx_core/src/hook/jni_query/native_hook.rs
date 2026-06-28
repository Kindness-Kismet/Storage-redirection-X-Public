use crate::zygisk::abi;
use jni_sys::{JNIEnv, JNINativeMethod};
use libc::{c_char, c_void};
use std::ffi::{CStr, CString};
use std::sync::atomic::{AtomicPtr, Ordering};

// backup slot 未就绪哨兵，避免 register 与 store 之间 hook 被调用时读到 null
pub(super) fn backup_slot_loading() -> *mut c_void {
    std::ptr::dangling_mut::<u8>() as *mut c_void
}

// JNI native 注册条目：required=true 表示关键方法，失败时整体回滚
pub(super) struct NativeHookEntry {
    pub(super) name: &'static str,
    pub(super) sig: &'static str,
    pub(super) hook_fn: *mut c_void,
    pub(super) backup_slot: &'static AtomicPtr<std::ffi::c_void>,
    pub(super) required: bool,
}

// 逐个注册 JNI native；关键方法失败时反注册已成功的，避免半安装状态下 native 指针指向空 backup 的 hook
pub(super) unsafe fn install_native_methods(
    api: &abi::Api,
    env: *mut JNIEnv,
    class_name: &CStr,
    entries: &[NativeHookEntry],
    package_name: &str,
) -> bool {
    let mut installed: Vec<(
        CString,
        CString,
        *mut c_void,
        &'static AtomicPtr<std::ffi::c_void>,
    )> = Vec::new();

    for entry in entries {
        let Ok(name_c) = CString::new(entry.name) else {
            log::warn!(
                "hook encode name failed method={} pkg={}",
                entry.name,
                package_name
            );
            if entry.required {
                unsafe { rollback_installed_natives(api, env, class_name, &installed) };
                return false;
            }
            continue;
        };
        let Ok(sig_c) = CString::new(entry.sig) else {
            log::warn!(
                "hook encode sig failed method={} pkg={}",
                entry.name,
                package_name
            );
            if entry.required {
                unsafe { rollback_installed_natives(api, env, class_name, &installed) };
                return false;
            }
            continue;
        };

        match unsafe {
            register_single_native(
                api,
                env,
                class_name.as_ptr(),
                name_c.as_ptr(),
                sig_c.as_ptr(),
                entry.hook_fn,
                entry.backup_slot,
            )
        } {
            Some(backup) => {
                installed.push((name_c, sig_c, backup, entry.backup_slot));
            }
            None => {
                log::warn!(
                    "hook install failed method={} pkg={}",
                    entry.name,
                    package_name
                );
                if entry.required {
                    unsafe { rollback_installed_natives(api, env, class_name, &installed) };
                    return false;
                }
            }
        }
    }
    true
}

// 逆序反注册，先恢复 Java 层指针再清 backup，避免竞态下 hook 函数访问空 backup
unsafe fn rollback_installed_natives(
    api: &abi::Api,
    env: *mut JNIEnv,
    class_name: &CStr,
    installed: &[(
        CString,
        CString,
        *mut c_void,
        &'static AtomicPtr<std::ffi::c_void>,
    )],
) {
    for (name, sig, original, slot) in installed.iter().rev() {
        unsafe {
            restore_single_native(
                api,
                env,
                class_name.as_ptr(),
                name.as_ptr(),
                sig.as_ptr(),
                *original,
            );
        }
        slot.store(std::ptr::null_mut(), Ordering::Release);
    }
}

unsafe fn register_single_native(
    api: &abi::Api,
    env: *mut JNIEnv,
    class_name: *const c_char,
    name: *const c_char,
    sig: *const c_char,
    hook_fn: *mut c_void,
    backup_slot: &AtomicPtr<std::ffi::c_void>,
) -> Option<*mut c_void> {
    // 先占位哨兵，避免 hook_jni_native_methods 替换 Java 层后立刻被调用时 backup 仍为 null
    let loading = backup_slot_loading();
    backup_slot.store(loading, Ordering::Release);

    let mut method = JNINativeMethod {
        name: name as *mut c_char,
        signature: sig as *mut c_char,
        fnPtr: hook_fn,
    };
    api.hook_jni_native_methods(env, class_name, &mut method, 1);
    let backup = method.fnPtr;
    if backup.is_null() || backup == hook_fn || backup == loading {
        backup_slot.store(std::ptr::null_mut(), Ordering::Release);
        return None;
    }
    backup_slot.store(backup, Ordering::Release);
    Some(backup)
}

unsafe fn restore_single_native(
    api: &abi::Api,
    env: *mut JNIEnv,
    class_name: *const c_char,
    name: *const c_char,
    sig: *const c_char,
    original_fn: *mut c_void,
) {
    let mut method = JNINativeMethod {
        name: name as *mut c_char,
        signature: sig as *mut c_char,
        fnPtr: original_fn,
    };
    api.hook_jni_native_methods(env, class_name, &mut method, 1);
}

// 在 hook 函数中等待 backup 从哨兵状态转为真实指针；null 代表未安装或已卸载
pub(super) fn load_original(slot: &AtomicPtr<std::ffi::c_void>) -> *mut c_void {
    const MAX_SPIN_ROUNDS: usize = 4096;
    let loading = backup_slot_loading();
    let mut rounds = 0usize;
    loop {
        let current = slot.load(Ordering::Acquire);
        if current != loading {
            return current;
        }
        if rounds >= MAX_SPIN_ROUNDS {
            return std::ptr::null_mut();
        }
        rounds += 1;
        std::hint::spin_loop();
    }
}
