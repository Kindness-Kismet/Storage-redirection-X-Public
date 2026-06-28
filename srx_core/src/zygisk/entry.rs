// Zygisk 入口与 companion 入口实现，把外部回调接到 RuntimeFlow 上
use super::abi::{
    Api, ApiTable, AppSpecializeArgs, ModuleAbi, ServerSpecializeArgs, ZYGISK_API_VERSION,
};
use crate::lifecycle::{RuntimeFlow, run_companion_pipeline};
use crate::logging::Logger;
use crate::platform;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Mutex, OnceLock};

// 全局 RuntimeFlow 实例，所有 specialize 回调从这里取共享状态
static FLOW: OnceLock<Mutex<RuntimeFlow>> = OnceLock::new();
// 首次 on_load 时填好后保持不变的 ABI 结构
static MODULE_ABI: OnceLock<ModuleAbi> = OnceLock::new();
// 模块 entry 被调用的累计次数，仅用于日志采样
static ENTRY_CALL_COUNT: AtomicUsize = AtomicUsize::new(0);
// 入口日志采样间隔，避免每次加载都刷一行
const ENTRY_LOG_STEP: usize = 64;

// 首次调用以及每隔 ENTRY_LOG_STEP 次各打一条，其他静默
#[inline]
fn should_log_entry(call_index: usize) -> bool {
    call_index == 1 || call_index.is_multiple_of(ENTRY_LOG_STEP)
}

// 防止 LTO 把 no_mangle 导出符号优化掉
#[used]
static ZYGISK_MODULE_ENTRY_KEEP: unsafe extern "C" fn(*mut ApiTable, *mut jni_sys::JNIEnv) =
    zygisk_module_entry;
#[used]
static ZYGISK_COMPANION_ENTRY_KEEP: unsafe extern "C" fn(libc::c_int) = zygisk_companion_entry;

// Zygisk 加载模块时调用；拿到 API 表与 env 后完成注册并触发首次 on_load
#[unsafe(no_mangle)]
pub unsafe extern "C" fn zygisk_module_entry(table: *mut ApiTable, env: *mut jni_sys::JNIEnv) {
    module_entry_impl(table, env);
}

unsafe fn module_entry_impl(table: *mut ApiTable, env: *mut jni_sys::JNIEnv) {
    let call_index = ENTRY_CALL_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
    Logger::init(None);

    if should_log_entry(call_index) {
        log::debug!(
            "entry call n={} table={:p} env={:p}",
            call_index,
            table,
            env
        );
    }
    if table.is_null() {
        log::info!("entry exit reason=null_table");
        return;
    }

    let api_level = platform::android_api_level();
    let is_supported_api = api_level >= platform::MIN_SUPPORTED_API_LEVEL;
    if should_log_entry(call_index) {
        log::info!(
            "entry android_api api={} min={} supported={}",
            api_level,
            platform::MIN_SUPPORTED_API_LEVEL,
            is_supported_api
        );
    }
    if !is_supported_api {
        if should_log_entry(call_index) {
            log::info!(
                "entry exit reason=unsupported_android api={} min={}",
                api_level,
                platform::MIN_SUPPORTED_API_LEVEL
            );
        }
        return;
    }

    let flow_lock = FLOW.get_or_init(|| Mutex::new(RuntimeFlow::new()));
    let flow_ptr = flow_lock as *const _ as *mut std::ffi::c_void;
    let api = Api::new(table);

    let abi = MODULE_ABI.get_or_init(|| ModuleAbi {
        api_version: ZYGISK_API_VERSION,
        impl_ptr: flow_ptr,
        pre_app_specialize: Some(pre_app_specialize),
        post_app_specialize: Some(post_app_specialize),
        pre_server_specialize: Some(pre_server_specialize),
        post_server_specialize: Some(post_server_specialize),
    });

    let register = (*table).register_module;
    if let Some(func) = register {
        let registered = func(table, abi as *const _ as *mut ModuleAbi);
        if should_log_entry(call_index) || !registered {
            if registered {
                log::debug!("entry register_module result=ok");
            } else {
                log::warn!("entry register_module result=failed");
            }
        }
        if !registered {
            return;
        }
    } else {
        log::info!("entry exit reason=null_register_module");
        return;
    }

    if let Ok(mut flow) = flow_lock.lock() {
        flow.on_load(api, env);
    } else {
        log::info!("entry exit reason=flow_lock_failed");
    }
}

// companion 子进程启动入口，client 是与主进程通信的 socket fd
#[unsafe(no_mangle)]
pub unsafe extern "C" fn zygisk_companion_entry(client: libc::c_int) {
    companion_entry_impl(client);
}

unsafe fn companion_entry_impl(client: libc::c_int) {
    Logger::init(None);
    let api_level = platform::android_api_level();
    if api_level < platform::MIN_SUPPORTED_API_LEVEL {
        log::info!(
            "companion exit reason=unsupported_android api={} min={}",
            api_level,
            platform::MIN_SUPPORTED_API_LEVEL
        );
        return;
    }
    log::info!("companion entry client={}", client);
    run_companion_pipeline(client);
}

// 应用 fork 后 specialize 前：读配置决定是否挂载、是否安装 hook
unsafe extern "C" fn pre_app_specialize(
    impl_ptr: *mut std::ffi::c_void,
    args: *mut AppSpecializeArgs,
) {
    if impl_ptr.is_null() {
        return;
    }
    let flow_lock = &*(impl_ptr as *const Mutex<RuntimeFlow>);
    if let Ok(mut flow) = flow_lock.lock() {
        flow.pre_app_specialize(args);
    }
}

// specialize 后阶段：等挂载就绪并完成 hook 安装
unsafe extern "C" fn post_app_specialize(
    impl_ptr: *mut std::ffi::c_void,
    args: *const AppSpecializeArgs,
) {
    if impl_ptr.is_null() {
        return;
    }
    let flow_lock = &*(impl_ptr as *const Mutex<RuntimeFlow>);
    if let Ok(mut flow) = flow_lock.lock() {
        flow.post_app_specialize(args);
    }
}

// system_server specialize 前：请求 Zygisk dlclose 自身，避免常驻
unsafe extern "C" fn pre_server_specialize(
    impl_ptr: *mut std::ffi::c_void,
    _args: *mut ServerSpecializeArgs,
) {
    if impl_ptr.is_null() {
        return;
    }
    let flow_lock = &*(impl_ptr as *const Mutex<RuntimeFlow>);
    if let Ok(mut flow) = flow_lock.lock() {
        flow.pre_server_specialize();
    }
}

// system_server specialize 后无额外动作，仅为 ABI 保留占位
unsafe extern "C" fn post_server_specialize(
    _impl_ptr: *mut std::ffi::c_void,
    _args: *const ServerSpecializeArgs,
) {
}
