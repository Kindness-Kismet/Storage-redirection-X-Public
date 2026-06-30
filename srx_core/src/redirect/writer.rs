use crate::config::SettingsHub;
use crate::domain::PathMapping;
use crate::platform::{self, paths};
use crate::redirect::policy;
use std::cell::RefCell;
use std::collections::HashSet;

pub const ANDROID_APP_UID_START: i32 = 10000;
const DATA_MEDIA_PREFIX: &str = "/data/media/";
const STORAGE_PREFIX: &str = "/storage/emulated/";

pub fn data_media_to_storage_path(path: &str) -> String {
    if !paths::starts_with(path, DATA_MEDIA_PREFIX) {
        return path.to_string();
    }
    format!("{}{}", STORAGE_PREFIX, &path[DATA_MEDIA_PREFIX.len()..])
}

pub fn storage_to_data_media_path(path: &str) -> String {
    if !paths::starts_with(path, STORAGE_PREFIX) {
        return path.to_string();
    }
    format!("{}{}", DATA_MEDIA_PREFIX, &path[STORAGE_PREFIX.len()..])
}

pub fn get_caller_mappings(caller_package: &str, caller_uid: i32) -> Vec<PathMapping> {
    CALLER_MAPPING_CACHE.with(|cache| {
        let mut cache = cache.borrow_mut();
        let config_version = SettingsHub::instance().config_version();
        if cache.package_name == caller_package
            && cache.caller_uid == caller_uid
            && cache.config_version == config_version
        {
            return cache.mappings.clone();
        }
        cache.package_name = caller_package.to_string();
        cache.caller_uid = caller_uid;
        cache.config_version = config_version;
        cache.mappings = build_caller_mappings(caller_package, caller_uid);
        cache.mappings.clone()
    })
}

pub fn map_path_by_caller_mappings(path: &str, mappings: &[PathMapping]) -> String {
    for mapping in mappings {
        if path == mapping.request_path {
            return mapping.final_path.clone();
        }
        let prefix = format!("{}/", mapping.request_path);
        if paths::starts_with(path, &prefix) {
            let suffix = &path[mapping.request_path.len()..];
            return format!("{}{}", mapping.final_path, suffix);
        }
    }
    String::new()
}

pub fn is_path_allowed_by_caller_real_paths(
    resolved_path: &str,
    caller_package: &str,
    caller_uid: i32,
) -> bool {
    if resolved_path.is_empty() {
        return false;
    }

    let allowed_paths = get_caller_allowed_real_paths(caller_package, caller_uid);
    for allowed in allowed_paths {
        if allowed.is_empty() {
            continue;
        }
        if paths::matches(&allowed, resolved_path, true) {
            return true;
        }
    }
    false
}

pub fn is_path_excluded_by_caller_real_paths(
    resolved_path: &str,
    caller_package: &str,
    caller_uid: i32,
) -> bool {
    if resolved_path.is_empty() {
        return false;
    }

    let excluded_paths = get_caller_excluded_real_paths(caller_package, caller_uid);
    for excluded in excluded_paths {
        if excluded.is_empty() {
            continue;
        }
        if paths::matches(&excluded, resolved_path, true) {
            return true;
        }
    }
    false
}

// 无映射命中时的 fallback：原路 → redirect_target
pub fn map_path_by_caller_fallback(
    normalized_path: &str,
    redirect_target: &str,
    user_id: i32,
) -> String {
    if normalized_path.is_empty() || redirect_target.is_empty() {
        return String::new();
    }

    let storage_root = format!("/storage/emulated/{}", user_id);
    if normalized_path == redirect_target
        || paths::starts_with(normalized_path, &format!("{}/", redirect_target))
    {
        return String::new();
    }

    if normalized_path == storage_root {
        return redirect_target.to_string();
    }

    if !paths::starts_with(normalized_path, &format!("{}/", storage_root)) {
        return String::new();
    }

    let suffix = &normalized_path[storage_root.len()..];
    if suffix.is_empty() {
        return redirect_target.to_string();
    }

    let fallback = format!("{}{}", redirect_target, suffix);
    if paths::has_unsafe_segments(&fallback) {
        return String::new();
    }
    fallback
}

pub fn resolve_system_writer_redirect_target(
    caller_package: &str,
    caller_uid: i32,
    user_id: i32,
    is_caller_from_inferred_mapping: bool,
) -> String {
    if caller_package.is_empty() || user_id < 0 {
        return String::new();
    }

    let target = get_caller_default_redirect_target(caller_package, caller_uid);
    if !target.is_empty() {
        return target;
    }

    let config = SettingsHub::instance();
    let raw_enabled = config.is_user_enabled_in_raw_config(caller_package, user_id);
    if !is_caller_from_inferred_mapping && !raw_enabled {
        return String::new();
    }

    let mut target = format!(
        "/storage/emulated/{}/Android/data/{}/sdcard",
        user_id, caller_package
    );
    target = paths::resolve_user_path(&paths::normalize(&target), user_id);
    if target.is_empty() || paths::has_unsafe_segments(&target) {
        return String::new();
    }

    log::debug!(
        "writer force default caller={} uid={} reason={} target={}",
        caller_package,
        caller_uid,
        if is_caller_from_inferred_mapping {
            "inferred"
        } else {
            "config"
        },
        target
    );
    target
}

// 低 UID 时从路径反推 user_id，并回填 effective_caller_uid
pub fn resolve_system_writer_user_id(normalized_path: &str, effective_caller_uid: &mut i32) -> i32 {
    if *effective_caller_uid >= ANDROID_APP_UID_START {
        return platform::user_id_from_uid(*effective_caller_uid);
    }

    let user_id = paths::extract_user_id_from_storage_path(normalized_path);
    if user_id >= 0 {
        *effective_caller_uid = user_id * platform::ANDROID_USER_ID_OFFSET + ANDROID_APP_UID_START;
    }
    user_id
}

// 路径命中已配置应用时改写 caller_package，避免错误按原调用方重定向
pub fn maybe_override_system_writer_caller_by_path(
    normalized_path: &str,
    effective_caller_uid: &mut i32,
    user_id: i32,
    effective_caller_package: &mut String,
    is_caller_from_inferred_mapping: &mut bool,
) {
    if user_id < 0 {
        return;
    }

    let config = SettingsHub::instance();
    let inferred =
        config.resolve_redirect_package_by_path_for_user(*effective_caller_uid, normalized_path);
    if inferred.is_empty() {
        return;
    }

    let mut should_replace = effective_caller_package.is_empty();
    if !should_replace && !config.should_redirect(effective_caller_package, *effective_caller_uid) {
        should_replace = true;
    }

    if !should_replace {
        return;
    }

    let mut inferred_uid = policy::get_uid_for_package(&inferred);
    if inferred_uid < ANDROID_APP_UID_START {
        policy::refresh_shared_uid_cache();
        inferred_uid = policy::get_uid_for_package(&inferred);
    }
    if inferred_uid >= ANDROID_APP_UID_START {
        *effective_caller_uid = inferred_uid;
    }

    log::debug!(
        "writer path override caller={} uid={} path={}",
        inferred,
        *effective_caller_uid,
        normalized_path
    );
    *effective_caller_package = inferred;
    *is_caller_from_inferred_mapping = true;
}

pub fn is_path_in_user_storage(resolved_path: &str, user_id: i32) -> bool {
    if resolved_path.is_empty() || user_id < 0 {
        return false;
    }

    let prefix = format!("/storage/emulated/{}/", user_id);
    paths::starts_with(resolved_path, &prefix)
}

pub fn log_system_writer_caller_unresolved(
    hub_package: &str,
    effective_caller_uid: i32,
    pathname: &str,
) {
    let count = SYSTEM_WRITER_CALLER_MISS.fetch_add(1, std::sync::atomic::Ordering::Relaxed) + 1;
    if !should_log_every_step(count) {
        return;
    }
    log::debug!(
        "writer caller unresolved proc={} uid={} path={} n={}",
        hub_package,
        effective_caller_uid,
        pathname,
        count
    );
}

pub fn log_system_writer_user_unresolved(
    caller_package: &str,
    effective_caller_uid: i32,
    pathname: &str,
) {
    let count = SYSTEM_WRITER_CALLER_MISS.fetch_add(1, std::sync::atomic::Ordering::Relaxed) + 1;
    if !should_log_every_step(count) {
        return;
    }
    log::debug!(
        "writer user unresolved caller={} uid={} path={} n={}",
        caller_package,
        effective_caller_uid,
        pathname,
        count
    );
}

pub fn log_system_writer_redirect_disabled(
    caller_package: &str,
    effective_caller_uid: i32,
    pathname: &str,
) {
    let count =
        SYSTEM_WRITER_CALLER_DISABLED.fetch_add(1, std::sync::atomic::Ordering::Relaxed) + 1;
    if !should_log_every_step(count) {
        return;
    }
    log::debug!(
        "writer redirect disabled caller={} uid={} path={} n={}",
        caller_package,
        effective_caller_uid,
        pathname,
        count
    );
}

pub fn log_system_writer_skip_path_infer_for_low_uid(original_caller_uid: i32, pathname: &str) {
    let count =
        SYSTEM_WRITER_PATH_INFER_SKIPPED.fetch_add(1, std::sync::atomic::Ordering::Relaxed) + 1;
    if !should_log_every_step(count) {
        return;
    }
    log::debug!(
        "writer skip path infer low-uid uid={} path={} n={}",
        original_caller_uid,
        pathname,
        count
    );
}

// 过滤跨存储/越界/同一路径/Android 目录及重复项
fn build_caller_mappings(caller_package: &str, caller_uid: i32) -> Vec<PathMapping> {
    if caller_package.is_empty() || caller_uid < ANDROID_APP_UID_START {
        return Vec::new();
    }

    let config = SettingsHub::instance();
    let raw_mappings = config.get_path_mappings(caller_package, caller_uid);
    if raw_mappings.is_empty() {
        return Vec::new();
    }

    let user_id = platform::user_id_from_uid(caller_uid);
    let storage_root = format!("/storage/emulated/{}", user_id);
    let android_prefix = format!("{}/Android", storage_root);
    let android_prefix_with_slash = format!("{}/", android_prefix);

    let mut seen_current: HashSet<String> = HashSet::new();
    let mut mappings: Vec<PathMapping> = Vec::new();

    for mapping in raw_mappings {
        let current_path =
            paths::resolve_user_path(&paths::normalize(&mapping.request_path), user_id);
        let target_path = paths::resolve_user_path(&paths::normalize(&mapping.final_path), user_id);

        if current_path.is_empty() || target_path.is_empty() {
            continue;
        }
        if paths::has_unsafe_segments(&current_path) || paths::has_unsafe_segments(&target_path) {
            continue;
        }
        if current_path == target_path {
            continue;
        }
        if !paths::starts_with(&current_path, &format!("{}/", storage_root))
            || !paths::starts_with(&target_path, &format!("{}/", storage_root))
        {
            continue;
        }
        if current_path == android_prefix
            || paths::starts_with(&current_path, &android_prefix_with_slash)
            || target_path == android_prefix
            || paths::starts_with(&target_path, &android_prefix_with_slash)
        {
            continue;
        }
        if !seen_current.insert(current_path.clone()) {
            continue;
        }
        mappings.push(PathMapping::new(current_path, target_path));
    }

    mappings.sort_by(|a, b| {
        if a.request_path.len() != b.request_path.len() {
            b.request_path.len().cmp(&a.request_path.len())
        } else {
            a.request_path.cmp(&b.request_path)
        }
    });

    mappings
}

fn get_caller_default_redirect_target(caller_package: &str, caller_uid: i32) -> String {
    CALLER_TARGET_CACHE.with(|cache| {
        let mut cache = cache.borrow_mut();
        let config_version = SettingsHub::instance().config_version();
        if cache.package_name == caller_package
            && cache.caller_uid == caller_uid
            && cache.config_version == config_version
        {
            return cache.redirect_target.clone();
        }

        cache.package_name = caller_package.to_string();
        cache.caller_uid = caller_uid;
        cache.config_version = config_version;
        cache.redirect_target.clear();

        if caller_package.is_empty() || caller_uid < ANDROID_APP_UID_START {
            return cache.redirect_target.clone();
        }

        let config = SettingsHub::instance();
        if !config.should_redirect(caller_package, caller_uid) {
            return cache.redirect_target.clone();
        }

        let user_id = platform::user_id_from_uid(caller_uid);
        let target = format!(
            "/storage/emulated/{}/Android/data/{}/sdcard",
            user_id, caller_package
        );
        let resolved = paths::resolve_user_path(&paths::normalize(&target), user_id);
        if !paths::has_unsafe_segments(&resolved) {
            cache.redirect_target = resolved;
        }
        cache.redirect_target.clone()
    })
}

fn get_caller_allowed_real_paths(caller_package: &str, caller_uid: i32) -> Vec<String> {
    CALLER_ALLOWED_CACHE.with(|cache| {
        let mut cache = cache.borrow_mut();
        let config_version = SettingsHub::instance().config_version();
        if cache.package_name == caller_package
            && cache.caller_uid == caller_uid
            && cache.config_version == config_version
            && cache.is_loaded
        {
            return cache.allowed_real_paths.clone();
        }

        cache.config_version = config_version;
        refresh_caller_real_paths_cache(&mut cache, caller_package, caller_uid);
        cache.allowed_real_paths.clone()
    })
}

fn get_caller_excluded_real_paths(caller_package: &str, caller_uid: i32) -> Vec<String> {
    CALLER_ALLOWED_CACHE.with(|cache| {
        let mut cache = cache.borrow_mut();
        let config_version = SettingsHub::instance().config_version();
        if cache.package_name == caller_package
            && cache.caller_uid == caller_uid
            && cache.config_version == config_version
            && cache.is_loaded
        {
            return cache.excluded_real_paths.clone();
        }

        cache.config_version = config_version;
        refresh_caller_real_paths_cache(&mut cache, caller_package, caller_uid);
        cache.excluded_real_paths.clone()
    })
}

fn refresh_caller_real_paths_cache(
    cache: &mut CallerAllowedCache,
    caller_package: &str,
    caller_uid: i32,
) {
    cache.package_name = caller_package.to_string();
    cache.caller_uid = caller_uid;
    cache.is_loaded = true;
    cache.allowed_real_paths.clear();
    cache.excluded_real_paths.clear();

    if caller_package.is_empty() || caller_uid < ANDROID_APP_UID_START {
        return;
    }

    let config = SettingsHub::instance();
    cache.allowed_real_paths = config.get_allowed_real_paths(caller_package, caller_uid);
    cache.excluded_real_paths = config.get_excluded_real_paths(caller_package, caller_uid);
}

// 首次和每 256 次输出一次
fn should_log_every_step(count: u64) -> bool {
    count == 1 || count.is_multiple_of(256)
}

struct CallerMappingCache {
    package_name: String,
    caller_uid: i32,
    config_version: u64,
    mappings: Vec<PathMapping>,
}

struct CallerTargetCache {
    package_name: String,
    caller_uid: i32,
    config_version: u64,
    redirect_target: String,
}

struct CallerAllowedCache {
    package_name: String,
    caller_uid: i32,
    config_version: u64,
    is_loaded: bool,
    allowed_real_paths: Vec<String>,
    excluded_real_paths: Vec<String>,
}

thread_local! {
    static CALLER_MAPPING_CACHE: RefCell<CallerMappingCache> = const { RefCell::new(CallerMappingCache {
        package_name: String::new(),
        caller_uid: -1,
        config_version: 0,
        mappings: Vec::new(),
    }) };
    static CALLER_TARGET_CACHE: RefCell<CallerTargetCache> = const { RefCell::new(CallerTargetCache {
        package_name: String::new(),
        caller_uid: -1,
        config_version: 0,
        redirect_target: String::new(),
    }) };
    static CALLER_ALLOWED_CACHE: RefCell<CallerAllowedCache> = const { RefCell::new(CallerAllowedCache {
        package_name: String::new(),
        caller_uid: -1,
        config_version: 0,
        is_loaded: false,
        allowed_real_paths: Vec::new(),
        excluded_real_paths: Vec::new(),
    }) };
}

static SYSTEM_WRITER_CALLER_MISS: std::sync::atomic::AtomicU64 =
    std::sync::atomic::AtomicU64::new(0);
static SYSTEM_WRITER_CALLER_DISABLED: std::sync::atomic::AtomicU64 =
    std::sync::atomic::AtomicU64::new(0);
static SYSTEM_WRITER_PATH_INFER_SKIPPED: std::sync::atomic::AtomicU64 =
    std::sync::atomic::AtomicU64::new(0);
