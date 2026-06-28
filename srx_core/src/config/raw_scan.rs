use super::SettingsHub;
use crate::platform::module_paths;
use serde_json::Value;
use std::cell::{Cell, RefCell};
use std::fs;
use std::sync::atomic::{AtomicU64, Ordering};

const SELF_PACKAGE_NAME: &str = "com.storage.redirect.x";
const RAW_PROBE_LOG_STEP: u64 = 256;
// 缓存条目存活时间，命中即跳过 fs::metadata，避开同一 caller 的高频探测
const RAW_CACHE_TTL_MS: i64 = 1000;
// 单线程缓存项数上限，防止瞬时大量唯一 caller 撑爆缓存
const RAW_CACHE_CAP: usize = 128;

static RAW_PROBE_COUNT: AtomicU64 = AtomicU64::new(0);

struct CacheEntry {
    package_name: String,
    user_id: i32,
    config_version: u64,
    cached_at_ms: i64,
    is_enabled: bool,
    has_config: bool,
}

thread_local! {
    static RAW_CACHE: RefCell<Vec<CacheEntry>> = const { RefCell::new(Vec::new()) };
    static RAW_CACHE_VERSION: Cell<u64> = const { Cell::new(0) };
}

impl SettingsHub {
    // 绕过内存态合并配置，直接从磁盘 JSON 判断用户启用状态
    pub fn is_user_enabled_in_raw_config(&self, package_name: &str, user_id: i32) -> bool {
        if package_name.is_empty() || user_id < 0 || package_name == SELF_PACKAGE_NAME {
            return false;
        }

        let config_dir = {
            let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
            state.config_dir.clone()
        };
        if config_dir.is_empty() {
            return false;
        }

        let config_version = self.config_version();
        let now_ms = current_time_ms();
        invalidate_cache_on_version_change(config_version);

        // TTL 内的命中直接返回，跳过 fs::metadata
        if let Some(cached) = lookup_cached(package_name, user_id, config_version, now_ms) {
            return cached;
        }

        let config_path = format!("{}/apps/{}.json", config_dir, package_name);
        let fallback_path = format!("{}/apps/{}.json", module_paths::CONFIG_DIR, package_name);

        let resolved_path = match fs::metadata(&config_path) {
            Ok(_) => config_path.clone(),
            Err(_) => match fs::metadata(&fallback_path) {
                Ok(_) => {
                    log_probe(&format!(
                        "raw config fallback pkg={} shared={} module={}",
                        package_name, config_path, fallback_path
                    ));
                    fallback_path.clone()
                }
                Err(_) => {
                    log_probe(&format!(
                        "raw config stat failed pkg={} shared={} module={}",
                        package_name, config_path, fallback_path
                    ));
                    upsert_entry(
                        package_name,
                        user_id,
                        CacheEntry {
                            package_name: package_name.to_string(),
                            user_id,
                            config_version,
                            cached_at_ms: now_ms,
                            is_enabled: false,
                            has_config: false,
                        },
                    );
                    return false;
                }
            },
        };

        let mut is_enabled = false;
        let content = fs::read_to_string(&resolved_path).unwrap_or_default();
        if !content.is_empty() {
            match serde_json::from_str::<Value>(&content) {
                Ok(json) => {
                    if let Some(users) = json.get("users").and_then(|v| v.as_object())
                        && let Some(user) =
                            users.get(&user_id.to_string()).and_then(|v| v.as_object())
                    {
                        let enabled = user.get("enabled");
                        is_enabled = match enabled {
                            None => true,
                            Some(value) => value.as_bool().unwrap_or(false),
                        };
                    }
                }
                Err(error) => {
                    log_probe(&format!(
                        "raw config parse failed pkg={} path={} err={}",
                        package_name, resolved_path, error
                    ));
                    is_enabled = false;
                }
            }
        }

        upsert_entry(
            package_name,
            user_id,
            CacheEntry {
                package_name: package_name.to_string(),
                user_id,
                config_version,
                cached_at_ms: now_ms,
                is_enabled,
                has_config: true,
            },
        );

        is_enabled
    }
}

// config_version 变化即清空缓存，保证用户改配置后立即可见
fn invalidate_cache_on_version_change(current_version: u64) {
    RAW_CACHE_VERSION.with(|cell| {
        if cell.get() != current_version {
            cell.set(current_version);
            RAW_CACHE.with(|cache| cache.borrow_mut().clear());
        }
    });
}

fn lookup_cached(
    package_name: &str,
    user_id: i32,
    config_version: u64,
    now_ms: i64,
) -> Option<bool> {
    RAW_CACHE.with(|cache| {
        let cache = cache.borrow();
        cache
            .iter()
            .find(|entry| entry.user_id == user_id && entry.package_name == package_name)
            .and_then(|entry| {
                if entry.config_version != config_version {
                    return None;
                }
                if now_ms.saturating_sub(entry.cached_at_ms) > RAW_CACHE_TTL_MS {
                    return None;
                }
                Some(if entry.has_config {
                    entry.is_enabled
                } else {
                    false
                })
            })
    })
}

fn upsert_entry(package_name: &str, user_id: i32, entry: CacheEntry) {
    RAW_CACHE.with(|cache| {
        let mut cache = cache.borrow_mut();
        if let Some(slot) = cache
            .iter_mut()
            .find(|e| e.user_id == user_id && e.package_name == package_name)
        {
            *slot = entry;
            return;
        }
        if cache.len() >= RAW_CACHE_CAP {
            cache.clear();
        }
        cache.push(entry);
    });
}

fn current_time_ms() -> i64 {
    let mut ts = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    if unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut ts) } != 0 {
        return 0;
    }
    ts.tv_sec.saturating_mul(1000) + ts.tv_nsec / 1_000_000
}

fn log_probe(message: &str) {
    let count = RAW_PROBE_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
    if count == 1 || count.is_multiple_of(RAW_PROBE_LOG_STEP) {
        log::debug!("{}", message);
    }
}
