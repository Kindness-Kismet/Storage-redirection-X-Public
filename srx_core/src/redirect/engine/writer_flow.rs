use super::should_log_step;
use super::writer_perf::{
    WriterRedirectPerf, log_writer_default_redirect_summary, log_writer_redirect_perf,
};
use crate::config::{RawUserEnabledState, SettingsHub};
use crate::domain::PathMapping;
use crate::hook::stats::InterceptHub;
use crate::platform::paths;
use crate::redirect::router::{RedirectAction, RedirectDecision};
use crate::redirect::thumbnail_diag::{self, ThumbnailDecisionDiag};
use crate::redirect::{policy, writer};
use std::sync::atomic::{AtomicU64, Ordering};

const WRITER_ALLOWED_LOG_STEP: u64 = 256;
static WRITER_ALLOWED_LOG_COUNT: AtomicU64 = AtomicU64::new(0);

// 系统代写进程的重定向流水线：按阶段推进，任一阶段命中即经 finish 统一记录后退出
pub(super) fn run(
    hub: &InterceptHub,
    pathname: &str,
    package_name: &str,
    self_uid: i32,
    is_shared_uid: bool,
    perf_started_ms: i64,
) -> RedirectDecision {
    WriterFlow::new(
        hub,
        pathname,
        package_name,
        self_uid,
        is_shared_uid,
        perf_started_ms,
    )
    .execute()
}

struct WriterFlow<'a> {
    hub: &'a InterceptHub,
    pathname: &'a str,
    package_name: &'a str,
    self_uid: i32,
    is_shared_uid: bool,
    perf_started_ms: i64,
    normalized_path: String,
    is_data_media: bool,
    effective_caller_uid: i32,
    effective_caller_package: String,
    user_id: i32,
    is_caller_from_inferred: bool,
    enabled_in_memory: bool,
    enabled_in_raw: bool,
    resolved_path: String,
    caller_mappings: Vec<PathMapping>,
    mapped_path: String,
    redirect_target: String,
    fallback_started_ms: i64,
    reload_ms: i64,
    caller_ms: i64,
    enable_ms: i64,
    mapping_ms: i64,
    allow_ms: i64,
    fallback_ms: i64,
}

impl<'a> WriterFlow<'a> {
    fn new(
        hub: &'a InterceptHub,
        pathname: &'a str,
        package_name: &'a str,
        self_uid: i32,
        is_shared_uid: bool,
        perf_started_ms: i64,
    ) -> Self {
        Self {
            hub,
            pathname,
            package_name,
            self_uid,
            is_shared_uid,
            perf_started_ms,
            normalized_path: String::new(),
            is_data_media: false,
            effective_caller_uid: 0,
            effective_caller_package: String::new(),
            user_id: -1,
            is_caller_from_inferred: false,
            enabled_in_memory: false,
            enabled_in_raw: false,
            resolved_path: String::new(),
            caller_mappings: Vec::new(),
            mapped_path: String::new(),
            redirect_target: String::new(),
            fallback_started_ms: 0,
            reload_ms: 0,
            caller_ms: 0,
            enable_ms: 0,
            mapping_ms: 0,
            allow_ms: 0,
            fallback_ms: 0,
        }
    }

    fn execute(&mut self) -> RedirectDecision {
        self.reload_config();
        if let Some(decision) = self.check_empty_path() {
            return decision;
        }
        self.normalize();
        self.resolve_caller();
        if let Some(decision) = self.check_caller_empty() {
            return decision;
        }
        if let Some(decision) = self.check_user_id() {
            return decision;
        }
        if let Some(decision) = self.check_enabled() {
            return decision;
        }
        if let Some(decision) = self.resolve_and_check_storage() {
            return decision;
        }
        self.resolve_mappings();
        if let Some(decision) = self.check_mapping() {
            return decision;
        }
        if let Some(decision) = self.check_mapping_target() {
            return decision;
        }
        if let Some(decision) = self.classify_allow() {
            return decision;
        }
        if let Some(decision) = self.resolve_target() {
            return decision;
        }
        self.apply_fallback()
    }

    fn reload_config(&mut self) {
        let reload_started_ms = paths::monotonic_ms();
        crate::hook::refresh_runtime_config_throttled();
        self.reload_ms = paths::monotonic_ms().saturating_sub(reload_started_ms);
    }

    fn check_empty_path(&mut self) -> Option<RedirectDecision> {
        if !self.pathname.is_empty() {
            return None;
        }
        let decision = RedirectDecision {
            action: RedirectAction::Allow,
            new_path: String::new(),
        };
        Some(self.finish("empty_path", decision, false))
    }

    fn normalize(&mut self) {
        let raw_normalized = paths::normalize(self.pathname);
        self.is_data_media = paths::starts_with(&raw_normalized, "/data/media/");
        self.normalized_path = if self.is_data_media {
            writer::data_media_to_storage_path(&raw_normalized)
        } else {
            raw_normalized
        };
    }

    fn resolve_caller(&mut self) {
        let caller_started_ms = paths::monotonic_ms();
        self.effective_caller_uid = self.hub.get_current_caller_uid();
        let original_caller_uid = self.effective_caller_uid;
        self.effective_caller_package = self.hub.get_current_caller_package();

        self.user_id = writer::resolve_system_writer_user_id(
            &self.normalized_path,
            &mut self.effective_caller_uid,
        );
        self.is_caller_from_inferred = false;
        if original_caller_uid >= writer::ANDROID_APP_UID_START {
            writer::maybe_override_system_writer_caller_by_path(
                &self.normalized_path,
                &mut self.effective_caller_uid,
                self.user_id,
                &mut self.effective_caller_package,
                &mut self.is_caller_from_inferred,
            );
        } else if self.effective_caller_package.is_empty() {
            writer::log_system_writer_skip_path_infer_for_low_uid(
                original_caller_uid,
                &self.normalized_path,
            );
        }

        // Java 栈帧回退：共享 UID 进程内部创建时，按调用栈识别具体组件
        if self.effective_caller_package.is_empty()
            && self.is_shared_uid
            && !is_already_private_path(&self.normalized_path)
        {
            let candidates: Vec<String> = policy::get_packages_for_uid(self.self_uid)
                .into_iter()
                .filter(|pkg| !policy::is_system_writer_package(pkg))
                .collect();
            if let Some(pkg) = crate::monitor::infer_caller_package_by_stack(&candidates) {
                let inferred_uid = policy::get_uid_for_package(&pkg);
                if inferred_uid >= writer::ANDROID_APP_UID_START {
                    self.effective_caller_uid = inferred_uid;
                }
                log::debug!(
                    "writer: stack infer caller={} uid={} path={}",
                    pkg,
                    self.effective_caller_uid,
                    self.normalized_path
                );
                self.effective_caller_package = pkg;
                self.is_caller_from_inferred = true;
            }
        }

        self.caller_ms = paths::monotonic_ms().saturating_sub(caller_started_ms);
    }

    fn check_caller_empty(&mut self) -> Option<RedirectDecision> {
        if !self.effective_caller_package.is_empty() {
            return None;
        }
        writer::log_system_writer_caller_unresolved(
            self.package_name,
            self.effective_caller_uid,
            self.pathname,
        );
        let decision = RedirectDecision {
            action: RedirectAction::Allow,
            new_path: String::new(),
        };
        Some(self.finish("caller_empty", decision, false))
    }

    fn check_user_id(&mut self) -> Option<RedirectDecision> {
        if self.user_id >= 0 {
            return None;
        }
        writer::log_system_writer_user_unresolved(
            &self.effective_caller_package,
            self.effective_caller_uid,
            self.pathname,
        );
        let decision = RedirectDecision {
            action: RedirectAction::Allow,
            new_path: String::new(),
        };
        Some(self.finish("user_empty", decision, false))
    }

    fn check_enabled(&mut self) -> Option<RedirectDecision> {
        let enable_started_ms = paths::monotonic_ms();
        let config = SettingsHub::instance();
        self.enabled_in_memory =
            config.should_redirect(&self.effective_caller_package, self.effective_caller_uid);
        let raw_enabled_state =
            config.get_user_enabled_in_raw_config(&self.effective_caller_package, self.user_id);
        self.enabled_in_raw = raw_enabled_state == RawUserEnabledState::Enabled;
        self.enable_ms = paths::monotonic_ms().saturating_sub(enable_started_ms);
        if raw_enabled_state == RawUserEnabledState::Disabled
            || (!self.enabled_in_memory && !self.enabled_in_raw)
        {
            writer::log_system_writer_redirect_disabled(
                &self.effective_caller_package,
                self.effective_caller_uid,
                self.pathname,
            );
            let decision = RedirectDecision {
                action: RedirectAction::Allow,
                new_path: String::new(),
            };
            return Some(self.finish("disabled", decision, false));
        }
        None
    }

    fn resolve_and_check_storage(&mut self) -> Option<RedirectDecision> {
        self.resolved_path = paths::resolve_user_path(&self.normalized_path, self.user_id);
        if writer::is_path_in_user_storage(&self.resolved_path, self.user_id) {
            return None;
        }
        let decision = RedirectDecision {
            action: RedirectAction::Allow,
            new_path: String::new(),
        };
        Some(self.finish("outside_storage", decision, false))
    }

    fn resolve_mappings(&mut self) {
        let mapping_started_ms = paths::monotonic_ms();
        self.caller_mappings =
            writer::get_caller_mappings(&self.effective_caller_package, self.effective_caller_uid);
        self.mapped_path =
            writer::map_path_by_caller_mappings(&self.resolved_path, &self.caller_mappings);
        self.mapping_ms = paths::monotonic_ms().saturating_sub(mapping_started_ms);
    }

    fn check_mapping(&mut self) -> Option<RedirectDecision> {
        if self.mapped_path.is_empty() || self.mapped_path == self.resolved_path {
            return None;
        }
        let new_path = if self.is_data_media {
            writer::storage_to_data_media_path(&self.mapped_path)
        } else {
            self.mapped_path.clone()
        };
        log::debug!(
            "writer: caller map caller={} uid={} from={} to={}",
            self.effective_caller_package,
            self.effective_caller_uid,
            self.resolved_path,
            new_path
        );
        let decision = RedirectDecision {
            action: RedirectAction::Redirect,
            new_path,
        };
        Some(self.finish("mapping", decision, true))
    }

    fn check_mapping_target(&mut self) -> Option<RedirectDecision> {
        for mapping in &self.caller_mappings {
            if self.resolved_path == mapping.final_path
                || paths::starts_with(&self.resolved_path, &format!("{}/", mapping.final_path))
            {
                log::debug!(
                    "writer: map target allow caller={} uid={} path={}",
                    self.effective_caller_package,
                    self.effective_caller_uid,
                    self.resolved_path
                );
                let decision = RedirectDecision {
                    action: RedirectAction::Allow,
                    new_path: String::new(),
                };
                return Some(self.finish("mapping_target", decision, true));
            }
        }
        None
    }

    fn classify_allow(&mut self) -> Option<RedirectDecision> {
        let allow_started_ms = paths::monotonic_ms();
        match writer::classify_path_by_caller_real_paths(
            &self.resolved_path,
            &self.effective_caller_package,
            self.effective_caller_uid,
        ) {
            // 两者的写入都交给后续沙盒回退；代写读取不走重定向，查询改写另行保留只读目录的真实条目
            rule @ (writer::CallerRealPathMatch::Excluded
            | writer::CallerRealPathMatch::ReadOnly) => {
                log::debug!(
                    "writer: {} hit caller={} uid={} path={}",
                    if matches!(rule, writer::CallerRealPathMatch::Excluded) {
                        "excl"
                    } else {
                        "ro"
                    },
                    self.effective_caller_package,
                    self.effective_caller_uid,
                    self.resolved_path
                );
            }
            writer::CallerRealPathMatch::Allowed => {
                self.allow_ms = paths::monotonic_ms().saturating_sub(allow_started_ms);
                let allowed_count = WRITER_ALLOWED_LOG_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
                if should_log_step(allowed_count, WRITER_ALLOWED_LOG_STEP) {
                    log::debug!(
                        "writer: real allow caller={} uid={} path={} n={}",
                        self.effective_caller_package,
                        self.effective_caller_uid,
                        self.resolved_path,
                        allowed_count
                    );
                }
                let decision = RedirectDecision {
                    action: RedirectAction::Allow,
                    new_path: String::new(),
                };
                return Some(self.finish("allowed", decision, true));
            }
            writer::CallerRealPathMatch::None => {}
        }
        self.allow_ms = paths::monotonic_ms().saturating_sub(allow_started_ms);
        None
    }

    fn resolve_target(&mut self) -> Option<RedirectDecision> {
        self.fallback_started_ms = paths::monotonic_ms();
        self.redirect_target = writer::resolve_system_writer_redirect_target(
            &self.effective_caller_package,
            self.effective_caller_uid,
            self.user_id,
            self.is_caller_from_inferred,
            self.enabled_in_memory,
            self.enabled_in_raw,
        );
        if !self.redirect_target.is_empty() {
            return None;
        }
        writer::log_system_writer_redirect_disabled(
            &self.effective_caller_package,
            self.effective_caller_uid,
            self.pathname,
        );
        self.fallback_ms = paths::monotonic_ms().saturating_sub(self.fallback_started_ms);
        let decision = RedirectDecision {
            action: RedirectAction::Allow,
            new_path: String::new(),
        };
        Some(self.finish("target_empty", decision, true))
    }

    fn apply_fallback(&mut self) -> RedirectDecision {
        let fallback_path = writer::map_path_by_caller_fallback(
            &self.resolved_path,
            &self.redirect_target,
            self.user_id,
        );
        if fallback_path.is_empty() || fallback_path == self.resolved_path {
            self.fallback_ms = paths::monotonic_ms().saturating_sub(self.fallback_started_ms);
            let decision = RedirectDecision {
                action: RedirectAction::Allow,
                new_path: String::new(),
            };
            return self.finish("fallback_empty", decision, true);
        }

        let new_path = if self.is_data_media {
            writer::storage_to_data_media_path(&fallback_path)
        } else {
            fallback_path
        };
        log_writer_default_redirect_summary(
            &self.effective_caller_package,
            self.effective_caller_uid,
            &self.resolved_path,
            &new_path,
        );
        self.fallback_ms = paths::monotonic_ms().saturating_sub(self.fallback_started_ms);
        let decision = RedirectDecision {
            action: RedirectAction::Redirect,
            new_path,
        };
        self.finish("fallback", decision, true)
    }

    // 统一记录性能与缩略图诊断后返回决策；log_thumbnail 仅在已解析调用方的退出点为真
    fn finish(
        &self,
        exit_reason: &str,
        decision: RedirectDecision,
        log_thumbnail: bool,
    ) -> RedirectDecision {
        log_writer_redirect_perf(&WriterRedirectPerf {
            package_name: self.package_name,
            exit_reason,
            caller_package: &self.effective_caller_package,
            path: self.pathname,
            normalized_path: &self.normalized_path,
            reload_ms: self.reload_ms,
            caller_ms: self.caller_ms,
            enable_ms: self.enable_ms,
            mapping_ms: self.mapping_ms,
            allow_ms: self.allow_ms,
            fallback_ms: self.fallback_ms,
            total_ms: paths::monotonic_ms().saturating_sub(self.perf_started_ms),
            decision: &decision,
        });
        if log_thumbnail {
            thumbnail_diag::log_system_writer_decision(&ThumbnailDecisionDiag {
                proc_package: self.package_name,
                caller_package: &self.effective_caller_package,
                caller_uid: self.effective_caller_uid,
                user_id: self.user_id,
                resolved_path: &self.resolved_path,
                enabled_in_memory: self.enabled_in_memory,
                enabled_in_raw: self.enabled_in_raw,
                is_caller_from_inferred: self.is_caller_from_inferred,
                exit_reason,
                decision: &decision,
            });
        }
        decision
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
