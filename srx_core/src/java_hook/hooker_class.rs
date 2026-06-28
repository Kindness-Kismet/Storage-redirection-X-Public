// Hooker 类全局引用 + LSPlant native trampoline 绑定

use super::lsplant;
use crate::hook::rewrite_cursor_storage_path_for_caller;
use crate::zygisk::jni::{
    call_object_method_a, get_method_id, get_object_class, new_global_ref, new_jstring_utf8,
    register_natives,
};
use jni_sys::{_jobject, JNIEnv, JNINativeMethod, jclass, jobject, jobjectArray, jstring, jvalue};
use std::sync::atomic::{AtomicPtr, Ordering};

static HOOKER_CLASS_GLOBAL: AtomicPtr<_jobject> = AtomicPtr::new(std::ptr::null_mut());
const HIDDEN_ROW_SENTINEL: &str = "\u{1F}SRX_HIDDEN_ROW";

const DO_HOOK_NAME: &[u8] = b"doHook\0";
const DO_HOOK_SIG: &[u8] =
    b"(Ljava/lang/reflect/Member;Ljava/lang/reflect/Method;)Ljava/lang/reflect/Method;\0";
const DO_UNHOOK_NAME: &[u8] = b"doUnhook\0";
const DO_UNHOOK_SIG: &[u8] = b"(Ljava/lang/reflect/Member;)Z\0";
const CALLBACK_NAME: &[u8] = b"onMediaProviderQuery\0";
const CALLBACK_SIG: &[u8] = b"(Lorg/srx/hook/Hooker;[Ljava/lang/Object;)Ljava/lang/Object;\0";
const FILTER_PATH_NAME: &[u8] = b"filterPath\0";
const FILTER_PATH_SIG: &[u8] = b"(Ljava/lang/String;IZ)Ljava/lang/String;\0";

pub fn init(env: *mut JNIEnv, hooker_class: jclass) -> bool {
    if env.is_null() || hooker_class.is_null() {
        return false;
    }
    if !lsplant::init(env) {
        return false;
    }

    let methods = [
        JNINativeMethod {
            name: DO_HOOK_NAME.as_ptr() as *mut _,
            signature: DO_HOOK_SIG.as_ptr() as *mut _,
            fnPtr: do_hook as *mut _,
        },
        JNINativeMethod {
            name: DO_UNHOOK_NAME.as_ptr() as *mut _,
            signature: DO_UNHOOK_SIG.as_ptr() as *mut _,
            fnPtr: do_unhook as *mut _,
        },
        JNINativeMethod {
            name: CALLBACK_NAME.as_ptr() as *mut _,
            signature: CALLBACK_SIG.as_ptr() as *mut _,
            fnPtr: on_media_provider_query as *mut _,
        },
        JNINativeMethod {
            name: FILTER_PATH_NAME.as_ptr() as *mut _,
            signature: FILTER_PATH_SIG.as_ptr() as *mut _,
            fnPtr: filter_path as *mut _,
        },
    ];
    if !register_natives(env, hooker_class, &methods) {
        log::warn!("java hook register natives failed");
        return false;
    }

    let gref = new_global_ref(env, hooker_class);
    if gref.is_null() {
        return false;
    }
    HOOKER_CLASS_GLOBAL.store(gref, Ordering::Release);
    true
}

pub fn hooker_class() -> jclass {
    HOOKER_CLASS_GLOBAL.load(Ordering::Acquire)
}

unsafe extern "C" fn do_hook(
    env: *mut JNIEnv,
    thiz: jobject,
    target: jobject,
    callback: jobject,
) -> jobject {
    lsplant::hook(env, target, thiz, callback)
}

unsafe extern "C" fn do_unhook(
    env: *mut JNIEnv,
    _thiz: jobject,
    target: jobject,
) -> jni_sys::jboolean {
    if lsplant::unhook(env, target) {
        jni_sys::JNI_TRUE
    } else {
        jni_sys::JNI_FALSE
    }
}

unsafe extern "C" fn on_media_provider_query(
    env: *mut JNIEnv,
    _class: jclass,
    hooker: jobject,
    args: jobjectArray,
) -> jobject {
    call_backup(env, hooker, args)
}

unsafe extern "C" fn filter_path(
    env: *mut JNIEnv,
    _class: jclass,
    path: jstring,
    caller_uid: jni_sys::jint,
    preserve_missing_target: jni_sys::jboolean,
) -> jstring {
    if env.is_null() || path.is_null() {
        return std::ptr::null_mut();
    }
    let original = crate::zygisk::jni::get_jstring_utf8(env, path);
    let Some(rewritten) = rewrite_cursor_storage_path_for_caller(
        &original,
        caller_uid,
        preserve_missing_target == jni_sys::JNI_TRUE,
    ) else {
        return path;
    };
    if rewritten.is_empty() {
        return new_jstring_utf8(env, HIDDEN_ROW_SENTINEL);
    }
    new_jstring_utf8(env, &rewritten)
}

fn call_backup(env: *mut JNIEnv, hooker: jobject, args: jobjectArray) -> jobject {
    if env.is_null() || hooker.is_null() || args.is_null() {
        return std::ptr::null_mut();
    }
    let hooker_class = get_object_class(env, hooker);
    if hooker_class.is_null() {
        return std::ptr::null_mut();
    }
    let backup_mid = get_method_id(
        env,
        hooker_class,
        "callBackup",
        "([Ljava/lang/Object;)Ljava/lang/Object;",
    );
    if backup_mid.is_null() {
        return std::ptr::null_mut();
    }
    call_object_method_a(env, hooker, backup_mid, &[jvalue { l: args }])
}
