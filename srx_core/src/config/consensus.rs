use super::{AppProfile, UserProfile};
use crate::domain::PathMapping;
use crate::redirect::policy;
use std::collections::HashMap;

pub fn is_shared_group_consistent(
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
