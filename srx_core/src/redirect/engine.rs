// 重定向决策引擎：统一处理普通应用与系统代写进程的路径重定向
use super::policy;
use super::router::{PathRouter, RedirectAction, RedirectDecision};
use super::thumbnail_diag::{self, ThumbnailDecisionDiag};
use super::writer;
use crate::config::{RawUserEnabledState, SettingsHub};
use crate::hook::stats::InterceptHub;
use crate::platform::paths;
use std::sync::atomic::{AtomicU64, Ordering};

const WRITER_ALLOWED_LOG_STEP: u64 = 256;
const WRITER_DEFAULT_REDIRECT_LOG_STEP: u64 = 4096;
const WRITER_REDIRECT_PHASE_SLOW_MS: i64 = 10;
const WRITER_REDIRECT_TOTAL_SLOW_MS: i64 = 100;
const REDIRECT_SLOW_MS: i64 = 10;
const REDIRECT_SAMPLE_STEP: u64 = 8192;
static WRITER_ALLOWED_LOG_COUNT: AtomicU64 = AtomicU64::new(0);
static WRITER_DEFAULT_REDIRECT_COUNT: AtomicU64 = AtomicU64::new(0);
static REDIRECT_DECISION_COUNT: AtomicU64 = AtomicU64::new(0);

#[inline]
fn should_log_step(count: u64, step: u64) -> bool {
    count == 1 || count.is_multiple_of(step)
}

// 根据进程身份决策路径重定向，系统代写进程使用按调用方映射
pub fn process_redirect_path(hub: &InterceptHub, pathname: &str) -> RedirectDecision {
    let perf_started_ms = paths::monotonic_ms();
    let self_uid = unsafe { libc::getuid() as i32 };
    let package_name = hub.get_package_name();
    let is_shared_uid = policy::is_shared_uid_process(self_uid);
    let is_system_writer_process = policy::is_system_writer_package(&package_name) || is_shared_uid;
    if !is_system_writer_process {
        let decision = PathRouter::instance().process_path(pathname);
        log_redirect_perf(
            "app",
            &package_name,
            "router",
            pathname,
            "",
            perf_started_ms,
            &decision,
        );
        return decision;
    }

    let reload_started_ms = paths::monotonic_ms();
    crate::hook::refresh_runtime_config_throttled();
    let reload_ms = paths::monotonic_ms().saturating_sub(reload_started_ms);

    if pathname.is_empty() {
        let decision = RedirectDecision {
            action: RedirectAction::Allow,
            new_path: String::new(),
        };
        log_writer_redirect_perf(&WriterRedirectPerf {
            package_name: &package_name,
            exit_reason: "empty_path",
            caller_package: "",
            path: pathname,
            normalized_path: "",
            reload_ms,
            caller_ms: 0,
            enable_ms: 0,
            mapping_ms: 0,
            allow_ms: 0,
            fallback_ms: 0,
            total_ms: paths::monotonic_ms().saturating_sub(perf_started_ms),
            decision: &decision,
        });
        return decision;
    }

    let raw_normalized = paths::normalize(pathname);
    let is_data_media = paths::starts_with(&raw_normalized, "/data/media/");
    let normalized_path = if is_data_media {
        writer::data_media_to_storage_path(&raw_normalized)
    } else {
        raw_normalized
    };

    let caller_started_ms = paths::monotonic_ms();
    let mut effective_caller_uid = hub.get_current_caller_uid();
    let original_caller_uid = effective_caller_uid;
    let mut effective_caller_package = hub.get_current_caller_package();

    let user_id =
        writer::resolve_system_writer_user_id(&normalized_path, &mut effective_caller_uid);
    let mut is_caller_from_inferred = false;
    if original_caller_uid >= writer::ANDROID_APP_UID_START {
        writer::maybe_override_system_writer_caller_by_path(
            &normalized_path,
            &mut effective_caller_uid,
            user_id,
            &mut effective_caller_package,
            &mut is_caller_from_inferred,
        );
    } else if effective_caller_package.is_empty() {
        writer::log_system_writer_skip_path_infer_for_low_uid(
            original_caller_uid,
            &normalized_path,
        );
    }

    // Java 栈帧回退：共享 UID 进程内部创建时，按调用栈识别具体组件
    if effective_caller_package.is_empty()
        && is_shared_uid
        && !is_already_private_path(&normalized_path)
    {
        let candidates: Vec<String> = policy::get_packages_for_uid(self_uid)
            .into_iter()
            .filter(|pkg| !policy::is_system_writer_package(pkg))
            .collect();
        if let Some(pkg) = crate::monitor::infer_caller_package_by_stack(&candidates) {
            let inferred_uid = policy::get_uid_for_package(&pkg);
            if inferred_uid >= writer::ANDROID_APP_UID_START {
                effective_caller_uid = inferred_uid;
            }
            log::debug!(
                "writer: stack infer caller={} uid={} path={}",
                pkg,
                effective_caller_uid,
                normalized_path
            );
            effective_caller_package = pkg;
            is_caller_from_inferred = true;
        }
    }

    let caller_ms = paths::monotonic_ms().saturating_sub(caller_started_ms);
    if effective_caller_package.is_empty() {
        writer::log_system_writer_caller_unresolved(&package_name, effective_caller_uid, pathname);
        let decision = RedirectDecision {
            action: RedirectAction::Allow,
            new_path: String::new(),
        };
        log_writer_redirect_perf(&WriterRedirectPerf {
            package_name: &package_name,
            exit_reason: "caller_empty",
            caller_package: &effective_caller_package,
            path: pathname,
            normalized_path: &normalized_path,
            reload_ms,
            caller_ms,
            enable_ms: 0,
            mapping_ms: 0,
            allow_ms: 0,
            fallback_ms: 0,
            total_ms: paths::monotonic_ms().saturating_sub(perf_started_ms),
            decision: &decision,
        });
        return decision;
    }

    if user_id < 0 {
        writer::log_system_writer_user_unresolved(
            &effective_caller_package,
            effective_caller_uid,
            pathname,
        );
        let decision = RedirectDecision {
            action: RedirectAction::Allow,
            new_path: String::new(),
        };
        log_writer_redirect_perf(&WriterRedirectPerf {
            package_name: &package_name,
            exit_reason: "user_empty",
            caller_package: &effective_caller_package,
            path: pathname,
            normalized_path: &normalized_path,
            reload_ms,
            caller_ms,
            enable_ms: 0,
            mapping_ms: 0,
            allow_ms: 0,
            fallback_ms: 0,
            total_ms: paths::monotonic_ms().saturating_sub(perf_started_ms),
            decision: &decision,
        });
        return decision;
    }

    let enable_started_ms = paths::monotonic_ms();
    let config = SettingsHub::instance();
    let enabled_in_memory = config.should_redirect(&effective_caller_package, effective_caller_uid);
    let raw_enabled_state =
        config.get_user_enabled_in_raw_config(&effective_caller_package, user_id);
    let enabled_in_raw = raw_enabled_state == RawUserEnabledState::Enabled;
    let enable_ms = paths::monotonic_ms().saturating_sub(enable_started_ms);
    if raw_enabled_state == RawUserEnabledState::Disabled || (!enabled_in_memory && !enabled_in_raw)
    {
        writer::log_system_writer_redirect_disabled(
            &effective_caller_package,
            effective_caller_uid,
            pathname,
        );
        let decision = RedirectDecision {
            action: RedirectAction::Allow,
            new_path: String::new(),
        };
        log_writer_redirect_perf(&WriterRedirectPerf {
            package_name: &package_name,
            exit_reason: "disabled",
            caller_package: &effective_caller_package,
            path: pathname,
            normalized_path: &normalized_path,
            reload_ms,
            caller_ms,
            enable_ms,
            mapping_ms: 0,
            allow_ms: 0,
            fallback_ms: 0,
            total_ms: paths::monotonic_ms().saturating_sub(perf_started_ms),
            decision: &decision,
        });
        return decision;
    }

    let resolved_path = paths::resolve_user_path(&normalized_path, user_id);
    if !writer::is_path_in_user_storage(&resolved_path, user_id) {
        let decision = RedirectDecision {
            action: RedirectAction::Allow,
            new_path: String::new(),
        };
        log_writer_redirect_perf(&WriterRedirectPerf {
            package_name: &package_name,
            exit_reason: "outside_storage",
            caller_package: &effective_caller_package,
            path: pathname,
            normalized_path: &normalized_path,
            reload_ms,
            caller_ms,
            enable_ms,
            mapping_ms: 0,
            allow_ms: 0,
            fallback_ms: 0,
            total_ms: paths::monotonic_ms().saturating_sub(perf_started_ms),
            decision: &decision,
        });
        return decision;
    }

    let log_thumbnail_decision = |exit_reason: &str, decision: &RedirectDecision| {
        thumbnail_diag::log_system_writer_decision(&ThumbnailDecisionDiag {
            proc_package: &package_name,
            caller_package: &effective_caller_package,
            caller_uid: effective_caller_uid,
            user_id,
            resolved_path: &resolved_path,
            enabled_in_memory,
            enabled_in_raw,
            is_caller_from_inferred,
            exit_reason,
            decision,
        });
    };

    let mapping_started_ms = paths::monotonic_ms();
    let caller_mappings =
        writer::get_caller_mappings(&effective_caller_package, effective_caller_uid);
    let mapped_path = writer::map_path_by_caller_mappings(&resolved_path, &caller_mappings);
    let mapping_ms = paths::monotonic_ms().saturating_sub(mapping_started_ms);
    if !mapped_path.is_empty() && mapped_path != resolved_path {
        let new_path = if is_data_media {
            writer::storage_to_data_media_path(&mapped_path)
        } else {
            mapped_path
        };
        log::debug!(
            "writer: caller map caller={} uid={} from={} to={}",
            effective_caller_package,
            effective_caller_uid,
            resolved_path,
            new_path
        );
        let decision = RedirectDecision {
            action: RedirectAction::Redirect,
            new_path,
        };
        log_writer_redirect_perf(&WriterRedirectPerf {
            package_name: &package_name,
            exit_reason: "mapping",
            caller_package: &effective_caller_package,
            path: pathname,
            normalized_path: &normalized_path,
            reload_ms,
            caller_ms,
            enable_ms,
            mapping_ms,
            allow_ms: 0,
            fallback_ms: 0,
            total_ms: paths::monotonic_ms().saturating_sub(perf_started_ms),
            decision: &decision,
        });
        log_thumbnail_decision("mapping", &decision);
        return decision;
    }

    for mapping in &caller_mappings {
        if resolved_path == mapping.final_path
            || paths::starts_with(&resolved_path, &format!("{}/", mapping.final_path))
        {
            log::debug!(
                "writer: map target allow caller={} uid={} path={}",
                effective_caller_package,
                effective_caller_uid,
                resolved_path
            );
            let decision = RedirectDecision {
                action: RedirectAction::Allow,
                new_path: String::new(),
            };
            log_writer_redirect_perf(&WriterRedirectPerf {
                package_name: &package_name,
                exit_reason: "mapping_target",
                caller_package: &effective_caller_package,
                path: pathname,
                normalized_path: &normalized_path,
                reload_ms,
                caller_ms,
                enable_ms,
                mapping_ms,
                allow_ms: 0,
                fallback_ms: 0,
                total_ms: paths::monotonic_ms().saturating_sub(perf_started_ms),
                decision: &decision,
            });
            log_thumbnail_decision("mapping_target", &decision);
            return decision;
        }
    }

    let allow_started_ms = paths::monotonic_ms();
    match writer::classify_path_by_caller_real_paths(
        &resolved_path,
        &effective_caller_package,
        effective_caller_uid,
    ) {
        writer::CallerRealPathMatch::Excluded => {
            log::debug!(
                "writer: excl hit caller={} uid={} path={}",
                effective_caller_package,
                effective_caller_uid,
                resolved_path
            );
        }
        writer::CallerRealPathMatch::Allowed => {
            let allow_ms = paths::monotonic_ms().saturating_sub(allow_started_ms);
            let allowed_count = WRITER_ALLOWED_LOG_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
            if should_log_step(allowed_count, WRITER_ALLOWED_LOG_STEP) {
                log::debug!(
                    "writer: real allow caller={} uid={} path={} n={}",
                    effective_caller_package,
                    effective_caller_uid,
                    resolved_path,
                    allowed_count
                );
            }
            let decision = RedirectDecision {
                action: RedirectAction::Allow,
                new_path: String::new(),
            };
            log_writer_redirect_perf(&WriterRedirectPerf {
                package_name: &package_name,
                exit_reason: "allowed",
                caller_package: &effective_caller_package,
                path: pathname,
                normalized_path: &normalized_path,
                reload_ms,
                caller_ms,
                enable_ms,
                mapping_ms,
                allow_ms,
                fallback_ms: 0,
                total_ms: paths::monotonic_ms().saturating_sub(perf_started_ms),
                decision: &decision,
            });
            log_thumbnail_decision("allowed", &decision);
            return decision;
        }
        writer::CallerRealPathMatch::None => {}
    }
    let allow_ms = paths::monotonic_ms().saturating_sub(allow_started_ms);

    let fallback_started_ms = paths::monotonic_ms();
    let redirect_target = writer::resolve_system_writer_redirect_target(
        &effective_caller_package,
        effective_caller_uid,
        user_id,
        is_caller_from_inferred,
        enabled_in_memory,
        enabled_in_raw,
    );
    if redirect_target.is_empty() {
        writer::log_system_writer_redirect_disabled(
            &effective_caller_package,
            effective_caller_uid,
            pathname,
        );
        let fallback_ms = paths::monotonic_ms().saturating_sub(fallback_started_ms);
        let decision = RedirectDecision {
            action: RedirectAction::Allow,
            new_path: String::new(),
        };
        log_writer_redirect_perf(&WriterRedirectPerf {
            package_name: &package_name,
            exit_reason: "target_empty",
            caller_package: &effective_caller_package,
            path: pathname,
            normalized_path: &normalized_path,
            reload_ms,
            caller_ms,
            enable_ms,
            mapping_ms,
            allow_ms,
            fallback_ms,
            total_ms: paths::monotonic_ms().saturating_sub(perf_started_ms),
            decision: &decision,
        });
        log_thumbnail_decision("target_empty", &decision);
        return decision;
    }

    let fallback_path =
        writer::map_path_by_caller_fallback(&resolved_path, &redirect_target, user_id);
    if fallback_path.is_empty() || fallback_path == resolved_path {
        let fallback_ms = paths::monotonic_ms().saturating_sub(fallback_started_ms);
        let decision = RedirectDecision {
            action: RedirectAction::Allow,
            new_path: String::new(),
        };
        log_writer_redirect_perf(&WriterRedirectPerf {
            package_name: &package_name,
            exit_reason: "fallback_empty",
            caller_package: &effective_caller_package,
            path: pathname,
            normalized_path: &normalized_path,
            reload_ms,
            caller_ms,
            enable_ms,
            mapping_ms,
            allow_ms,
            fallback_ms,
            total_ms: paths::monotonic_ms().saturating_sub(perf_started_ms),
            decision: &decision,
        });
        log_thumbnail_decision("fallback_empty", &decision);
        return decision;
    }

    let new_path = if is_data_media {
        writer::storage_to_data_media_path(&fallback_path)
    } else {
        fallback_path
    };
    log_writer_default_redirect_summary(
        &effective_caller_package,
        effective_caller_uid,
        &resolved_path,
        &new_path,
    );
    let fallback_ms = paths::monotonic_ms().saturating_sub(fallback_started_ms);
    let decision = RedirectDecision {
        action: RedirectAction::Redirect,
        new_path,
    };
    log_writer_redirect_perf(&WriterRedirectPerf {
        package_name: &package_name,
        exit_reason: "fallback",
        caller_package: &effective_caller_package,
        path: pathname,
        normalized_path: &normalized_path,
        reload_ms,
        caller_ms,
        enable_ms,
        mapping_ms,
        allow_ms,
        fallback_ms,
        total_ms: paths::monotonic_ms().saturating_sub(perf_started_ms),
        decision: &decision,
    });
    log_thumbnail_decision("fallback", &decision);
    decision
}

pub fn record_redirect_hit(hub: &InterceptHub, op_name: &str, from_path: &str, to_path: &str) {
    log::trace!("{}: {} -> {}", op_name, from_path, to_path);
    hub.increment_total_redirected();
    hub.increment_global_redirect_count();
}

fn log_writer_default_redirect_summary(
    caller_package: &str,
    caller_uid: i32,
    from_path: &str,
    to_path: &str,
) {
    let count = WRITER_DEFAULT_REDIRECT_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
    if !should_log_step(count, WRITER_DEFAULT_REDIRECT_LOG_STEP) {
        return;
    }
    log::debug!(
        "writer summary default_redirect caller={} uid={} count={} sample_from={} sample_to={}",
        caller_package,
        caller_uid,
        count,
        from_path,
        to_path
    );
}

struct WriterRedirectPerf<'a> {
    package_name: &'a str,
    exit_reason: &'a str,
    caller_package: &'a str,
    path: &'a str,
    normalized_path: &'a str,
    reload_ms: i64,
    caller_ms: i64,
    enable_ms: i64,
    mapping_ms: i64,
    allow_ms: i64,
    fallback_ms: i64,
    total_ms: i64,
    decision: &'a RedirectDecision,
}

fn log_redirect_perf(
    mode: &str,
    package_name: &str,
    exit_reason: &str,
    path: &str,
    caller_package: &str,
    started_ms: i64,
    decision: &RedirectDecision,
) {
    let total_ms = paths::monotonic_ms().saturating_sub(started_ms);
    if !should_log_redirect_perf(total_ms) {
        return;
    }
    log::info!(
        "perf redirect mode={} pkg={} caller={} exit={} action={} path={} to={} total_ms={}",
        mode,
        package_name,
        caller_package,
        exit_reason,
        redirect_action_text(decision),
        path,
        decision.new_path,
        total_ms
    );
}

fn log_writer_redirect_perf(perf: &WriterRedirectPerf<'_>) {
    if !should_log_writer_redirect_perf(perf) {
        return;
    }
    log::info!(
        "perf redirect mode=writer pkg={} caller={} exit={} action={} path={} normalized={} to={} reload_ms={} caller_ms={} enable_ms={} mapping_ms={} allow_ms={} fallback_ms={} total_ms={}",
        perf.package_name,
        perf.caller_package,
        perf.exit_reason,
        redirect_action_text(perf.decision),
        perf.path,
        perf.normalized_path,
        perf.decision.new_path,
        perf.reload_ms,
        perf.caller_ms,
        perf.enable_ms,
        perf.mapping_ms,
        perf.allow_ms,
        perf.fallback_ms,
        perf.total_ms
    );
}

fn should_log_writer_redirect_perf(perf: &WriterRedirectPerf<'_>) -> bool {
    if perf.total_ms >= WRITER_REDIRECT_TOTAL_SLOW_MS {
        return true;
    }
    if perf.reload_ms >= WRITER_REDIRECT_PHASE_SLOW_MS
        || perf.caller_ms >= WRITER_REDIRECT_PHASE_SLOW_MS
        || perf.enable_ms >= WRITER_REDIRECT_PHASE_SLOW_MS
        || perf.mapping_ms >= WRITER_REDIRECT_PHASE_SLOW_MS
        || perf.allow_ms >= WRITER_REDIRECT_PHASE_SLOW_MS
        || perf.fallback_ms >= WRITER_REDIRECT_PHASE_SLOW_MS
    {
        return true;
    }
    false
}

fn should_log_redirect_perf(total_ms: i64) -> bool {
    if total_ms >= REDIRECT_SLOW_MS {
        return true;
    }
    let count = REDIRECT_DECISION_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
    should_log_step(count, REDIRECT_SAMPLE_STEP)
}

fn redirect_action_text(decision: &RedirectDecision) -> &'static str {
    if decision.is_redirect() {
        "redirect"
    } else {
        "allow"
    }
}

// 路径已在 Android/data、Android/media 或 Android/obb 下，无需重定向
fn is_already_private_path(path: &str) -> bool {
    let prefix = "/storage/emulated/";
    if !path.starts_with(prefix) {
        return false;
    }
    let after_prefix = &path[prefix.len()..];
    let Some(slash) = after_prefix.find('/') else {
        return false;
    };
    let relative = &after_prefix[slash + 1..];
    relative.starts_with("Android/data/")
        || relative.starts_with("Android/media/")
        || relative.starts_with("Android/obb/")
}
