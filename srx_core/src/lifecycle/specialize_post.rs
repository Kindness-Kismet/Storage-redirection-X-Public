// 应用 specialize 后流程：等待挂载状态并安装 PLT Hook
use super::RuntimeFlow;
use crate::hook::InterceptHub;
use crate::platform::paths::monotonic_ms;
use crate::platform::unique_fd::UniqueFd;
use crate::platform::{self, anti_detect};
use crate::zygisk::abi;
use libc::{O_CLOEXEC, O_RDONLY, open, read, unlink};
use std::sync::atomic::{AtomicBool, Ordering};

static PLT_HOOK_INSTALLED: AtomicBool = AtomicBool::new(false);
const MOUNT_STATUS_POLL_COUNT: i32 = 60;
const MOUNT_STATUS_POLL_DELAY_US: u32 = 50 * 1000;
const POST_SPECIALIZE_SLOW_MS: i64 = 20;

impl RuntimeFlow {
    pub fn post_app_specialize(&mut self, _args: *const abi::AppSpecializeArgs) {
        let perf_started_ms = monotonic_ms();
        if self.should_install_fuse_fixer {
            crate::hook::install_fuse_fixer_hook();
        }

        if self.should_skip_post_work {
            log_post_perf(self, "skip", 0, 0, 0, perf_started_ms);
            return;
        }

        if !self.should_redirect && !self.should_monitor {
            let anti_started_ms = monotonic_ms();
            // 无需重定向或监控的应用仍需命名匿名可执行区域
            let named_count = anti_detect::name_anonymous_executable_regions();
            let anti_ms = monotonic_ms().saturating_sub(anti_started_ms);
            if named_count > 0 {
                log::info!("anon regions named n={}", named_count);
            }
            log_post_perf(self, "bypass", 0, 0, anti_ms, perf_started_ms);
            return;
        }

        let mut mount_wait_ms = 0;
        if self.should_redirect && !self.is_system_writer_hook_redirect {
            if platform::is_isolated_uid(self.app_uid) {
                self.is_mount_applied = false;
                log::info!(
                    "isolated uid skip mount wait uid={} pid={}",
                    self.app_uid,
                    self.app_pid
                );
            } else {
                let mount_started_ms = monotonic_ms();
                wait_for_mount_status(
                    &self.app_data_dir,
                    self.app_pid,
                    self.is_mount_request_sent,
                    &mut self.is_mount_applied,
                );
                mount_wait_ms = monotonic_ms().saturating_sub(mount_started_ms);
            }
        } else if self.should_redirect && self.is_system_writer_hook_redirect {
            self.is_mount_applied = false;
            log::info!("writer per-caller hook map (skip marker wait)");
        }

        let hook_started_ms = monotonic_ms();
        let is_redirect_via_hook = self.should_redirect && self.is_system_writer_hook_redirect;
        install_plt_hook(
            &self.package_name,
            self.should_monitor,
            is_redirect_via_hook,
        );
        let hook_ms = monotonic_ms().saturating_sub(hook_started_ms);

        let anti_started_ms = monotonic_ms();
        // Hook 安装后命名匿名可执行区域，覆盖模块代码和 hook trampoline
        let named_count = anti_detect::name_anonymous_executable_regions();
        let anti_ms = monotonic_ms().saturating_sub(anti_started_ms);
        if named_count > 0 {
            log::info!("anon regions named n={}", named_count);
        }
        log_post_perf(
            self,
            "done",
            mount_wait_ms,
            hook_ms,
            anti_ms,
            perf_started_ms,
        );
    }
}

// 轮询读取挂载状态标记文件，确认挂载是否成功
fn wait_for_mount_status(
    app_data_dir: &str,
    app_pid: i32,
    is_mount_request_sent: bool,
    is_mount_applied_out: &mut bool,
) {
    *is_mount_applied_out = false;
    let mut last_errno_code = 0;

    if !is_mount_request_sent {
        log::warn!("mount req not sent, skip wait");
        return;
    }
    if app_data_dir.is_empty() || app_pid <= 0 {
        log::warn!("mount ctx invalid, skip wait");
        return;
    }

    let marker_path = format!("{}/.srx_mount_status_{}", app_data_dir, app_pid);
    log::info!("wait marker {}", marker_path);

    let Ok(c_path) = std::ffi::CString::new(marker_path.clone()) else {
        return;
    };

    let mut poll_count = 0;
    for _ in 0..MOUNT_STATUS_POLL_COUNT {
        poll_count += 1;
        let fd = unsafe { open(c_path.as_ptr(), O_RDONLY | O_CLOEXEC) };
        if fd >= 0 {
            let file = UniqueFd::new(fd);
            let mut ch = [0u8; 1];
            let n = unsafe { read(file.get(), ch.as_mut_ptr() as *mut _, 1) };
            if n == 1 {
                unsafe { unlink(c_path.as_ptr()) };
                *is_mount_applied_out = ch[0] == b'1';
                log::info!(
                    "marker read n={} val={} applied={} polls={}",
                    n,
                    ch[0] as char,
                    *is_mount_applied_out,
                    poll_count
                );
                break;
            }
        } else {
            last_errno_code = unsafe { *libc::__errno() };
        }
        unsafe { libc::usleep(MOUNT_STATUS_POLL_DELAY_US) };
    }

    if *is_mount_applied_out {
        log::info!("app mount confirmed pid={}", app_pid);
    } else {
        log::warn!(
            "mount unknown/failed pid={} marker={} polls={} errno={}",
            app_pid,
            marker_path,
            poll_count,
            last_errno_code
        );
    }
}

fn log_post_perf(
    flow: &RuntimeFlow,
    exit_reason: &str,
    mount_wait_ms: i64,
    hook_ms: i64,
    anti_ms: i64,
    started_ms: i64,
) {
    let total_ms = monotonic_ms().saturating_sub(started_ms);
    if total_ms < POST_SPECIALIZE_SLOW_MS && !flow.should_redirect && !flow.should_monitor {
        return;
    }
    log::info!(
        "perf post pkg={} pid={} exit={} redirect={} monitor={} hook_redirect={} mount_sent={} mount_applied={} mount_wait_ms={} hook_ms={} anti_ms={} total_ms={}",
        flow.package_name,
        flow.app_pid,
        exit_reason,
        flow.should_redirect,
        flow.should_monitor,
        flow.is_system_writer_hook_redirect,
        flow.is_mount_request_sent,
        flow.is_mount_applied,
        mount_wait_ms,
        hook_ms,
        anti_ms,
        total_ms
    );
}

fn install_plt_hook(package_name: &str, should_monitor: bool, is_redirect_via_hook: bool) {
    let is_monitor_only = !is_redirect_via_hook;
    let should_install = should_monitor || is_redirect_via_hook;
    if !should_install {
        log::info!("plt hook skip");
        return;
    }

    if PLT_HOOK_INSTALLED.swap(true, Ordering::AcqRel) {
        log::info!("plt hook already installed");
        return;
    }

    log::info!(
        "plt hook install redirect={} monitor={}",
        !is_monitor_only,
        should_monitor
    );

    let hub = InterceptHub::instance();
    hub.init(package_name, is_monitor_only, should_monitor);
    if hub.install() {
        log::info!("plt hook ok");
    } else {
        PLT_HOOK_INSTALLED.store(false, Ordering::Release);
        log::warn!("plt hook failed");
    }
}
