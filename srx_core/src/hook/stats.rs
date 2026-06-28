use super::context;
use super::entries::{HookProfile, build_hook_entries, count_hooks_for_profile, is_hook_enabled};
use crate::platform::paths::monotonic_ms;
use crate::redirect::policy;
use std::sync::RwLock;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicI64, AtomicU64, Ordering};

const STATS_TAG: &str = "Stats";
const SELF_MODULE_PATH: &str = "libsrx_core.so";
const FLUSH_THRESHOLD: i32 = 32;
const FLUSH_INTERVAL_MS: i64 = 2000;

pub struct InterceptHub {
    package_name: RwLock<String>,
    is_initialized: AtomicBool,
    is_hooks_installed: AtomicBool,
    is_monitor_only: AtomicBool,
    is_monitor_enabled: AtomicBool,
    stats: AtomicStats,
    pending_redirect_count: AtomicI32,
    last_flush_ms: AtomicI64,
}

#[derive(Default)]
struct AtomicStats {
    open_calls: AtomicU64,
    openat_calls: AtomicU64,
    stat_calls: AtomicU64,
    access_calls: AtomicU64,
    mkdir_calls: AtomicU64,
    unlink_calls: AtomicU64,
    rename_calls: AtomicU64,
    opendir_calls: AtomicU64,
    readlink_calls: AtomicU64,
    total_redirected: AtomicU64,
}

impl AtomicStats {
    const fn new() -> Self {
        Self {
            open_calls: AtomicU64::new(0),
            openat_calls: AtomicU64::new(0),
            stat_calls: AtomicU64::new(0),
            access_calls: AtomicU64::new(0),
            mkdir_calls: AtomicU64::new(0),
            unlink_calls: AtomicU64::new(0),
            rename_calls: AtomicU64::new(0),
            opendir_calls: AtomicU64::new(0),
            readlink_calls: AtomicU64::new(0),
            total_redirected: AtomicU64::new(0),
        }
    }
}

impl InterceptHub {
    pub fn instance() -> &'static InterceptHub {
        &INTERCEPT_HUB
    }

    pub fn init(
        &self,
        package_name: &str,
        is_monitor_only: bool,
        is_monitor_enabled: bool,
    ) -> bool {
        if self.is_initialized.load(Ordering::Relaxed) {
            return true;
        }

        if !package_name.is_empty() {
            let mut name = self
                .package_name
                .write()
                .unwrap_or_else(|err| err.into_inner());
            *name = package_name.to_string();
        }

        self.is_monitor_only
            .store(is_monitor_only, Ordering::Relaxed);
        self.is_monitor_enabled
            .store(is_monitor_enabled, Ordering::Relaxed);

        log::info!(
            "hook manager init pkg={} redirect={} monitor={}",
            self.get_package_name(),
            !is_monitor_only,
            is_monitor_enabled
        );

        self.is_initialized.store(true, Ordering::Relaxed);
        true
    }

    pub fn is_monitor_only(&self) -> bool {
        self.is_monitor_only.load(Ordering::Relaxed)
    }

    pub fn is_monitor_enabled(&self) -> bool {
        self.is_monitor_enabled.load(Ordering::Relaxed)
    }

    pub fn is_redirect_enabled(&self) -> bool {
        !self.is_monitor_only()
    }

    pub fn get_package_name(&self) -> String {
        self.package_name
            .read()
            .unwrap_or_else(|err| err.into_inner())
            .clone()
    }

    pub fn set_current_caller_package(&self, caller_package: &str) {
        context::set_current_caller_package(caller_package);
    }

    pub fn get_current_caller_package(&self) -> String {
        context::get_current_caller_package()
    }

    pub fn set_current_caller_uid(&self, caller_uid: i32) {
        context::set_current_caller_uid(caller_uid);
    }

    pub fn get_current_caller_uid(&self) -> i32 {
        context::get_current_caller_uid()
    }

    pub fn clear_current_caller(&self) {
        context::clear_current_caller();
    }

    // 由 FUSE 请求头填入，用于跨进程调用方识别
    pub fn get_fuse_caller_uid(&self) -> i32 {
        context::get_fuse_caller_uid()
    }

    pub fn is_hooks_installed(&self) -> bool {
        self.is_hooks_installed.load(Ordering::Relaxed)
    }

    pub fn install(&self) -> bool {
        if self.is_hooks_installed() {
            log::warn!("hook already installed");
            return true;
        }

        log::info!("hook install start");
        let errno = srx_hook::init(srx_hook::HookMode::Automatic, false);
        if !errno.is_ok() {
            log::warn!("srx_hook init failed err={:?}", errno);
            return false;
        }

        let ignore_errno = srx_hook::add_ignore(SELF_MODULE_PATH);
        if !ignore_errno.is_ok() {
            log::warn!("add_ignore failed err={:?}", ignore_errno);
        }

        let is_system_writer = policy::is_system_writer_package(&self.get_package_name());
        let (profile_mask, profile_name) =
            select_hook_profile(is_system_writer, self.is_monitor_only());

        let selected_hook_count = count_hooks_for_profile(profile_mask);
        log::info!(
            "hook profile={} count={}",
            profile_name,
            selected_hook_count
        );

        let entries = build_hook_entries();
        let mut hook_list: Vec<&'static str> = Vec::new();
        let mut is_success = true;

        for entry in entries {
            if !is_hook_enabled(profile_mask, entry.profile_mask) {
                continue;
            }

            let stub = srx_hook::hook_all(
                None,
                entry.symbol,
                entry.new_func,
                None,
                std::ptr::null_mut(),
            );

            if stub.is_none() {
                if entry.is_optional {
                    log::warn!("optional hook unavailable sym={}", entry.symbol);
                    continue;
                }
                log::warn!("hook register failed sym={}", entry.symbol);
                is_success = false;
                continue;
            }

            hook_list.push(entry.symbol);
        }

        if !is_success {
            log::warn!("some hooks failed to register");
            return false;
        }

        let hook_list_text = hook_list.join(", ");
        log::info!(
            "hook registered count={} list={}",
            hook_list.len(),
            hook_list_text
        );

        log::info!("hook refresh");
        let (refresh_errno, refresh_errors) = srx_hook::refresh();
        for err in &refresh_errors {
            log::warn!(
                "module resolve failed path={} err={:?}",
                err.module_path,
                err.errno
            );
        }
        if !refresh_errno.is_ok() {
            log::warn!("hook refresh failed err={:?}", refresh_errno);
            return false;
        }

        self.is_hooks_installed.store(true, Ordering::Relaxed);
        log::info!("hook install done");
        true
    }

    // 按阈值或时间双触发刷盘，避免频繁写入
    pub fn increment_global_redirect_count(&self) {
        let pending = self.pending_redirect_count.fetch_add(1, Ordering::Relaxed) + 1;
        if pending >= FLUSH_THRESHOLD {
            self.flush_to_global_stats();
            return;
        }

        let now_ms = monotonic_ms();
        let last_flush = self.last_flush_ms.load(Ordering::Relaxed);
        if now_ms - last_flush >= FLUSH_INTERVAL_MS {
            self.flush_to_global_stats();
        }
    }

    pub fn increment_open_calls(&self) {
        self.stats.open_calls.fetch_add(1, Ordering::Relaxed);
    }

    pub fn increment_openat_calls(&self) {
        self.stats.openat_calls.fetch_add(1, Ordering::Relaxed);
    }

    pub fn increment_stat_calls(&self) {
        self.stats.stat_calls.fetch_add(1, Ordering::Relaxed);
    }

    pub fn increment_access_calls(&self) {
        self.stats.access_calls.fetch_add(1, Ordering::Relaxed);
    }

    pub fn increment_mkdir_calls(&self) {
        self.stats.mkdir_calls.fetch_add(1, Ordering::Relaxed);
    }

    pub fn increment_unlink_calls(&self) {
        self.stats.unlink_calls.fetch_add(1, Ordering::Relaxed);
    }

    pub fn increment_rename_calls(&self) {
        self.stats.rename_calls.fetch_add(1, Ordering::Relaxed);
    }

    pub fn increment_opendir_calls(&self) {
        self.stats.opendir_calls.fetch_add(1, Ordering::Relaxed);
    }

    pub fn increment_readlink_calls(&self) {
        self.stats.readlink_calls.fetch_add(1, Ordering::Relaxed);
    }

    pub fn increment_total_redirected(&self) {
        self.stats.total_redirected.fetch_add(1, Ordering::Relaxed);
    }

    // 通过 logcat 广播，由外部收集器汇总
    fn flush_to_global_stats(&self) {
        let pending = self.pending_redirect_count.swap(0, Ordering::Relaxed);
        if pending <= 0 {
            return;
        }

        self.last_flush_ms.store(monotonic_ms(), Ordering::Relaxed);

        // 收集器按 "+N" 解析
        log::info!(target: STATS_TAG, "+{}", pending);
    }
}

fn select_hook_profile(is_system_writer: bool, is_monitor_only: bool) -> (u32, &'static str) {
    if is_system_writer {
        if is_monitor_only {
            return (HookProfile::SystemWriter as u32, "system-writer-monitor");
        }
        return (HookProfile::SystemWriter as u32, "system-writer");
    }
    if is_monitor_only {
        return (HookProfile::Monitor as u32, "monitor");
    }
    (HookProfile::Full as u32, "full")
}

static INTERCEPT_HUB: InterceptHub = InterceptHub {
    package_name: RwLock::new(String::new()),
    is_initialized: AtomicBool::new(false),
    is_hooks_installed: AtomicBool::new(false),
    is_monitor_only: AtomicBool::new(false),
    is_monitor_enabled: AtomicBool::new(false),
    stats: AtomicStats::new(),
    pending_redirect_count: AtomicI32::new(0),
    last_flush_ms: AtomicI64::new(0),
};
