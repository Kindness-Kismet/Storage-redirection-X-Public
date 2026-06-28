use super::{AppProfile, SettingsState, UserProfile};
use crate::domain::PathMapping;
use crate::platform::paths;
use serde_json::Value;
use std::collections::HashMap;

pub fn parse_global_config(state: &mut SettingsState, json_content: &str) -> bool {
    let parsed: Value = match serde_json::from_str(json_content) {
        Ok(value) => value,
        Err(error) => {
            log::error!("global config parse err: {}", error);
            state.is_file_monitor_enabled = false;
            state.is_fuse_fixer_enabled = false;
            return false;
        }
    };

    if let Some(value) = parsed.get("file_monitor_enabled") {
        state.is_file_monitor_enabled = value.as_bool().unwrap_or(false);
    } else {
        state.is_file_monitor_enabled = false;
    }

    if let Some(value) = parsed.get("fuse_fixer_enabled") {
        state.is_fuse_fixer_enabled = value.as_bool().unwrap_or(false);
    } else {
        state.is_fuse_fixer_enabled = false;
    }

    if state.should_log_summary {
        log::debug!(
            "global monitor={} fuse_fixer={}",
            state.is_file_monitor_enabled,
            state.is_fuse_fixer_enabled
        );
    }
    true
}

pub fn parse_app_config(state: &mut SettingsState, package_name: &str, json_content: &str) -> bool {
    let parsed: Value = match serde_json::from_str(json_content) {
        Ok(value) => value,
        Err(error) => {
            log::error!("app config parse err [{}]: {}", package_name, error);
            return false;
        }
    };

    let users = match parsed.get("users") {
        Some(value) if value.is_object() => value,
        _ => {
            log::warn!("app config missing users, skip: {}", package_name);
            return false;
        }
    };

    let mut app_profile = AppProfile {
        user_profiles: HashMap::new(),
    };

    let Some(users_map) = users.as_object() else {
        log::warn!("app config users not object, skip: {}", package_name);
        return false;
    };
    for (user_key, user_value) in users_map {
        let Some(user_id) = try_parse_user_id(user_key) else {
            continue;
        };
        let Some(user_obj) = user_value.as_object() else {
            continue;
        };

        let mut user_profile = UserProfile {
            is_enabled: true,
            allowed_real_paths: Vec::new(),
            excluded_real_paths: Vec::new(),
            path_mappings: Vec::new(),
        };

        if let Some(enabled) = user_obj.get("enabled")
            && let Some(flag) = enabled.as_bool()
        {
            user_profile.is_enabled = flag;
        }

        let storage_root = format!("/storage/emulated/{}", user_id);

        if let Some(paths_value) = user_obj.get("allowed_real_paths")
            && let Some(paths_list) = paths_value.as_array()
        {
            for item in paths_list {
                let Some(raw) = item.as_str() else {
                    continue;
                };
                let Some((is_excluded, resolved)) =
                    resolve_allowed_path_rule_for_user(raw, &storage_root)
                else {
                    log::warn!(
                        "skip allow rule (invalid, relative only): user={} path={}",
                        user_id,
                        raw
                    );
                    continue;
                };
                if resolved.is_empty() {
                    continue;
                }
                if is_excluded {
                    user_profile.excluded_real_paths.push(resolved);
                } else {
                    user_profile.allowed_real_paths.push(resolved);
                }
            }
            normalize_paths(&mut user_profile.allowed_real_paths);
            normalize_paths(&mut user_profile.excluded_real_paths);
        }

        if let Some(mappings_value) = user_obj.get("path_mappings") {
            let mut index_by_current_path: HashMap<String, usize> = HashMap::new();
            let mut upsert_mapping = |current_raw: &str, target_raw: &str| {
                let resolved_current = resolve_allowed_path_for_user(current_raw, &storage_root);
                if resolved_current.is_empty() {
                    log::warn!(
                        "skip map (current invalid, relative only): user={} path={}",
                        user_id,
                        current_raw
                    );
                    return;
                }

                let resolved_target = resolve_allowed_path_for_user(target_raw, &storage_root);
                if resolved_target.is_empty() {
                    log::warn!(
                        "skip map (target invalid, relative only): user={} path={}",
                        user_id,
                        target_raw
                    );
                    return;
                }

                if resolved_current == resolved_target {
                    return;
                }

                if let Some(&idx) = index_by_current_path.get(&resolved_current) {
                    if let Some(existing) = user_profile.path_mappings.get_mut(idx) {
                        existing.final_path = resolved_target.clone();
                    }
                    log::warn!(
                        "override map (current dup): user={} cur={}",
                        user_id,
                        resolved_current
                    );
                    return;
                }

                index_by_current_path
                    .insert(resolved_current.clone(), user_profile.path_mappings.len());
                user_profile
                    .path_mappings
                    .push(PathMapping::new(resolved_current, resolved_target));
            };

            if mappings_value.is_object() {
                let Some(map) = mappings_value.as_object() else {
                    continue;
                };
                for (current_key, target_value) in map {
                    let Some(target_str) = target_value.as_str() else {
                        continue;
                    };
                    upsert_mapping(current_key, target_str);
                }
            } else if let Some(list) = mappings_value.as_array() {
                for item in list {
                    let Some(obj) = item.as_object() else {
                        continue;
                    };
                    let (Some(current_value), Some(target_value)) =
                        (obj.get("request_path"), obj.get("final_path"))
                    else {
                        continue;
                    };
                    let (Some(current_str), Some(target_str)) =
                        (current_value.as_str(), target_value.as_str())
                    else {
                        continue;
                    };
                    upsert_mapping(current_str, target_str);
                }
            } else {
                log::warn!(
                    "skip mappings (unsupported type): pkg={} user={}",
                    package_name,
                    user_id
                );
            }
        }

        app_profile.user_profiles.insert(user_id, user_profile);
    }

    if app_profile.user_profiles.is_empty() {
        log::warn!("app config users empty, skip: {}", package_name);
        return false;
    }

    state.apps.insert(package_name.to_string(), app_profile);
    if state.should_log_summary {
        log::debug!(
            "app loaded: {} users={}",
            package_name,
            state
                .apps
                .get(package_name)
                .map(|app| app.user_profiles.len())
                .unwrap_or(0)
        );
    }
    true
}

// 仅接受纯数字
fn try_parse_user_id(value: &str) -> Option<i32> {
    if value.is_empty() {
        return None;
    }
    if !value.chars().all(|c| c.is_ascii_digit()) {
        return None;
    }
    let parsed = value.parse::<i32>().ok()?;
    if parsed < 0 { None } else { Some(parsed) }
}

fn normalize_paths(paths: &mut Vec<String>) {
    if paths.is_empty() {
        return;
    }
    paths.sort();
    paths.dedup();
}

// ! 前缀表示排除规则，返回 (is_excluded, 绝对路径)
fn resolve_allowed_path_rule_for_user(
    raw_path: &str,
    storage_root: &str,
) -> Option<(bool, String)> {
    let raw_trimmed = raw_path.trim();
    if raw_trimmed.is_empty() {
        return None;
    }

    let (is_excluded, path_body) = if let Some(stripped) = raw_trimmed.strip_prefix('!') {
        (true, stripped.trim_start())
    } else {
        (false, raw_trimmed)
    };

    let mut normalized = paths::normalize(path_body);
    if normalized.is_empty() {
        return None;
    }
    if is_excluded {
        normalized = normalize_excluded_hidden_segments(&normalized);
    }
    if paths::has_unsafe_segments(&normalized) {
        return None;
    }
    if normalized.starts_with('/') {
        return None;
    }

    normalized = format!("{}/{}", storage_root, normalized);
    normalized = paths::normalize(&normalized);
    if normalized == storage_root {
        return None;
    }
    if !paths::starts_with(&normalized, &format!("{}/", storage_root)) {
        return None;
    }

    Some((is_excluded, normalized))
}

fn normalize_excluded_hidden_segments(path: &str) -> String {
    if !path.contains("!.") {
        return path.to_string();
    }

    path.split('/')
        .map(normalize_excluded_hidden_segment)
        .collect::<Vec<_>>()
        .join("/")
}

fn normalize_excluded_hidden_segment(segment: &str) -> &str {
    if !segment.starts_with("!.") || segment.len() <= 2 {
        return segment;
    }

    let normalized = &segment[1..];
    if normalized == "." || normalized == ".." {
        return segment;
    }
    normalized
}

// 拒绝 ! 排除前缀，只接受普通相对路径
fn resolve_allowed_path_for_user(raw_path: &str, storage_root: &str) -> String {
    let Some((is_excluded, resolved)) = resolve_allowed_path_rule_for_user(raw_path, storage_root)
    else {
        return String::new();
    };
    if is_excluded {
        return String::new();
    }
    resolved
}
