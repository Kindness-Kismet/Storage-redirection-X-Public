use super::SettingsHub;
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
                if !super::consensus::is_shared_group_consistent(&apps, package_name, user_id, true)
                {
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
