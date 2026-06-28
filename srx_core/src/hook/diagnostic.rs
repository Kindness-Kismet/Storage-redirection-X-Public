use super::monitor;
use super::path::{is_storage_path_fast, resolve_dirfd_path, resolve_path_for_dirfd};
use super::stats::InterceptHub;
use crate::platform::paths;
use crate::redirect::{RedirectAction, RedirectDecision};
use std::sync::atomic::{AtomicU64, Ordering};

const FAST_BYPASS_LOG_STEP: u64 = 2048;
const RELATIVE_BYPASS_LOG_STEP: u64 = 256;
const DIAG_LOG_STEP: u64 = 64;
const DIAG_IMPORTANT_LOG_STEP: u64 = 16;

static FAST_BYPASS_COUNT: AtomicU64 = AtomicU64::new(0);
static RELATIVE_BYPASS_COUNT: AtomicU64 = AtomicU64::new(0);
static DIAG_LOG_COUNT: AtomicU64 = AtomicU64::new(0);
static DIAG_IMPORTANT_LOG_COUNT: AtomicU64 = AtomicU64::new(0);

fn should_log_by_step(counter: &AtomicU64, step: u64) -> bool {
    let current = counter.fetch_add(1, Ordering::Relaxed) + 1;
    current == 1 || current.is_multiple_of(step)
}

fn should_log_diag_sample() -> bool {
    should_log_by_step(&DIAG_LOG_COUNT, DIAG_LOG_STEP)
}

// 写/unlink/rename 走更高频采样
fn should_log_diag_important_sample() -> bool {
    should_log_by_step(&DIAG_IMPORTANT_LOG_COUNT, DIAG_IMPORTANT_LOG_STEP)
}

pub fn log_diag_path_event(hub: &InterceptHub, op_name: &str, stage: &str, path: &str, flags: i32) {
    if path.is_empty() || !is_storage_path_fast(path) {
        return;
    }

    let is_important =
        monitor::has_write_intent_flags(flags) || op_name == "unlink" || op_name == "rename";
    if is_important {
        if !should_log_diag_important_sample() {
            return;
        }
    } else if !should_log_diag_sample() {
        return;
    }

    if flags >= 0 {
        log::debug!(
            "diag path stage={} pkg={} op={} path={} flags=0x{:x} mon={}",
            stage,
            hub.get_package_name(),
            op_name,
            path,
            flags,
            hub.is_monitor_only()
        );
    } else {
        log::debug!(
            "diag path stage={} pkg={} op={} path={} mon={}",
            stage,
            hub.get_package_name(),
            op_name,
            path,
            hub.is_monitor_only()
        );
    }
}

pub fn log_diag_redirect_decision(
    hub: &InterceptHub,
    op_name: &str,
    from_path: &str,
    redirect_result: &RedirectDecision,
) {
    let is_redirected = matches!(redirect_result.action, RedirectAction::Redirect);
    if !is_redirected && !should_log_diag_sample() {
        return;
    }

    if !from_path.is_empty()
        && !is_storage_path_fast(from_path)
        && !is_storage_path_fast(&redirect_result.new_path)
    {
        return;
    }

    let action_text = match redirect_result.action {
        RedirectAction::Redirect => "redirect",
        RedirectAction::Allow => "allow",
    };
    let target_path = if redirect_result.new_path.is_empty() {
        "<none>"
    } else {
        redirect_result.new_path.as_str()
    };
    let safe_from = if from_path.is_empty() {
        "<null>"
    } else {
        from_path
    };
    log::info!(
        "diag redirect pkg={} op={} action={} from={} to={}",
        hub.get_package_name(),
        op_name,
        action_text,
        safe_from,
        target_path
    );
}

pub fn log_diag_rename_decision(
    hub: &InterceptHub,
    oldpath: &str,
    newpath: &str,
    final_oldpath: &str,
    final_newpath: &str,
) {
    let is_old_changed = !oldpath.is_empty() && oldpath != final_oldpath;
    let is_new_changed = !newpath.is_empty() && newpath != final_newpath;
    if !is_old_changed && !is_new_changed && !should_log_diag_sample() {
        return;
    }

    log::info!(
        "diag rename pkg={} old={} new={} final_old={} final_new={} mon={}",
        hub.get_package_name(),
        if oldpath.is_empty() {
            "<null>"
        } else {
            oldpath
        },
        if newpath.is_empty() {
            "<null>"
        } else {
            newpath
        },
        if final_oldpath.is_empty() {
            "<null>"
        } else {
            final_oldpath
        },
        if final_newpath.is_empty() {
            "<null>"
        } else {
            final_newpath
        },
        hub.is_monitor_only()
    );
}

pub fn record_fast_bypass(op_name: &str, pathname: &str) {
    let current = FAST_BYPASS_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
    if current == 1 || current.is_multiple_of(FAST_BYPASS_LOG_STEP) {
        let normalized = paths::normalize(pathname);
        let is_absolute = pathname.starts_with('/');
        let is_storage =
            !normalized.is_empty() && paths::starts_with(&normalized, "/storage/emulated/");
        log::debug!(
            "diag fast-bypass op={} n={} raw={} norm={} abs={} storage={}",
            op_name,
            current,
            if pathname.is_empty() {
                "<null>"
            } else {
                pathname
            },
            if normalized.is_empty() {
                "<empty>"
            } else {
                normalized.as_str()
            },
            is_absolute,
            is_storage
        );
    }
}

pub fn log_relative_path_bypass(
    hub: &InterceptHub,
    op_name: &str,
    dirfd: i32,
    pathname: &str,
    flags: i32,
) {
    if pathname.is_empty() || pathname.starts_with('/') {
        return;
    }

    let current = RELATIVE_BYPASS_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
    if !(current == 1 || current.is_multiple_of(RELATIVE_BYPASS_LOG_STEP)) {
        return;
    }

    let dirfd_path = resolve_dirfd_path(dirfd);
    let resolved_path = resolve_path_for_dirfd(dirfd, pathname);
    let is_storage =
        !resolved_path.is_empty() && paths::starts_with(&resolved_path, "/storage/emulated/");

    if flags >= 0 {
        log::info!(
            "diag rel-bypass pkg={} op={} n={} dirfd={} base={} rel={} resolved={} storage={} flags=0x{:x} mon={}",
            hub.get_package_name(),
            op_name,
            current,
            dirfd,
            if dirfd_path.is_empty() {
                "<empty>"
            } else {
                dirfd_path.as_str()
            },
            pathname,
            if resolved_path.is_empty() {
                "<empty>"
            } else {
                resolved_path.as_str()
            },
            is_storage,
            flags,
            hub.is_monitor_only()
        );
    } else {
        log::info!(
            "diag rel-bypass pkg={} op={} n={} dirfd={} base={} rel={} resolved={} storage={} mon={}",
            hub.get_package_name(),
            op_name,
            current,
            dirfd,
            if dirfd_path.is_empty() {
                "<empty>"
            } else {
                dirfd_path.as_str()
            },
            pathname,
            if resolved_path.is_empty() {
                "<empty>"
            } else {
                resolved_path.as_str()
            },
            is_storage,
            hub.is_monitor_only()
        );
    }
}
