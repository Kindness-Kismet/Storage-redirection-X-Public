use super::context;
use super::stats::InterceptHub;
use crate::config::SettingsHub;
use crate::monitor::AuditTrail;
use crate::redirect::policy;
use libc::{RTLD_LOCAL, RTLD_NOW, c_int, c_void, dlopen, dlsym, getpid, getuid, prctl};
use std::cell::Cell;
use std::ffi::CString;
use std::sync::{
    OnceLock,
    atomic::{AtomicU64, Ordering},
};

const ANDROID_APP_UID_START: i32 = 10000;
const FUSE_CALLER_MAX_AGE_MS: i64 = 1500;
const BINDER_SAVED_CALLER_MAX_AGE_MS: i64 = 1500;
const BINDER_SAVED_CALLER_HARD_LIMIT_MS: i64 = 30_000;
const CALLER_LOG_SAMPLE_STEP: u64 = 1024;
static CALLER_FUSE_STALE_COUNT: AtomicU64 = AtomicU64::new(0);
static CALLER_SAVED_UID_STALE_COUNT: AtomicU64 = AtomicU64::new(0);
static CALLER_FALLBACK_HIT_COUNT: AtomicU64 = AtomicU64::new(0);
static CALLER_UNRESOLVED_COUNT: AtomicU64 = AtomicU64::new(0);
static CALLER_SIGNAL_SKIP_COUNT: AtomicU64 = AtomicU64::new(0);

thread_local! {
    // Binder 线程名终身不变，缓存避免重复 prctl 系统调用
    static BINDER_THREAD_CACHED: Cell<Option<bool>> = const { Cell::new(None) };
}

fn is_likely_binder_thread_cached() -> bool {
    BINDER_THREAD_CACHED.with(|cell| {
        if let Some(cached) = cell.get() {
            return cached;
        }
        let result = is_likely_binder_thread();
        cell.set(Some(result));
        result
    })
}

// FUSE / Binder saved UID / Binder 线程三者皆无时调用方解析必定失败，可直接跳过
fn has_caller_signal(hub: &InterceptHub) -> bool {
    let fuse_uid = hub.get_fuse_caller_uid();
    if fuse_uid >= ANDROID_APP_UID_START {
        let fuse_age = context::get_fuse_caller_uid_age_ms();
        if (0..=FUSE_CALLER_MAX_AGE_MS).contains(&fuse_age) {
            return true;
        }
    }

    let saved_uid = context::get_binder_saved_caller_uid();
    if saved_uid >= ANDROID_APP_UID_START {
        let max_age = if context::is_binder_identity_cleared() {
            BINDER_SAVED_CALLER_HARD_LIMIT_MS
        } else {
            BINDER_SAVED_CALLER_MAX_AGE_MS
        };
        let saved_age = context::get_binder_saved_caller_uid_age_ms();
        if (0..=max_age).contains(&saved_age) {
            return true;
        }
    }

    is_likely_binder_thread_cached()
}

// 通过 Binder / FUSE / PID 多级回退解析当前线程调用方
pub fn update_caller_package_for_current_thread(hub: &InterceptHub) {
    if !has_caller_signal(hub) {
        let count = CALLER_SIGNAL_SKIP_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
        if should_log_sample(count) {
            log::debug!(
                "caller signal skip proc={} n={}",
                hub.get_package_name(),
                count
            );
        }
        hub.clear_current_caller();
        return;
    }

    let binder_uid = resolve_caller_uid_by_binder();
    let mut caller_uid = binder_uid;
    let mut caller_source = "binder_uid";
    let mut fuse_uid = -1;
    let mut fuse_age_ms = -1;

    if caller_uid < 0 {
        fuse_uid = hub.get_fuse_caller_uid();
        fuse_age_ms = context::get_fuse_caller_uid_age_ms();
        let self_uid = unsafe { getuid() } as i32;
        if fuse_uid >= ANDROID_APP_UID_START && (0..=FUSE_CALLER_MAX_AGE_MS).contains(&fuse_age_ms)
        {
            if fuse_uid != self_uid {
                caller_uid = fuse_uid;
                caller_source = "fuse_uid";
            } else {
                // 共享 UID：UID 相同但 PID 不同说明来自同组的另一个进程
                let fuse_pid = context::get_fuse_caller_pid();
                let self_pid = unsafe { getpid() } as i32;
                if fuse_pid > 0 && fuse_pid != self_pid {
                    let pkg = resolve_caller_package_by_pid(fuse_pid);
                    if !pkg.is_empty() {
                        hub.set_current_caller_uid(fuse_uid);
                        hub.set_current_caller_package(&pkg);
                        AuditTrail::instance().update_caller_package(&pkg);
                        let count = CALLER_FALLBACK_HIT_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
                        if should_log_sample(count) {
                            log::debug!(
                                "caller fallback src=fuse_pid pkg={} uid={} fuse_pid={} n={}",
                                pkg,
                                fuse_uid,
                                fuse_pid,
                                count
                            );
                        }
                        context::clear_fuse_caller_uid();
                        return;
                    }
                }
            }
        } else if fuse_uid >= ANDROID_APP_UID_START && fuse_age_ms > FUSE_CALLER_MAX_AGE_MS {
            let count = CALLER_FUSE_STALE_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
            if should_log_sample(count) {
                log::debug!(
                    "caller skip stale fuse uid={} age_ms={} n={}",
                    fuse_uid,
                    fuse_age_ms,
                    count
                );
            }
        }
        context::clear_fuse_caller_uid();
    }

    let mut package_name = String::new();
    if caller_uid >= ANDROID_APP_UID_START {
        package_name = resolve_caller_package_by_uid(caller_uid, hub);
    }

    let mut caller_pid = -1;
    let saved_uid = context::get_binder_saved_caller_uid();
    let saved_uid_age_ms = context::get_binder_saved_caller_uid_age_ms();
    let identity_cleared = context::is_binder_identity_cleared();
    if package_name.is_empty() {
        caller_pid = resolve_caller_pid_by_binder();
        if caller_pid > 0 {
            package_name = resolve_caller_package_by_pid(caller_pid);
            if !package_name.is_empty() {
                caller_source = "binder_pid";
            }
        }
        // clearCallingIdentity 前保存的调用方 UID 作为末级回退
        // 处于 clear/restore 区间时直接使用，否则走时间窗口兜底
        if package_name.is_empty() && saved_uid >= ANDROID_APP_UID_START {
            let saved_uid_valid = if identity_cleared {
                (0..=BINDER_SAVED_CALLER_HARD_LIMIT_MS).contains(&saved_uid_age_ms)
            } else {
                (0..=BINDER_SAVED_CALLER_MAX_AGE_MS).contains(&saved_uid_age_ms)
            };
            if saved_uid_valid {
                caller_uid = saved_uid;
                package_name = resolve_caller_package_by_uid(saved_uid, hub);
                if !package_name.is_empty() {
                    caller_source = "saved_uid";
                }
            } else {
                context::clear_binder_saved_caller_uid();
                context::set_binder_identity_cleared(false);
                if saved_uid_age_ms > BINDER_SAVED_CALLER_MAX_AGE_MS {
                    let count = CALLER_SAVED_UID_STALE_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
                    if should_log_sample(count) {
                        log::debug!(
                            "caller skip stale saved_uid uid={} age_ms={} cleared={} n={}",
                            saved_uid,
                            saved_uid_age_ms,
                            identity_cleared,
                            count
                        );
                    }
                }
            }
        }
        if package_name.is_empty() {
            caller_uid = -1;
        }
    }

    if !package_name.is_empty() {
        hub.set_current_caller_uid(caller_uid);
        hub.set_current_caller_package(&package_name);
        AuditTrail::instance().update_caller_package(&package_name);
        if caller_source != "binder_uid" {
            let count = CALLER_FALLBACK_HIT_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
            if should_log_sample(count) {
                log::debug!(
                    "caller fallback src={} pkg={} uid={} binder_uid={} fuse_uid={} fuse_age_ms={} cleared={} n={}",
                    caller_source,
                    package_name,
                    caller_uid,
                    binder_uid,
                    fuse_uid,
                    fuse_age_ms,
                    identity_cleared,
                    count
                );
            }
        }
        return;
    }

    let count = CALLER_UNRESOLVED_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
    if should_log_sample(count) {
        log::warn!(
            "caller unresolved proc={} binder_uid={} binder_pid={} saved_uid={} saved_age_ms={} fuse_uid={} fuse_age_ms={} n={}",
            hub.get_package_name(),
            binder_uid,
            caller_pid,
            saved_uid,
            saved_uid_age_ms,
            fuse_uid,
            fuse_age_ms,
            count
        );
    }
    hub.clear_current_caller();
}

#[inline]
fn should_log_sample(count: u64) -> bool {
    count == 1 || count.is_multiple_of(CALLER_LOG_SAMPLE_STEP)
}

fn resolve_caller_uid_by_binder() -> i32 {
    if !is_likely_binder_thread_cached() {
        return -1;
    }

    let symbols = get_caller_binder_symbols();
    let mut caller_uid = -1;
    if let Some(func) = symbols.ndk_get_calling_uid {
        caller_uid = unsafe { func() } as i32;
    }

    if caller_uid < 0
        && let (Some(self_fn), Some(get_uid_fn)) =
            (symbols.binder_self, symbols.binder_get_calling_uid)
    {
        let ipc_state = unsafe { self_fn() };
        if !ipc_state.is_null() {
            caller_uid = unsafe { get_uid_fn(ipc_state) } as i32;
        }
    }

    if caller_uid < ANDROID_APP_UID_START {
        return -1;
    }

    let self_uid = unsafe { getuid() } as i32;
    if caller_uid == self_uid {
        return -1;
    }

    caller_uid
}

fn resolve_caller_pid_by_binder() -> i32 {
    if !is_likely_binder_thread_cached() {
        return -1;
    }

    let symbols = get_caller_binder_symbols();
    let mut caller_pid = -1;
    if let Some(func) = symbols.ndk_get_calling_pid {
        caller_pid = unsafe { func() };
    }

    if caller_pid <= 0
        && let (Some(self_fn), Some(get_pid_fn)) =
            (symbols.binder_self, symbols.binder_get_calling_pid)
    {
        let ipc_state = unsafe { self_fn() };
        if !ipc_state.is_null() {
            caller_pid = unsafe { get_pid_fn(ipc_state) };
        }
    }

    if caller_pid <= 0 || caller_pid == unsafe { getpid() } as i32 {
        return -1;
    }

    caller_pid
}

// 通过 /proc/PID/cmdline 取包名
fn resolve_caller_package_by_pid(pid: i32) -> String {
    if pid <= 0 {
        return String::new();
    }

    let cmdline_path = format!("/proc/{}/cmdline", pid);
    let mut cmdline = read_single_line_by_syscall(&cmdline_path);
    if cmdline.is_empty() {
        return String::new();
    }

    if let Some(pos) = cmdline.find('\0') {
        cmdline.truncate(pos);
    }

    if let Some(pos) = cmdline.find(':') {
        cmdline.truncate(pos);
    }

    normalize_caller_package_text(&cmdline)
}

fn resolve_caller_package_by_uid(caller_uid: i32, hub: &InterceptHub) -> String {
    if caller_uid < ANDROID_APP_UID_START {
        return String::new();
    }

    let mut packages = policy::get_packages_for_uid(caller_uid);
    if packages.is_empty() {
        policy::refresh_shared_uid_cache();
        packages = policy::get_packages_for_uid(caller_uid);
    }
    if packages.is_empty() {
        return String::new();
    }

    packages.sort();
    packages.dedup();

    let mut candidates = Vec::new();
    let self_package = hub.get_package_name();
    for package in packages {
        if package.is_empty() || package == self_package {
            continue;
        }
        if policy::is_system_writer_package(&package) {
            continue;
        }
        candidates.push(package);
    }

    if candidates.is_empty() {
        return String::new();
    }
    if candidates.len() == 1 {
        return candidates.remove(0);
    }

    let config = SettingsHub::instance();
    let mut mapping_candidates = Vec::new();
    let mut fallback_candidates = Vec::new();

    for candidate in candidates {
        if !config.should_redirect(&candidate, caller_uid) {
            continue;
        }
        let mappings = config.get_path_mappings(&candidate, caller_uid);
        if !mappings.is_empty() {
            mapping_candidates.push(candidate);
        } else {
            fallback_candidates.push(candidate);
        }
    }

    if mapping_candidates.len() == 1 {
        return mapping_candidates.remove(0);
    }
    if mapping_candidates.is_empty() && fallback_candidates.len() == 1 {
        return fallback_candidates.remove(0);
    }

    String::new()
}

fn is_likely_binder_thread() -> bool {
    let mut name = [0u8; 17];
    let ret = unsafe { prctl(libc::PR_GET_NAME, name.as_mut_ptr() as *mut c_void, 0, 0, 0) };
    if ret != 0 {
        return false;
    }

    let raw = String::from_utf8_lossy(&name);
    raw.starts_with("Binder:")
        || raw.starts_with("binder:")
        || raw.starts_with("HwBinder:")
        || raw.starts_with("hwbinder:")
}

// 直接走 syscall，避免触发自身 Hook
fn read_single_line_by_syscall(path: &str) -> String {
    if path.is_empty() {
        return String::new();
    }

    let Ok(c_path) = CString::new(path) else {
        return String::new();
    };

    let fd = unsafe {
        libc::syscall(
            libc::SYS_openat,
            libc::AT_FDCWD,
            c_path.as_ptr(),
            libc::O_RDONLY | libc::O_CLOEXEC,
            0,
        ) as c_int
    };
    if fd < 0 {
        return String::new();
    }

    let mut buffer = [0u8; 256];
    let mut total = 0usize;
    while total < buffer.len() - 1 {
        let n = unsafe {
            libc::read(
                fd,
                buffer[total..].as_mut_ptr() as *mut c_void,
                buffer.len() - 1 - total,
            )
        };
        if n <= 0 {
            break;
        }

        total += n as usize;
        if buffer[..total]
            .iter()
            .any(|b| *b == 0 || *b == b'\n' || *b == b'\r')
        {
            break;
        }
    }

    unsafe { libc::close(fd) };
    if total == 0 {
        return String::new();
    }

    let mut end = 0usize;
    while end < total {
        let ch = buffer[end];
        if ch == 0 || ch == b'\n' || ch == b'\r' {
            break;
        }
        end += 1;
    }

    if end == 0 {
        return String::new();
    }

    String::from_utf8_lossy(&buffer[..end]).to_string()
}

fn normalize_caller_package_text(text: &str) -> String {
    if text.is_empty() {
        return String::new();
    }

    let mut value = text.to_string();
    if let Some(pos) = value.find(['\r', '\n', '\t', ' ']) {
        value.truncate(pos);
    }
    if value.is_empty() {
        return String::new();
    }

    if !value
        .chars()
        .all(|ch| ch.is_ascii_alphanumeric() || ch == '.' || ch == '_' || ch == '-')
    {
        return String::new();
    }
    value
}

#[derive(Clone, Copy)]
struct CallerBinderSymbols {
    ndk_get_calling_uid: Option<unsafe extern "C" fn() -> u32>,
    ndk_get_calling_pid: Option<unsafe extern "C" fn() -> i32>,
    binder_self: Option<unsafe extern "C" fn() -> *mut c_void>,
    binder_get_calling_uid: Option<unsafe extern "C" fn(*mut c_void) -> u32>,
    binder_get_calling_pid: Option<unsafe extern "C" fn(*mut c_void) -> i32>,
}

fn get_caller_binder_symbols() -> &'static CallerBinderSymbols {
    static SYMBOLS: OnceLock<CallerBinderSymbols> = OnceLock::new();
    SYMBOLS.get_or_init(|| {
        let mut symbols = CallerBinderSymbols {
            ndk_get_calling_uid: None,
            ndk_get_calling_pid: None,
            binder_self: None,
            binder_get_calling_uid: None,
            binder_get_calling_pid: None,
        };

        unsafe {
            if let Some(libbinder_ndk_name) = c_str("libbinder_ndk.so") {
                let libbinder_ndk = dlopen(libbinder_ndk_name.as_ptr(), RTLD_NOW | RTLD_LOCAL);
                if !libbinder_ndk.is_null() {
                    symbols.ndk_get_calling_uid =
                        load_symbol(libbinder_ndk, "AIBinder_getCallingUid");
                    symbols.ndk_get_calling_pid =
                        load_symbol(libbinder_ndk, "AIBinder_getCallingPid");
                }
            }

            if let Some(libbinder_name) = c_str("libbinder.so") {
                let libbinder = dlopen(libbinder_name.as_ptr(), RTLD_NOW | RTLD_LOCAL);
                if !libbinder.is_null() {
                    symbols.binder_self =
                        load_symbol(libbinder, "_ZN7android14IPCThreadState4selfEv");
                    symbols.binder_get_calling_uid =
                        load_symbol(libbinder, "_ZNK7android14IPCThreadState13getCallingUidEv");
                    if symbols.binder_get_calling_uid.is_none() {
                        symbols.binder_get_calling_uid =
                            load_symbol(libbinder, "_ZN7android14IPCThreadState13getCallingUidEv");
                    }
                    symbols.binder_get_calling_pid =
                        load_symbol(libbinder, "_ZNK7android14IPCThreadState13getCallingPidEv");
                    if symbols.binder_get_calling_pid.is_none() {
                        symbols.binder_get_calling_pid =
                            load_symbol(libbinder, "_ZN7android14IPCThreadState13getCallingPidEv");
                    }
                }
            }
        }

        symbols
    })
}

unsafe fn load_symbol<T>(handle: *mut c_void, name: &str) -> Option<T> {
    let c_name = c_str(name)?;
    let symbol = dlsym(handle, c_name.as_ptr());
    if symbol.is_null() {
        None
    } else {
        Some(std::mem::transmute_copy(&symbol))
    }
}

fn c_str(value: &str) -> Option<CString> {
    CString::new(value).ok()
}

// clearCallingIdentity 之前提前快照 UID，供后续 restore 区间回查
pub fn capture_binder_caller_uid_if_available() {
    let uid = resolve_caller_uid_by_binder();
    if uid >= ANDROID_APP_UID_START {
        context::set_binder_saved_caller_uid(uid);
    } else {
        context::clear_binder_saved_caller_uid();
    }
}
