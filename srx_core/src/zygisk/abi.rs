// Zygisk ABI 类型与 API 表封装，字段顺序与 zygisk.hpp 严格对齐
use libc::{c_char, c_int, c_long, c_void, dev_t, ino_t};

// 本模块对齐的 Zygisk API 版本，低于该值的实现不会加载模块
pub const ZYGISK_API_VERSION: c_long = 4;

// 调用 set_option 时传入的行为开关，目前主要用 DlcloseModuleLibrary
#[repr(i32)]
#[derive(Copy, Clone)]
pub enum ZygiskOption {
    #[allow(dead_code)]
    ForceDenylistUnmount = 0,
    DlcloseModuleLibrary = 1,
}

// get_flags 返回的位标志：当前进程是否拿到 root、是否在 denylist
#[allow(dead_code)]
#[repr(u32)]
#[derive(Copy, Clone)]
pub enum StateFlag {
    ProcessGrantedRoot = 1 << 0,
    ProcessOnDenylist = 1 << 1,
}

// Zygisk 传给模块的 API 函数表，impl_ptr 是每个回调需要的 self 参数
#[repr(C)]
pub struct ApiTable {
    pub impl_ptr: *mut c_void,
    pub register_module:
        std::option::Option<unsafe extern "C" fn(*mut ApiTable, *mut ModuleAbi) -> bool>,
    pub hook_jni_native_methods: std::option::Option<
        unsafe extern "C" fn(
            *mut jni_sys::JNIEnv,
            *const c_char,
            *mut jni_sys::JNINativeMethod,
            c_int,
        ),
    >,
    pub plt_hook_register: std::option::Option<
        unsafe extern "C" fn(dev_t, ino_t, *const c_char, *mut c_void, *mut *mut c_void),
    >,
    pub exempt_fd: std::option::Option<unsafe extern "C" fn(c_int) -> bool>,
    pub plt_hook_commit: std::option::Option<unsafe extern "C" fn() -> bool>,
    pub connect_companion: std::option::Option<unsafe extern "C" fn(*mut c_void) -> c_int>,
    pub set_option: std::option::Option<unsafe extern "C" fn(*mut c_void, ZygiskOption)>,
    pub get_module_dir: std::option::Option<unsafe extern "C" fn(*mut c_void) -> c_int>,
    pub get_flags: std::option::Option<unsafe extern "C" fn(*mut c_void) -> u32>,
}

// 模块回填给 Zygisk 的 ABI 结构，impl_ptr 会随每次 specialize 回调原样传回
#[repr(C)]
pub struct ModuleAbi {
    pub api_version: c_long,
    pub impl_ptr: *mut c_void,
    pub pre_app_specialize:
        std::option::Option<unsafe extern "C" fn(*mut c_void, *mut AppSpecializeArgs)>,
    pub post_app_specialize:
        std::option::Option<unsafe extern "C" fn(*mut c_void, *const AppSpecializeArgs)>,
    pub pre_server_specialize:
        std::option::Option<unsafe extern "C" fn(*mut c_void, *mut ServerSpecializeArgs)>,
    pub post_server_specialize:
        std::option::Option<unsafe extern "C" fn(*mut c_void, *const ServerSpecializeArgs)>,
}

// SAFETY: 只承载 ABI 回调和句柄指针，并发路径不会直接解引用字段
unsafe impl Send for ModuleAbi {}
// SAFETY: Zygisk 保证回调按确定顺序触发，实例按只读方式共享
unsafe impl Sync for ModuleAbi {}

// app_process specialize 阶段的参数块，字段全是 Android 源码同名项的指针
#[repr(C)]
pub struct AppSpecializeArgs {
    pub uid: *mut jni_sys::jint,
    pub gid: *mut jni_sys::jint,
    pub gids: *mut jni_sys::jintArray,
    pub runtime_flags: *mut jni_sys::jint,
    pub rlimits: *mut jni_sys::jobjectArray,
    pub mount_external: *mut jni_sys::jint,
    pub se_info: *mut jni_sys::jstring,
    pub nice_name: *mut jni_sys::jstring,
    pub instruction_set: *mut jni_sys::jstring,
    pub app_data_dir: *mut jni_sys::jstring,
    pub fds_to_ignore: *const jni_sys::jintArray,
    pub is_child_zygote: *const jni_sys::jboolean,
    pub is_top_app: *const jni_sys::jboolean,
    pub pkg_data_info_list: *const jni_sys::jobjectArray,
    pub whitelisted_data_info_list: *const jni_sys::jobjectArray,
    pub mount_data_dirs: *const jni_sys::jboolean,
    pub mount_storage_dirs: *const jni_sys::jboolean,
}

impl AppSpecializeArgs {
    #[inline]
    pub fn is_child_zygote(&self) -> bool {
        if self.is_child_zygote.is_null() {
            return false;
        }
        unsafe { *self.is_child_zygote }
    }
}

// system_server specialize 阶段的参数块
#[repr(C)]
pub struct ServerSpecializeArgs {
    pub uid: *mut jni_sys::jint,
    pub gid: *mut jni_sys::jint,
    pub gids: *mut jni_sys::jintArray,
    pub runtime_flags: *mut jni_sys::jint,
    pub permitted_capabilities: *mut jni_sys::jlong,
    pub effective_capabilities: *mut jni_sys::jlong,
}

// Zygisk API 表的 Rust 侧句柄，所有方法内部都做空指针保护
#[derive(Clone, Copy)]
pub struct Api {
    table: *mut ApiTable,
}

impl Api {
    pub fn new(table: *mut ApiTable) -> Self {
        Self { table }
    }

    // 连接 companion 子进程，返回通信 socket 的 fd
    pub fn connect_companion(&self) -> c_int {
        unsafe {
            if let Some(func) = (*self.table).connect_companion {
                func((*self.table).impl_ptr)
            } else {
                -1
            }
        }
    }

    // 拿到模块目录的 fd，Zygisk 拒绝或函数缺失时返回 -1
    #[allow(dead_code)]
    pub fn get_module_dir(&self) -> c_int {
        unsafe {
            if let Some(func) = (*self.table).get_module_dir {
                func((*self.table).impl_ptr)
            } else {
                -1
            }
        }
    }

    // 设置模块加载选项，常用于请求加载结束后 dlclose 自身
    pub fn set_option(&self, opt: ZygiskOption) {
        unsafe {
            if let Some(func) = (*self.table).set_option {
                func((*self.table).impl_ptr, opt);
            }
        }
    }

    // 查询当前进程状态位，ABI 缺失时返回 0
    #[allow(dead_code)]
    pub fn get_flags(&self) -> u32 {
        unsafe {
            if let Some(func) = (*self.table).get_flags {
                func((*self.table).impl_ptr)
            } else {
                0
            }
        }
    }

    // 把 fd 登记成豁免项，specialize 结束后 Zygisk 不会强制关掉它
    pub fn exempt_fd(&self, fd: c_int) -> bool {
        unsafe {
            if let Some(func) = (*self.table).exempt_fd {
                func(fd)
            } else {
                false
            }
        }
    }

    // 批量替换 Java 类的 native 方法，Zygisk 把原实现写回每条 method 的 fnPtr
    #[allow(dead_code)]
    pub fn hook_jni_native_methods(
        &self,
        env: *mut jni_sys::JNIEnv,
        class_name: *const c_char,
        methods: *mut jni_sys::JNINativeMethod,
        count: c_int,
    ) {
        unsafe {
            if let Some(func) = (*self.table).hook_jni_native_methods {
                func(env, class_name, methods, count);
            }
        }
    }

    // 登记一条 PLT hook；原函数指针写回 old_func，真正生效需 plt_hook_commit
    #[allow(dead_code)]
    pub fn plt_hook_register(
        &self,
        dev: dev_t,
        inode: ino_t,
        symbol: *const c_char,
        new_func: *mut c_void,
        old_func: *mut *mut c_void,
    ) {
        unsafe {
            if let Some(func) = (*self.table).plt_hook_register {
                func(dev, inode, symbol, new_func, old_func);
            }
        }
    }

    // 把登记过的 PLT hook 一次性刷入，任一条目失败整体返回 false
    #[allow(dead_code)]
    pub fn plt_hook_commit(&self) -> bool {
        unsafe {
            if let Some(func) = (*self.table).plt_hook_commit {
                func()
            } else {
                false
            }
        }
    }
}
