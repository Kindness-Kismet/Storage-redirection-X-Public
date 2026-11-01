// 应用 specialize 后流程：读取挂载结果并安装 PLT Hook
use super::RuntimeFlow;
use crate::hook::InterceptHub;
use crate::platform;
use crate::platform::paths::monotonic_ms;
use crate::platform::unique_fd::UniqueFd;
use crate::zygisk::abi;
use std::sync::atomic::{AtomicBool, Ordering};

static PLT_HOOK_INSTALLED: AtomicBool = AtomicBool::new(false);
// 限制应用启动阶段的挂载等待，超时后记录失败并继续启动。
const MOUNT_RESULT_TIMEOUT_SEC: u64 = 3;
const POST_SPECIALIZE_SLOW_MS: i64 = 20;

impl RuntimeFlow {
    pub fn post_app_specialize(&mut self, _args: *const abi::AppSpecializeArgs) {
        let mount_socket = self.companion_socket.take();
        let perf_started_ms = monotonic_ms();
        if self.should_install_fuse_fixer {
            crate::hook::install_fuse_fixer_hook();
        }

        if self.should_skip_post_work {
            log_post_perf(self, "skip", 0, 0, perf_started_ms);
            return;
        }

        if !self.should_redirect && !self.should_monitor {
            log_post_perf(self, "bypass", 0, 0, perf_started_ms);
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
                self.is_mount_applied = read_mount_result(mount_socket.as_ref());
                mount_wait_ms = monotonic_ms().saturating_sub(mount_started_ms);
            }
        } else if self.should_redirect && self.is_system_writer_hook_redirect {
            self.is_mount_applied = false;
            log::info!("writer per-caller hook map (skip mount wait)");
        }

        let hook_started_ms = monotonic_ms();
        if self.is_system_writer_hook_redirect {
            install_plt_hook(&self.package_name, true);
        } else {
            self.request_dlclose();
        }
        let hook_ms = monotonic_ms().saturating_sub(hook_started_ms);

        log_post_perf(self, "done", mount_wait_ms, hook_ms, perf_started_ms);
    }
}

fn read_mount_result(socket: Option<&UniqueFd>) -> bool {
    let Some(socket) = socket else {
        log::warn!("mount req not sent, skip wait");
        return false;
    };
    match super::mount_result::read_result(
        socket.get(),
        std::time::Duration::from_secs(MOUNT_RESULT_TIMEOUT_SEC),
    ) {
        Ok(is_applied) => {
            log::info!("mount result applied={}", is_applied);
            is_applied
        }
        Err(error) => {
            log::warn!(
                "mount result unavailable fd={} error={}",
                socket.get(),
                error
            );
            false
        }
    }
}

fn log_post_perf(
    flow: &RuntimeFlow,
    exit_reason: &str,
    mount_wait_ms: i64,
    hook_ms: i64,
    started_ms: i64,
) {
    let total_ms = monotonic_ms().saturating_sub(started_ms);
    if total_ms < POST_SPECIALIZE_SLOW_MS && !flow.should_redirect && !flow.should_monitor {
        return;
    }
    log::info!(
        "perf post pkg={} pid={} exit={} redirect={} monitor={} hook_redirect={} mount_sent={} mount_applied={} mount_wait_ms={} hook_ms={} total_ms={}",
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
        total_ms
    );
}

fn install_plt_hook(package_name: &str, is_redirect_via_hook: bool) {
    if !is_redirect_via_hook {
        log::info!("plt hook skip");
        return;
    }

    if PLT_HOOK_INSTALLED.swap(true, Ordering::AcqRel) {
        log::info!("plt hook already installed");
        return;
    }

    log::info!("plt hook install redirect=true");

    let hub = InterceptHub::instance();
    hub.init(package_name);
    if hub.install() {
        log::info!("plt hook ok");
    } else {
        PLT_HOOK_INSTALLED.store(false, Ordering::Release);
        log::warn!("plt hook failed");
    }
}
