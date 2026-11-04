use super::{AppProfile, SettingsHub, UserProfile};
use crate::domain::PathMapping;
use crate::platform::{self, paths};
use crate::redirect::policy;
use std::collections::{HashMap, HashSet};

const SELF_PACKAGE_NAME: &str = "com.storage.redirect.x";

impl SettingsHub {
    pub fn get_merged_path_mappings_for_user(&self, app_uid: i32) -> Vec<PathMapping> {
        let apps = {
            let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
            if !state.is_loaded {
                return Vec::new();
            }
            state.apps.clone()
        };

        let user_id = platform::user_id_from_uid(app_uid);
        let mut merged: Vec<PathMapping> = Vec::new();
        let mut target_by_current: HashMap<String, String> = HashMap::new();
        let mut applied_shared_keys: HashSet<String> = HashSet::new();

        for (package_name, app) in &apps {
            if policy::is_shared_group_package(package_name) {
                let mut members = policy::get_shared_group_members(package_name);
                if members.is_empty() {
                    continue;
                }
                members.sort();
                let group_key = members.join("|");
                if !group_key.is_empty() && applied_shared_keys.contains(&group_key) {
                    continue;
                }
                if !is_shared_group_consistent(&apps, package_name, user_id, true) {
                    continue;
                }
                if !group_key.is_empty() {
                    applied_shared_keys.insert(group_key);
                }
            }

            let user = match app.user_profiles.get(&user_id) {
                Some(user) if user.is_enabled => user,
                _ => continue,
            };

            for mapping in &user.path_mappings {
                if mapping.request_path.is_empty() || mapping.final_path.is_empty() {
                    continue;
                }
                if let Some(existing) = target_by_current.get(&mapping.request_path) {
                    if existing != &mapping.final_path {
                        log::warn!(
                            "user {} map conflict, skip: cur={} old={} new={}",
                            user_id,
                            mapping.request_path,
                            existing,
                            mapping.final_path
                        );
                    }
                    continue;
                }

                target_by_current.insert(mapping.request_path.clone(), mapping.final_path.clone());
                merged.push(mapping.clone());
            }
        }

        merged.sort_by(|a, b| {
            if a.request_path.len() != b.request_path.len() {
                b.request_path.len().cmp(&a.request_path.len())
            } else {
                a.request_path.cmp(&b.request_path)
            }
        });
        merged
    }

    pub fn resolve_redirect_package_by_path_for_user(&self, app_uid: i32, path: &str) -> String {
        let apps = {
            let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
            if !state.is_loaded || path.is_empty() {
                return String::new();
            }
            state.apps.clone()
        };

        let user_id = platform::user_id_from_uid(app_uid);
        let normalized = paths::normalize(path);
        let storage_prefix = format!("/storage/emulated/{}/", user_id);
        if !paths::starts_with(&normalized, &storage_prefix) {
            return String::new();
        }

        let mut matched_package = String::new();
        let mut matched_prefix_len = 0usize;
        let mut is_ambiguous = false;

        for (package_name, app) in &apps {
            if package_name == SELF_PACKAGE_NAME || policy::is_system_writer_package(package_name) {
                continue;
            }

            let user = match app.user_profiles.get(&user_id) {
                Some(user) if user.is_enabled => user,
                _ => continue,
            };

            for mapping in &user.path_mappings {
                if mapping.request_path.is_empty() {
                    continue;
                }

                let is_exact = normalized == mapping.request_path;
                let is_prefix =
                    paths::starts_with(&normalized, &format!("{}/", mapping.request_path));
                if !is_exact && !is_prefix {
                    continue;
                }

                let prefix_len = mapping.request_path.len();
                if prefix_len < matched_prefix_len {
                    continue;
                }
                if prefix_len > matched_prefix_len {
                    matched_package = package_name.to_string();
                    matched_prefix_len = prefix_len;
                    is_ambiguous = false;
                    continue;
                }
                if matched_package != *package_name {
                    is_ambiguous = true;
                }
            }
        }

        if is_ambiguous {
            return String::new();
        }
        matched_package
    }
}

fn is_shared_group_consistent(
    apps: &HashMap<String, AppProfile>,
    package_name: &str,
    user_id: i32,
    should_log_warning: bool,
) -> bool {
    let group_members = policy::get_shared_group_members(package_name);
    if group_members.is_empty() {
        return true;
    }

    let writer_name = policy::get_system_writer_name(package_name);
    let mut active_configs: Vec<(String, UserProfile)> = Vec::new();

    for member in &group_members {
        let app = match apps.get(member) {
            Some(app) => app,
            None => {
                if should_log_warning {
                    log::warn!(
                        "{} shared group missing config: user={} pkg={}",
                        writer_name,
                        user_id,
                        member
                    );
                }
                return false;
            }
        };
        let user = match app.user_profiles.get(&user_id) {
            Some(user) => user,
            None => {
                if should_log_warning {
                    log::warn!(
                        "{} shared group missing user cfg: user={} pkg={}",
                        writer_name,
                        user_id,
                        member
                    );
                }
                return false;
            }
        };
        active_configs.push((member.clone(), user.clone()));
    }

    if active_configs.is_empty() {
        return false;
    }

    let base = &active_configs[0].1;
    for (member_name, member_config) in active_configs.iter().skip(1) {
        if !is_user_profile_equivalent(base, member_config) {
            if should_log_warning {
                log::warn!(
                    "{} shared group inconsistent: user={} pkg={} ref={}",
                    writer_name,
                    user_id,
                    member_name,
                    active_configs[0].0
                );
            }
            return false;
        }
    }

    true
}

// 去空映射，按 request/final 排序以便等价比较
fn normalize_mappings(mappings: &[PathMapping]) -> Vec<PathMapping> {
    let mut normalized: Vec<PathMapping> = mappings
        .iter()
        .filter(|mapping| !mapping.request_path.is_empty() && !mapping.final_path.is_empty())
        .cloned()
        .collect();

    normalized.sort_by(|a, b| {
        if a.request_path != b.request_path {
            a.request_path.cmp(&b.request_path)
        } else {
            a.final_path.cmp(&b.final_path)
        }
    });

    normalized
}

fn is_user_profile_equivalent(left: &UserProfile, right: &UserProfile) -> bool {
    if left.is_enabled != right.is_enabled {
        return false;
    }
    if left.allowed_real_paths != right.allowed_real_paths {
        return false;
    }
    if left.excluded_real_paths != right.excluded_real_paths {
        return false;
    }
    if left.readonly_real_paths != right.readonly_real_paths {
        return false;
    }

    let left_mappings = normalize_mappings(&left.path_mappings);
    let right_mappings = normalize_mappings(&right.path_mappings);
    if left_mappings.len() != right_mappings.len() {
        return false;
    }

    left_mappings
        .iter()
        .zip(right_mappings.iter())
        .all(|(l, r)| l.request_path == r.request_path && l.final_path == r.final_path)
}
