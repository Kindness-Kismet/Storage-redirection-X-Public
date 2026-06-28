// 路径路由器：重定向决策核心，处理映射、允许列表、排除列表
use crate::domain::PathMapping;
use crate::platform::{self, paths};
use once_cell::sync::Lazy;
use std::sync::RwLock;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

const ROUTER_SLOW_MS: i64 = 5;

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum RedirectAction {
    Allow,
    Redirect,
}

#[derive(Clone)]
pub struct RedirectDecision {
    pub action: RedirectAction,
    pub new_path: String,
}

impl RedirectDecision {
    fn allow() -> Self {
        Self {
            action: RedirectAction::Allow,
            new_path: String::new(),
        }
    }

    pub fn is_redirect(&self) -> bool {
        matches!(self.action, RedirectAction::Redirect)
    }
}

struct RouterState {
    current_package: String,
    user_id: i32,
    storage_root: String,
    redirect_target: String,
    allowed_real_paths: Vec<String>,
    excluded_real_paths: Vec<String>,
    path_mappings: Vec<PathMapping>,
}

impl RouterState {
    fn new() -> Self {
        Self {
            current_package: String::new(),
            user_id: 0,
            storage_root: String::new(),
            redirect_target: String::new(),
            allowed_real_paths: Vec::new(),
            excluded_real_paths: Vec::new(),
            path_mappings: Vec::new(),
        }
    }
}

struct RouterStats {
    total_checks: AtomicU64,
    redirected: AtomicU64,
    allowed: AtomicU64,
}

impl RouterStats {
    fn new() -> Self {
        Self {
            total_checks: AtomicU64::new(0),
            redirected: AtomicU64::new(0),
            allowed: AtomicU64::new(0),
        }
    }
}

pub struct PathRouter {
    state: RwLock<RouterState>,
    stats: RouterStats,
    initialized: AtomicBool,
}

impl PathRouter {
    fn new() -> Self {
        Self {
            state: RwLock::new(RouterState::new()),
            stats: RouterStats::new(),
            initialized: AtomicBool::new(false),
        }
    }

    pub fn instance() -> &'static PathRouter {
        &PATH_ROUTER
    }

    // 幂等，只首调打印
    pub fn init(&self) -> bool {
        if self.initialized.load(Ordering::Relaxed) {
            return true;
        }
        log::info!("path router init");
        self.initialized.store(true, Ordering::Relaxed);
        true
    }

    // 配置重定向上下文：包名、允许路径、路径映射
    pub fn configure(
        &self,
        package_name: &str,
        app_uid: i32,
        redirect_target: &str,
        allowed_real_paths: &[String],
        excluded_real_paths: &[String],
        path_mappings: &[PathMapping],
    ) {
        let mut state = self.state.write().unwrap_or_else(|err| err.into_inner());
        state.current_package = package_name.to_string();
        state.user_id = platform::user_id_from_uid(app_uid);
        state.storage_root = format!("/storage/emulated/{}", state.user_id);
        state.redirect_target =
            paths::resolve_user_path(&paths::normalize(redirect_target), state.user_id);

        state.allowed_real_paths.clear();
        for path in allowed_real_paths {
            let mut resolved = paths::normalize(path);
            if paths::has_unsafe_segments(&resolved) {
                continue;
            }
            resolved = paths::resolve_user_path(&resolved, state.user_id);
            if resolved.is_empty() {
                continue;
            }
            if resolved == state.storage_root {
                continue;
            }
            if !paths::starts_with(&resolved, &format!("{}/", state.storage_root)) {
                continue;
            }
            state.allowed_real_paths.push(resolved);
        }

        state.allowed_real_paths.sort();
        state.allowed_real_paths.dedup();

        state.excluded_real_paths.clear();
        for path in excluded_real_paths {
            let mut resolved = paths::normalize(path);
            if paths::has_unsafe_segments(&resolved) {
                continue;
            }
            resolved = paths::resolve_user_path(&resolved, state.user_id);
            if resolved.is_empty() {
                continue;
            }
            if resolved == state.storage_root {
                continue;
            }
            if !paths::starts_with(&resolved, &format!("{}/", state.storage_root)) {
                continue;
            }
            state.excluded_real_paths.push(resolved);
        }

        state.excluded_real_paths.sort();
        state.excluded_real_paths.dedup();

        state.path_mappings.clear();
        for mapping in path_mappings {
            let current_path =
                paths::resolve_user_path(&paths::normalize(&mapping.request_path), state.user_id);
            let target_path =
                paths::resolve_user_path(&paths::normalize(&mapping.final_path), state.user_id);
            if paths::has_unsafe_segments(&current_path) || paths::has_unsafe_segments(&target_path)
            {
                continue;
            }
            if current_path.is_empty() || target_path.is_empty() {
                continue;
            }
            if current_path == state.storage_root || target_path == state.storage_root {
                continue;
            }
            if !paths::starts_with(&current_path, &format!("{}/", state.storage_root))
                || !paths::starts_with(&target_path, &format!("{}/", state.storage_root))
            {
                continue;
            }
            if current_path == target_path {
                continue;
            }
            state
                .path_mappings
                .push(PathMapping::new(current_path, target_path));
        }

        state.path_mappings.sort_by(|a, b| {
            if a.request_path.len() != b.request_path.len() {
                b.request_path.len().cmp(&a.request_path.len())
            } else {
                a.request_path.cmp(&b.request_path)
            }
        });
        state
            .path_mappings
            .dedup_by(|a, b| a.request_path == b.request_path);

        log::info!(
            "router cfg pkg={} user={} allow={} excl={} map={}",
            state.current_package,
            state.user_id,
            state.allowed_real_paths.len(),
            state.excluded_real_paths.len(),
            state.path_mappings.len()
        );
    }

    // 对路径执行重定向决策：映射优先，其次允许列表，最后默认重定向
    pub fn process_path(&self, original_path: &str) -> RedirectDecision {
        let perf_started_ms = paths::monotonic_ms();
        self.stats.total_checks.fetch_add(1, Ordering::Relaxed);

        let normalized_started_ms = paths::monotonic_ms();
        let normalized = paths::normalize(original_path);
        let normalize_ms = paths::monotonic_ms().saturating_sub(normalized_started_ms);
        if normalized.is_empty() {
            self.stats.allowed.fetch_add(1, Ordering::Relaxed);
            let decision = RedirectDecision::allow();
            log_router_perf(
                "empty",
                original_path,
                &normalized,
                "",
                0,
                0,
                0,
                normalize_ms,
                0,
                0,
                0,
                perf_started_ms,
                &decision,
            );
            return decision;
        }

        if is_blacklisted_path(&normalized) {
            log::trace!("path blacklisted, skip: {}", normalized);
            self.stats.allowed.fetch_add(1, Ordering::Relaxed);
            let decision = RedirectDecision::allow();
            log_router_perf(
                "blacklist",
                original_path,
                &normalized,
                "",
                0,
                0,
                0,
                normalize_ms,
                0,
                0,
                0,
                perf_started_ms,
                &decision,
            );
            return decision;
        }

        if !paths::is_storage_path(&normalized) {
            self.stats.allowed.fetch_add(1, Ordering::Relaxed);
            let decision = RedirectDecision::allow();
            log_router_perf(
                "non_storage",
                original_path,
                &normalized,
                "",
                0,
                0,
                0,
                normalize_ms,
                0,
                0,
                0,
                perf_started_ms,
                &decision,
            );
            return decision;
        }

        let lock_started_ms = paths::monotonic_ms();
        let state = self.state.read().unwrap_or_else(|err| err.into_inner());
        let lock_ms = paths::monotonic_ms().saturating_sub(lock_started_ms);
        let resolved = paths::resolve_user_path(&normalized, state.user_id);
        let map_count = state.path_mappings.len();
        let allow_count = state.allowed_real_paths.len();
        let excl_count = state.excluded_real_paths.len();

        let map_started_ms = paths::monotonic_ms();
        if let Some(mapped) = map_path_to_target(&state.path_mappings, &resolved) {
            let map_ms = paths::monotonic_ms().saturating_sub(map_started_ms);
            self.stats.redirected.fetch_add(1, Ordering::Relaxed);
            log::debug!("map {} -> {}", resolved, mapped);
            let decision = RedirectDecision {
                action: RedirectAction::Redirect,
                new_path: mapped,
            };
            log_router_perf(
                "mapping",
                original_path,
                &normalized,
                &resolved,
                map_count,
                allow_count,
                excl_count,
                normalize_ms,
                lock_ms,
                map_ms,
                0,
                perf_started_ms,
                &decision,
            );
            return decision;
        }
        let map_ms = paths::monotonic_ms().saturating_sub(map_started_ms);

        let allow_started_ms = paths::monotonic_ms();
        if is_excluded_real_path(&state.excluded_real_paths, &resolved, &state.storage_root) {
            let redirected =
                generate_redirected_path(&state.storage_root, &state.redirect_target, &resolved);
            let allow_ms = paths::monotonic_ms().saturating_sub(allow_started_ms);
            if redirected != resolved {
                self.stats.redirected.fetch_add(1, Ordering::Relaxed);
                log::debug!("excl redirect {} -> {}", resolved, redirected);
                let decision = RedirectDecision {
                    action: RedirectAction::Redirect,
                    new_path: redirected,
                };
                log_router_perf(
                    "excluded",
                    original_path,
                    &normalized,
                    &resolved,
                    map_count,
                    allow_count,
                    excl_count,
                    normalize_ms,
                    lock_ms,
                    map_ms,
                    allow_ms,
                    perf_started_ms,
                    &decision,
                );
                return decision;
            }
        }

        if is_allowed_real_path(&state.allowed_real_paths, &resolved, &state.storage_root) {
            let allow_ms = paths::monotonic_ms().saturating_sub(allow_started_ms);
            self.stats.allowed.fetch_add(1, Ordering::Relaxed);
            let decision = RedirectDecision::allow();
            log_router_perf(
                "allowed",
                original_path,
                &normalized,
                &resolved,
                map_count,
                allow_count,
                excl_count,
                normalize_ms,
                lock_ms,
                map_ms,
                allow_ms,
                perf_started_ms,
                &decision,
            );
            return decision;
        }
        let allow_ms = paths::monotonic_ms().saturating_sub(allow_started_ms);

        let redirected =
            generate_redirected_path(&state.storage_root, &state.redirect_target, &resolved);
        if redirected == resolved {
            self.stats.allowed.fetch_add(1, Ordering::Relaxed);
            let decision = RedirectDecision::allow();
            log_router_perf(
                "same",
                original_path,
                &normalized,
                &resolved,
                map_count,
                allow_count,
                excl_count,
                normalize_ms,
                lock_ms,
                map_ms,
                allow_ms,
                perf_started_ms,
                &decision,
            );
            return decision;
        }

        self.stats.redirected.fetch_add(1, Ordering::Relaxed);
        log::debug!("redirect {} -> {}", resolved, redirected);
        let decision = RedirectDecision {
            action: RedirectAction::Redirect,
            new_path: redirected,
        };
        log_router_perf(
            "fallback",
            original_path,
            &normalized,
            &resolved,
            map_count,
            allow_count,
            excl_count,
            normalize_ms,
            lock_ms,
            map_ms,
            allow_ms,
            perf_started_ms,
            &decision,
        );
        decision
    }
}

static PATH_ROUTER: Lazy<PathRouter> = Lazy::new(PathRouter::new);

// 路径是否属于系统保护目录（不参与重定向）
fn is_blacklisted_path(path: &str) -> bool {
    const BLACKLIST: [&str; 11] = [
        "/system/",
        "/data/app/",
        "/data/misc/",
        "/proc/",
        "/dev/",
        "/sys/",
        "/apex/",
        "/vendor/",
        "/product/",
        "/odm/",
        "/cache/",
    ];
    BLACKLIST
        .iter()
        .any(|prefix| paths::starts_with(path, prefix))
}

fn is_excluded_real_path(excluded_paths: &[String], path: &str, storage_root: &str) -> bool {
    if storage_root.is_empty() {
        return false;
    }

    for excluded in excluded_paths {
        if paths::matches(excluded, path, true) {
            return true;
        }
    }
    false
}

fn is_allowed_real_path(allowed_paths: &[String], path: &str, storage_root: &str) -> bool {
    if storage_root.is_empty() {
        return false;
    }

    for allowed in allowed_paths {
        if paths::matches(allowed, path, true) {
            return true;
        }
    }
    false
}

fn map_path_to_target(mappings: &[PathMapping], path: &str) -> Option<String> {
    for mapping in mappings {
        if path == mapping.request_path {
            return Some(mapping.final_path.clone());
        }
        let prefix = format!("{}/", mapping.request_path);
        if paths::starts_with(path, &prefix) {
            let suffix = &path[mapping.request_path.len()..];
            return Some(format!("{}{}", mapping.final_path, suffix));
        }
    }
    None
}

#[allow(clippy::too_many_arguments)]
fn log_router_perf(
    exit_reason: &str,
    original_path: &str,
    normalized_path: &str,
    resolved_path: &str,
    map_count: usize,
    allow_count: usize,
    excl_count: usize,
    normalize_ms: i64,
    lock_ms: i64,
    map_ms: i64,
    allow_ms: i64,
    started_ms: i64,
    decision: &RedirectDecision,
) {
    let total_ms = paths::monotonic_ms().saturating_sub(started_ms);
    if total_ms < ROUTER_SLOW_MS {
        return;
    }
    log::info!(
        "perf router exit={} action={} map={} allow={} excl={} path={} normalized={} resolved={} to={} normalize_ms={} lock_ms={} map_ms={} allow_ms={} total_ms={}",
        exit_reason,
        if decision.is_redirect() {
            "redirect"
        } else {
            "allow"
        },
        map_count,
        allow_count,
        excl_count,
        original_path,
        normalized_path,
        resolved_path,
        decision.new_path,
        normalize_ms,
        lock_ms,
        map_ms,
        allow_ms,
        total_ms
    );
}

fn generate_redirected_path(storage_root: &str, redirect_target: &str, path: &str) -> String {
    if redirect_target.is_empty() || storage_root.is_empty() {
        return path.to_string();
    }

    if path == storage_root {
        return redirect_target.to_string();
    }

    let prefix = format!("{}/", storage_root);
    if !paths::starts_with(path, &prefix) {
        return path.to_string();
    }

    let relative = &path[prefix.len()..];
    paths::join(redirect_target, relative)
}
