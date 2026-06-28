// Java hook 模块入口：加载内嵌 DEX、拿 Hooker class 引用、绑定 native callback
// 由 specialize_pre 在 MediaProvider 目标进程内触发；失败不影响其他路径

#![allow(dead_code)]

mod dex_loader;
mod hooker_class;
mod lsplant;

use crate::zygisk::jni::{call_static_boolean_method_a, delete_local_ref, get_static_method_id};
use jni_sys::JNIEnv;
use std::sync::atomic::{AtomicBool, Ordering};

// build.rs 调 d8 产出；工具链缺失时为空，运行时探测 dex_loader 自动返回 null 失败
const HOOKER_DEX: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/Hooker.dex"));
static IS_INITIALIZED: AtomicBool = AtomicBool::new(false);

pub fn is_available() -> bool {
    !HOOKER_DEX.is_empty()
}

// 在目标进程内初始化一次；成功后 hooker_class() 返回全局引用
pub fn init_once(env: *mut JNIEnv) -> bool {
    if IS_INITIALIZED.load(Ordering::Acquire) {
        return true;
    }
    if !init(env) {
        return false;
    }
    IS_INITIALIZED.store(true, Ordering::Release);
    true
}

fn init(env: *mut JNIEnv) -> bool {
    if HOOKER_DEX.is_empty() || env.is_null() {
        return false;
    }
    let clazz = dex_loader::load_dex_class(env, HOOKER_DEX, "org.srx.hook.Hooker");
    if clazz.is_null() {
        log::warn!("java hook load dex class failed");
        return false;
    }
    let class_init_ok = hooker_class::init(env, clazz);
    if !class_init_ok {
        delete_local_ref(env, clazz);
        return false;
    }
    let install_ok = install_media_provider_hook(env, clazz);
    if !install_ok {
        log::warn!("java hook install media provider hook failed");
    }
    delete_local_ref(env, clazz);
    install_ok
}

fn install_media_provider_hook(env: *mut JNIEnv, clazz: jni_sys::jclass) -> bool {
    let method = get_static_method_id(env, clazz, "installMediaProviderHook", "()Z");
    if method.is_null() {
        return false;
    }
    call_static_boolean_method_a(env, clazz, method, &[])
}

#[allow(unused_imports)]
pub use hooker_class::hooker_class;
