use crate::config::{SettingsHub, watcher};
use crate::daemon_mount::{MountOperation, MountRequest, execute_mount_request, has_mount_state};
use crate::logging::Logger;
use crate::platform;
use crate::platform::paths::monotonic_ms;
use crate::redirect::policy;
use std::collections::HashSet;
use std::fs as std_fs;
use std::sync::atomic::{AtomicU64, Ordering};
use std::thread;
use std::time::Duration;

const RECONCILE_INTERVAL_MS: u64 = 1000;
const CONFIG_FINGERPRINT_FALLBACK_INTERVAL_MS: i64 = 10_000;
const SYSTEM_WRITER_RECONCILE_INTERVAL_MS: i64 = 5_000;
const INITIAL_RECONCILE_ROUNDS: usize = 3;
const ANDROID_APP_UID_START: i32 = 10000;
const UNINTERRUPTIBLE_SKIP_LOG_STEP: u64 = 32;
const MODULE_DISABLE_FILE: &str = "/data/adb/modules/storage.redirect.x/disable";

static UNINTERRUPTIBLE_SKIP_LOG_COUNT: AtomicU64 = AtomicU64::new(0);

pub fn main_entry() -> i32 {
    Logger::init(Some("srx_daemon"));
    log::info!("daemon start");

    if is_module_disabled() {
        log::info!("daemon exit reason=module_disabled");
        return 0;
    }

    let config = SettingsHub::instance();
    if !config.init(Some(crate::platform::module_paths::CONFIG_DIR)) {
        log::warn!("daemon config init failed");
        return 1;
    }
    policy::refresh_shared_uid_cache();
    let config_watch_fd = watcher::init(crate::platform::module_paths::CONFIG_DIR);
    if config_watch_fd < 0 {
        log::warn!("daemon config watcher unavailable, using fingerprint polling");
    }

    let mut last_version = 0;
    let mut last_fingerprint_check_ms = monotonic_ms();
    let mut last_system_writer_reconcile_ms = 0;
    let mut round: usize = 0;
    loop {
        if is_module_disabled() {
            log::info!("daemon stop reason=module_disabled");
            return 0;
        }

        let before = config.config_version();
        let did_reload = reload_config_for_daemon(config, &mut last_fingerprint_check_ms);
        let current = config.config_version();
        let now_ms = monotonic_ms();
        let should_full_reconcile = round < INITIAL_RECONCILE_ROUNDS
            || did_reload
            || current != last_version
            || current != before;
        let should_reconcile_system_writers = now_ms
            .saturating_sub(last_system_writer_reconcile_ms)
            >= SYSTEM_WRITER_RECONCILE_INTERVAL_MS;
        if should_full_reconcile || should_reconcile_system_writers {
            policy::refresh_shared_uid_cache();
            if should_full_reconcile {
                config.prepare_enabled_path_mapping_targets();
            }
            reconcile_running_apps(current, !should_full_reconcile);
            if should_full_reconcile {
                last_version = current;
            }
            if should_reconcile_system_writers {
                last_system_writer_reconcile_ms = now_ms;
            }
        }

        round = round.saturating_add(1);
        thread::sleep(Duration::from_millis(RECONCILE_INTERVAL_MS));
    }
}

fn is_module_disabled() -> bool {
    std_fs::metadata(MODULE_DISABLE_FILE).is_ok()
}

fn reload_config_for_daemon(config: &SettingsHub, last_fingerprint_check_ms: &mut i64) -> bool {
    if watcher::poll_changed() {
        *last_fingerprint_check_ms = monotonic_ms();
        let before = config.config_version();
        let _ = config.reload_force();
        return config.config_version() != before;
    }

    let now_ms = monotonic_ms();
    if now_ms.saturating_sub(*last_fingerprint_check_ms) < CONFIG_FINGERPRINT_FALLBACK_INTERVAL_MS {
        return false;
    }

    *last_fingerprint_check_ms = now_ms;
    let before = config.config_version();
    let _ = config.reload_if_changed();
    config.config_version() != before
}

fn reconcile_running_apps(config_version: u64, system_writers_only: bool) {
    let started_ms = monotonic_ms();
    let mut seen = HashSet::new();
    let mut applied = 0usize;
    let mut disabled = 0usize;
    let mut skipped = 0usize;
    let mut planned = 0usize;

    for proc in list_app_processes() {
        let key = format!("{}:{}", proc.pid, proc.package_name);
        if !seen.insert(key) {
            continue;
        }
        if should_skip_process(&proc) {
            skipped += 1;
            continue;
        }

        let needs_shared_config = should_ensure_shared_config_mount(&proc);
        if system_writers_only && !needs_shared_config {
            skipped += 1;
            continue;
        }

        let request = if needs_shared_config {
            build_shared_config_request(&proc, config_version)
        } else {
            build_request(&proc, config_version)
        };
        planned += 1;
        match request.operation {
            MountOperation::Reload => {
                if execute_mount_request(&request) {
                    applied += 1;
                }
            }
            MountOperation::Disable => {
                if has_mount_state(&request) && execute_mount_request(&request) {
                    disabled += 1;
                } else {
                    skipped += 1;
                }
            }
            MountOperation::EnsureSharedConfig => {
                if execute_mount_request(&request) {
                    applied += 1;
                } else {
                    skipped += 1;
                }
            }
        }
    }

    let scope = if system_writers_only {
        "system_writers"
    } else {
        "full"
    };
    log::info!(
        "daemon reconcile scope={} version={:x} planned={} applied={} disabled={} skipped={} ms={}",
        scope,
        config_version,
        planned,
        applied,
        disabled,
        skipped,
        monotonic_ms().saturating_sub(started_ms)
    );
}

fn should_ensure_shared_config_mount(proc: &AppProcess) -> bool {
    policy::is_system_writer_package(&proc.package_name) || policy::is_shared_uid_process(proc.uid)
}

fn build_shared_config_request(proc: &AppProcess, config_version: u64) -> MountRequest {
    MountRequest {
        operation: MountOperation::EnsureSharedConfig,
        pid: proc.pid,
        uid: proc.uid,
        package_name: proc.package_name.clone(),
        app_data_dir: String::new(),
        redirect_target: String::new(),
        allowed_real_paths: Vec::new(),
        path_mappings: Vec::new(),
        is_mapping_mode_only: true,
        config_version,
    }
}

fn build_request(proc: &AppProcess, config_version: u64) -> MountRequest {
    let config = SettingsHub::instance();
    let (
        operation,
        user_id,
        redirect_target,
        allowed_real_paths,
        path_mappings,
        is_mapping_mode_only,
    ) = match config.get_resolved_user_profile_snapshot(&proc.package_name, proc.uid) {
        Some(resolved) => (
            MountOperation::Reload,
            resolved.user_id,
            resolved.redirect_target,
            resolved.allowed_real_paths,
            resolved.path_mappings,
            resolved.is_mapping_mode_only,
        ),
        None => (
            MountOperation::Disable,
            platform::user_id_from_uid(proc.uid),
            String::new(),
            Vec::new(),
            Vec::new(),
            false,
        ),
    };

    MountRequest {
        operation,
        pid: proc.pid,
        uid: proc.uid,
        package_name: proc.package_name.clone(),
        app_data_dir: format!("/data/user/{}/{}", user_id, proc.package_name),
        redirect_target,
        allowed_real_paths,
        path_mappings,
        is_mapping_mode_only,
        config_version,
    }
}

fn should_skip_process(proc: &AppProcess) -> bool {
    if proc.pid <= 0 || proc.uid < ANDROID_APP_UID_START {
        return true;
    }
    if is_process_uninterruptible(proc.pid) {
        log_uninterruptible_skip(proc);
        return true;
    }
    if platform::is_isolated_uid(proc.uid) {
        return true;
    }
    false
}

#[derive(Clone)]
struct AppProcess {
    pid: i32,
    uid: i32,
    package_name: String,
}

fn list_app_processes() -> Vec<AppProcess> {
    let mut processes = Vec::new();
    let Ok(entries) = std_fs::read_dir("/proc") else {
        return processes;
    };

    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        if !name.chars().all(|ch| ch.is_ascii_digit()) {
            continue;
        }
        let Ok(pid) = name.parse::<i32>() else {
            continue;
        };
        let Some(package_name) = read_process_package(pid) else {
            continue;
        };
        let Some(uid) = read_process_uid(pid) else {
            continue;
        };
        processes.push(AppProcess {
            pid,
            uid,
            package_name,
        });
    }

    processes
}

fn read_process_package(pid: i32) -> Option<String> {
    let data = std_fs::read(format!("/proc/{}/cmdline", pid)).ok()?;
    let first = data.split(|ch| *ch == 0).next()?;
    let raw = std::str::from_utf8(first).ok()?.trim();
    if raw.is_empty() || raw.starts_with('/') || !raw.contains('.') {
        return None;
    }
    let package = raw.split(':').next().unwrap_or(raw).trim();
    if package.is_empty() || !package.contains('.') {
        return None;
    }
    Some(package.to_string())
}

fn read_process_uid(pid: i32) -> Option<i32> {
    let status = std_fs::read_to_string(format!("/proc/{}/status", pid)).ok()?;
    for line in status.lines() {
        let Some(rest) = line.strip_prefix("Uid:") else {
            continue;
        };
        let uid_text = rest.split_whitespace().next()?;
        return uid_text.parse::<i32>().ok();
    }
    None
}

fn is_process_uninterruptible(pid: i32) -> bool {
    let Ok(status) = std_fs::read_to_string(format!("/proc/{}/status", pid)) else {
        return false;
    };
    status
        .lines()
        .find_map(|line| line.strip_prefix("State:"))
        .map(|state| state.trim_start().starts_with('D'))
        .unwrap_or(false)
}

fn log_uninterruptible_skip(proc: &AppProcess) {
    let count = UNINTERRUPTIBLE_SKIP_LOG_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
    if count <= 8 || count.is_multiple_of(UNINTERRUPTIBLE_SKIP_LOG_STEP) {
        log::warn!(
            "daemon skip uninterruptible process pid={} pkg={} n={}",
            proc.pid,
            proc.package_name,
            count
        );
    }
}
