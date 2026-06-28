use super::stats::InterceptHub;
use super::{caller, context, diagnostic, monitor, path as path_utils};
use crate::platform::{fs, paths};
use crate::redirect::{policy, process_redirect_path, record_redirect_hit};
use libc::{AT_FDCWD, c_char, c_void, mode_t};
use std::ffi::CString;
use std::sync::atomic::{AtomicU64, Ordering};

static CALL_PREV_FALLBACK_MISS: AtomicU64 = AtomicU64::new(0);
static FORK_CHILD_HOOK_BYPASS: AtomicU64 = AtomicU64::new(0);

#[inline]
fn should_log_fallback_miss(count: u64) -> bool {
    count == 1 || count.is_multiple_of(256)
}

#[inline]
fn log_call_prev_fallback(site: &str) {
    let count = CALL_PREV_FALLBACK_MISS.fetch_add(1, Ordering::Relaxed) + 1;
    if should_log_fallback_miss(count) {
        log::warn!("{}: prev unavail, libc fallback n={}", site, count);
    }
}

#[inline]
fn should_bypass_hook_in_fork_child(hub: &InterceptHub) -> bool {
    if !srx_hook::is_forked_child() {
        return false;
    }

    if policy::is_system_writer_package(&hub.get_package_name()) {
        return false;
    }

    let process_uid = unsafe { libc::getuid() as i32 };
    !policy::is_shared_uid_process(process_uid)
}

#[inline]
fn log_fork_child_hook_bypass(package_name: &str) {
    let count = FORK_CHILD_HOOK_BYPASS.fetch_add(1, Ordering::Relaxed) + 1;
    if should_log_fallback_miss(count) {
        log::warn!("fork child hook bypass pkg={} n={}", package_name, count);
    }
}

pub unsafe fn call_prev<R: Copy, F, Fb>(proxy_fn: *mut c_void, libc_fallback: Fb, f: F) -> R
where
    F: FnOnce(*mut c_void) -> R,
    Fb: FnOnce() -> R,
{
    match srx_hook::with_prev_func(
        proxy_fn,
        |prev| {
            if prev.is_null() { None } else { Some(f(prev)) }
        },
    ) {
        Some(Some(result)) => result,
        None => {
            log_call_prev_fallback("call_prev");
            libc_fallback()
        }
        Some(None) => libc_fallback(),
    }
}

// 惰性版本：避免带副作用的 fallback 被提前求值
pub unsafe fn call_prev_lazy<R, F, Fb>(proxy_fn: *mut c_void, fallback_fn: Fb, f: F) -> R
where
    F: FnOnce(*mut c_void) -> R,
    Fb: FnOnce() -> R,
{
    match srx_hook::with_prev_func(
        proxy_fn,
        |prev| {
            if prev.is_null() { None } else { Some(f(prev)) }
        },
    ) {
        Some(Some(result)) => result,
        None => {
            log_call_prev_fallback("call_prev_lazy");
            fallback_fn()
        }
        Some(None) => fallback_fn(),
    }
}

// 重入场景走原函数；fork 子进程只保留系统代写进程的 hook
pub fn with_hook_guard<OriginalCall, HookCall, R>(
    original_call: OriginalCall,
    hook_call: HookCall,
) -> R
where
    OriginalCall: FnOnce() -> R,
    HookCall: FnOnce(&InterceptHub) -> R,
{
    if context::ReentryGuard::is_reentrant() {
        return original_call();
    }

    let hub = InterceptHub::instance();
    if should_bypass_hook_in_fork_child(hub) {
        log_fork_child_hook_bypass(&hub.get_package_name());
        return original_call();
    }

    let _guard = context::ReentryGuard::enter();
    hook_call(hub)
}

pub fn should_resolve_caller_context(hub: &InterceptHub) -> bool {
    hub.is_monitor_enabled() || policy::is_system_writer_package(&hub.get_package_name())
}

// 写入类重定向时按需补齐目标父目录
pub fn ensure_redirect_parent_directory(op_name: &str, from_path: &str, to_path: &str, flags: i32) {
    if from_path == to_path || !monitor::has_write_intent_flags(flags) {
        return;
    }

    let parent_dir = paths::parent(to_path);
    if parent_dir.is_empty() || parent_dir == "/" {
        return;
    }

    if fs::is_directory(&parent_dir) {
        return;
    }

    if fs::create_directory(&parent_dir, -1) {
        log::debug!("redirect parent mkdir ok op={} dir={}", op_name, parent_dir);
        return;
    }

    let error_no = unsafe { *libc::__errno() };
    log::warn!(
        "redirect parent mkdir failed op={} dir={} errno={} from={} to={}",
        op_name,
        parent_dir,
        error_no,
        from_path,
        to_path
    );
}

// 入口为用户可见 /storage/emulated/X，底层要落到 /data/media/X
pub fn ensure_redirect_parent_dirs(path: &str, mode: mode_t) {
    if path.is_empty() {
        return;
    }

    const STORAGE_PREFIX: &str = "/storage/emulated/";
    if let Some(suffix) = path.strip_prefix(STORAGE_PREFIX) {
        let underlying = format!("/data/media/{}", suffix);
        create_parent_dirs_recursive(&underlying, mode);
    } else {
        create_parent_dirs_recursive(path, mode);
    }
}

// 解析路径参数并在命中重定向时替换后调用原函数
pub fn with_redirected_path<F, R>(
    hub: &InterceptHub,
    op_name: &str,
    pathname: *const c_char,
    call_original: F,
) -> R
where
    F: FnOnce(*const c_char) -> R,
{
    if should_resolve_caller_context(hub) {
        caller::update_caller_package_for_current_thread(hub);
    }

    if pathname.is_null() || hub.is_monitor_only() {
        return call_original(pathname);
    }

    let path_text = unsafe { super::util::c_str_to_string(pathname) };
    if path_text.is_empty() {
        return call_original(pathname);
    }

    if !path_utils::is_relevant_storage_path(hub, &path_text) {
        diagnostic::record_fast_bypass(op_name, &path_text);
        return call_original(pathname);
    }

    diagnostic::log_diag_path_event(hub, op_name, "input", &path_text, -1);

    let redirect_result = process_redirect_path(hub, &path_text);
    diagnostic::log_diag_redirect_decision(hub, op_name, &path_text, &redirect_result);

    if redirect_result.is_redirect() {
        record_redirect_hit(hub, op_name, &path_text, &redirect_result.new_path);
        if let Ok(c_path) = CString::new(redirect_result.new_path) {
            return call_original(c_path.as_ptr());
        }
    }

    call_original(pathname)
}

fn create_parent_dirs_recursive(path: &str, mode: mode_t) {
    let parent = paths::parent(path);
    if parent.is_empty() || parent == "/" {
        return;
    }

    let Ok(c_parent) = CString::new(parent.clone()) else {
        return;
    };

    let mut st = std::mem::MaybeUninit::<libc::stat>::uninit();
    let ret = unsafe { libc::fstatat(AT_FDCWD, c_parent.as_ptr(), st.as_mut_ptr(), 0) };
    if ret == 0 {
        return;
    }

    create_parent_dirs_recursive(&parent, mode);

    let ret = unsafe { libc::mkdirat(AT_FDCWD, c_parent.as_ptr(), mode) };
    if ret == 0 {
        log::debug!("auto mkdir parent {}", parent);
        return;
    }

    let error_no = unsafe { *libc::__errno() };
    if error_no != libc::EEXIST {
        log::warn!("auto mkdir parent failed {} errno={}", parent, error_no);
    }
}
