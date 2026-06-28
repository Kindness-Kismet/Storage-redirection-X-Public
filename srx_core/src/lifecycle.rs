mod boot;
mod companion;
mod companion_mount;
mod companion_request;
mod specialize_post;
mod specialize_pre;

use crate::logging::Logger;
use crate::zygisk::{abi, jni};

pub use companion::run_companion_pipeline;

pub struct RuntimeFlow {
    api: Option<abi::Api>,
    env: *mut jni_sys::JNIEnv,
    package_name: String,
    app_data_dir: String,
    app_pid: i32,
    app_uid: i32,
    should_redirect: bool,
    should_monitor: bool,
    is_mount_applied: bool,
    is_mount_request_sent: bool,
    is_system_writer_hook_redirect: bool,
    should_install_fuse_fixer: bool,
    should_skip_post_work: bool,
    should_keep_module_loaded: bool,
}

// SAFETY: 实例只通过全局 Mutex 串行访问，裸指针只做句柄透传
unsafe impl Send for RuntimeFlow {}
// SAFETY: 共享访问由 Mutex 同步，当前实现不在并发路径解引用裸指针
unsafe impl Sync for RuntimeFlow {}

impl RuntimeFlow {
    pub fn new() -> Self {
        Self {
            api: None,
            env: std::ptr::null_mut(),
            package_name: String::new(),
            app_data_dir: String::new(),
            app_pid: -1,
            app_uid: -1,
            should_redirect: false,
            should_monitor: false,
            is_mount_applied: false,
            is_mount_request_sent: false,
            is_system_writer_hook_redirect: false,
            should_install_fuse_fixer: false,
            should_skip_post_work: false,
            should_keep_module_loaded: false,
        }
    }

    pub fn on_load(&mut self, api: abi::Api, env: *mut jni_sys::JNIEnv) {
        self.api = Some(api);
        self.env = env;
        jni::init_java_vm(env);
        Logger::init(Some("zygisk"));
        boot::log_boot_summary_once();
    }

    pub fn pre_server_specialize(&mut self) {
        if let Some(api) = self.api.as_ref() {
            api.set_option(abi::ZygiskOption::DlcloseModuleLibrary);
        }
    }
}
