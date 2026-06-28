// FuseFixer 安装入口与 hook 回调：检测 Default Ignorable Code Point 并清理路径

use super::di::{has_default_ignorable, remove_default_ignorable};
use super::image::FuseJniImage;
use super::strings::{CxxString, Layout, StringAbi, read_cxx_string};
use once_cell::sync::OnceCell;
use srx_inline_hook::{HookMode, hook_sym_addr, init};
use std::os::raw::c_void;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

const TAG_INSTALL_OK: &str = "fuse fixer hook ok";
const TAG_INSTALL_PARTIAL: &str = "fuse fixer hook incomplete";

const SYM_IS_APP_NDK: &[u8] = b"_ZN13mediaprovider4fuseL22is_app_accessible_pathEP4fuseRKNSt6__ndk112basic_stringIcNS3_11char_traitsIcEENS3_9allocatorIcEEEEj";
const SYM_IS_APP_STD: &[u8] = b"_ZN13mediaprovider4fuseL22is_app_accessible_pathEP4fuseRKNSt3__112basic_stringIcNS3_11char_traitsIcEENS3_9allocatorIcEEEEj";
const SYM_IS_USER_NDK: &[u8] = b"_ZN13mediaprovider4fuseL23is_user_accessible_pathEP8fuse_reqPK4fuseRKNSt6__ndk112basic_stringIcNS6_11char_traitsIcEENS6_9allocatorIcEEEE";
const SYM_IS_USER_STD: &[u8] = b"_ZN13mediaprovider4fuseL23is_user_accessible_pathEP8fuse_reqPK4fuseRKNSt3__112basic_stringIcNS6_11char_traitsIcEENS6_9allocatorIcEEEE";
const SYM_IS_PKG_NDK: &[u8] = b"_ZL21is_package_owned_pathRKNSt6__ndk112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEEES7_";
const SYM_IS_PKG_STD: &[u8] = b"_ZL21is_package_owned_pathRKNSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEEES7_";
const SYM_IS_BPF_NDK: &[u8] =
    b"_ZL19is_bpf_backing_pathRKNSt6__ndk112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEEE";
const SYM_IS_BPF_STD: &[u8] =
    b"_ZL19is_bpf_backing_pathRKNSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEEE";

type IsAppFn = unsafe extern "C" fn(*const c_void, *const CxxString, u32) -> bool;
type IsUserFn = unsafe extern "C" fn(*const c_void, *const c_void, *const CxxString) -> bool;
type IsPkgFn = unsafe extern "C" fn(*const CxxString, *const CxxString) -> bool;
type IsBpfFn = unsafe extern "C" fn(*const CxxString) -> bool;

struct State {
    abi: StringAbi,
    layout: Layout,
    orig_is_app: AtomicU64,
    orig_is_user: AtomicU64,
    orig_is_pkg: AtomicU64,
    orig_is_bpf: AtomicU64,
}

static STATE: OnceCell<State> = OnceCell::new();
static INIT_OK: AtomicBool = AtomicBool::new(false);
static FIX_LOG_COUNT: AtomicU64 = AtomicU64::new(0);

pub fn run() -> bool {
    if INIT_OK.load(Ordering::Acquire) {
        return true;
    }
    if let Err(e) = init(HookMode::Unique, false) {
        log::warn!("fuse fixer init failed err={:?}", e);
        return false;
    }
    spawn_install_worker();
    log::info!("fuse fixer deferred");
    true
}

fn spawn_install_worker() {
    static STARTED: AtomicBool = AtomicBool::new(false);
    if STARTED.swap(true, Ordering::AcqRel) {
        return;
    }
    if let Err(error) = std::thread::Builder::new()
        .name("srx-fuse-fixer".into())
        .spawn(|| {
            // 起步 500ms 等 zygote specialize 收尾；60 × 250ms 共 ~15s 覆盖 libfuse_jni 加载完成
            unsafe { libc::usleep(500_000) };
            for _ in 0..60 {
                if INIT_OK.load(Ordering::Acquire) {
                    return;
                }
                if try_install() {
                    return;
                }
                unsafe { libc::usleep(250_000) };
            }
        })
    {
        log::warn!("fuse fixer worker spawn failed err={:?}", error);
    }
}

fn try_install() -> bool {
    log::info!("fuse fixer install step=locate begin");
    let Some(image) = FuseJniImage::locate() else {
        log::info!("fuse fixer install step=locate pending");
        return false;
    };
    log::info!("fuse fixer install step=locate ok");

    log::info!("fuse fixer install step=abi begin");
    let Some(abi) = StringAbi::resolve(&image) else {
        log::warn!("fuse fixer std::string abi unresolved");
        return false;
    };
    let layout = abi.layout();
    log::info!("fuse fixer install step=abi ok layout={}", layout.name());

    log::info!("fuse fixer install step=symbols begin");
    let Some(is_app_addr) = image
        .find_symbol(SYM_IS_APP_NDK)
        .or_else(|| image.find_symbol(SYM_IS_APP_STD))
    else {
        log::warn!("fuse fixer is_app_accessible_path missing");
        return false;
    };
    log::info!(
        "fuse fixer install step=symbols ok is_app=0x{:x}",
        is_app_addr
    );

    let state = State {
        abi,
        layout,
        orig_is_app: AtomicU64::new(0),
        orig_is_user: AtomicU64::new(0),
        orig_is_pkg: AtomicU64::new(0),
        orig_is_bpf: AtomicU64::new(0),
    };
    let state = STATE.get_or_init(|| state);

    log::info!("fuse fixer install step=hook begin");
    let ok = install_one(
        "is_app_accessible_path",
        Some(is_app_addr),
        hooked_is_app as *const c_void,
        &state.orig_is_app,
        true,
    );
    install_one(
        "is_user_accessible_path",
        image
            .find_symbol(SYM_IS_USER_NDK)
            .or_else(|| image.find_symbol(SYM_IS_USER_STD)),
        hooked_is_user as *const c_void,
        &state.orig_is_user,
        false,
    );
    install_one(
        "is_package_owned_path",
        image
            .find_symbol(SYM_IS_PKG_NDK)
            .or_else(|| image.find_symbol(SYM_IS_PKG_STD)),
        hooked_is_pkg as *const c_void,
        &state.orig_is_pkg,
        false,
    );
    install_one(
        "is_bpf_backing_path",
        image
            .find_symbol(SYM_IS_BPF_NDK)
            .or_else(|| image.find_symbol(SYM_IS_BPF_STD)),
        hooked_is_bpf as *const c_void,
        &state.orig_is_bpf,
        false,
    );

    if ok {
        INIT_OK.store(true, Ordering::Release);
        log::info!("{}", TAG_INSTALL_OK);
    } else {
        log::warn!("{}", TAG_INSTALL_PARTIAL);
    }
    ok
}

fn install_one(
    name: &str,
    target: Option<usize>,
    replacement: *const c_void,
    orig_slot: &AtomicU64,
    required: bool,
) -> bool {
    let Some(target_addr) = target else {
        if required {
            log::warn!("fuse fixer symbol missing {}", name);
        } else {
            log::info!("fuse fixer optional symbol absent {}", name);
        }
        return !required;
    };
    let mut orig: *const c_void = std::ptr::null();
    let result = unsafe {
        hook_sym_addr(
            target_addr as *mut c_void,
            replacement,
            &mut orig as *mut *const c_void,
        )
    };
    if result.is_err() || orig.is_null() {
        log::warn!("fuse fixer hook {} failed err={:?}", name, result.err());
        return false;
    }
    orig_slot.store(orig as u64, Ordering::Release);
    log::info!("fuse fixer hook {} ok", name);
    true
}

fn log_fix(site: &str) {
    let n = FIX_LOG_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
    if n <= 16 || n.is_multiple_of(256) {
        log::info!("fuse fixer normalized site={} n={}", site, n);
    }
}

unsafe extern "C" fn hooked_is_app(fuse: *const c_void, path: *const CxxString, uid: u32) -> bool {
    let Some(state) = STATE.get() else {
        return false;
    };
    let raw = state.orig_is_app.load(Ordering::Acquire);
    if raw == 0 {
        return false;
    }
    let orig: IsAppFn = unsafe { std::mem::transmute::<u64, IsAppFn>(raw) };
    let bytes = unsafe { read_cxx_string(path, state.layout) };
    if !has_default_ignorable(bytes) {
        return unsafe { orig(fuse, path, uid) };
    }
    let cleaned = remove_default_ignorable(bytes);
    log_fix("is_app_accessible_path");
    let mut tmp = unsafe { state.abi.construct(&cleaned) };
    let result = unsafe { orig(fuse, &tmp as *const CxxString, uid) };
    unsafe { state.abi.drop_string(&mut tmp) };
    result
}

unsafe extern "C" fn hooked_is_user(
    req: *const c_void,
    fuse: *const c_void,
    path: *const CxxString,
) -> bool {
    let Some(state) = STATE.get() else {
        return false;
    };
    let raw = state.orig_is_user.load(Ordering::Acquire);
    if raw == 0 {
        return false;
    }
    let orig: IsUserFn = unsafe { std::mem::transmute::<u64, IsUserFn>(raw) };
    let bytes = unsafe { read_cxx_string(path, state.layout) };
    if !has_default_ignorable(bytes) {
        return unsafe { orig(req, fuse, path) };
    }
    let cleaned = remove_default_ignorable(bytes);
    log_fix("is_user_accessible_path");
    let mut tmp = unsafe { state.abi.construct(&cleaned) };
    let result = unsafe { orig(req, fuse, &tmp as *const CxxString) };
    unsafe { state.abi.drop_string(&mut tmp) };
    result
}

unsafe extern "C" fn hooked_is_pkg(path: *const CxxString, fuse_path: *const CxxString) -> bool {
    let Some(state) = STATE.get() else {
        return false;
    };
    let raw = state.orig_is_pkg.load(Ordering::Acquire);
    if raw == 0 {
        return false;
    }
    let orig: IsPkgFn = unsafe { std::mem::transmute::<u64, IsPkgFn>(raw) };
    let bytes = unsafe { read_cxx_string(path, state.layout) };
    if !has_default_ignorable(bytes) {
        return unsafe { orig(path, fuse_path) };
    }
    let cleaned = remove_default_ignorable(bytes);
    log_fix("is_package_owned_path");
    let mut tmp = unsafe { state.abi.construct(&cleaned) };
    let result = unsafe { orig(&tmp as *const CxxString, fuse_path) };
    unsafe { state.abi.drop_string(&mut tmp) };
    result
}

unsafe extern "C" fn hooked_is_bpf(path: *const CxxString) -> bool {
    let Some(state) = STATE.get() else {
        return false;
    };
    let raw = state.orig_is_bpf.load(Ordering::Acquire);
    if raw == 0 {
        return false;
    }
    let orig: IsBpfFn = unsafe { std::mem::transmute::<u64, IsBpfFn>(raw) };
    let bytes = unsafe { read_cxx_string(path, state.layout) };
    if !has_default_ignorable(bytes) {
        return unsafe { orig(path) };
    }
    let cleaned = remove_default_ignorable(bytes);
    log_fix("is_bpf_backing_path");
    let mut tmp = unsafe { state.abi.construct(&cleaned) };
    let result = unsafe { orig(&tmp as *const CxxString) };
    unsafe { state.abi.drop_string(&mut tmp) };
    result
}
