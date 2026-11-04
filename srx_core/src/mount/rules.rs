use super::MountPlanner;
use crate::platform::{fs, paths};
use std::fs as std_fs;

pub(super) const REAL_PATH_WILDCARD_EXPAND_LIMIT: usize = 512;

impl MountPlanner {
    // 按应用当前视图展开规则，通配符只匹配此刻已存在的目录；结果已排序去重
    pub(super) fn expand_storage_view_rules(
        &self,
        rules: &[String],
        storage_path: &str,
        label: &str,
    ) -> Vec<String> {
        let storage_prefix = format!("{}/", storage_path);
        let mut expanded_paths = Vec::new();
        for rule in rules {
            let resolved =
                self.resolve_user_path(&self.resolve_placeholders(&self.normalize_path(rule)));
            if !resolved.starts_with(&storage_prefix) || paths::has_unsafe_segments(&resolved) {
                continue;
            }
            if !has_path_wildcard(&resolved) {
                expanded_paths.push(resolved);
                continue;
            }
            let expanded = Self::expand_real_path_wildcard(&resolved, storage_path, storage_path);
            if expanded.len() >= REAL_PATH_WILDCARD_EXPAND_LIMIT {
                log::warn!(
                    "{} wildcard limit path={} limit={}",
                    label,
                    resolved,
                    REAL_PATH_WILDCARD_EXPAND_LIMIT
                );
            }
            expanded_paths.extend(expanded);
        }
        expanded_paths.sort();
        expanded_paths.dedup();
        expanded_paths
    }

    pub(super) fn expand_real_path_wildcard(
        rule_path: &str,
        storage_path: &str,
        real_base_path: &str,
    ) -> Vec<String> {
        let Some(mut relative) = rule_path.strip_prefix(storage_path) else {
            return Vec::new();
        };
        if let Some(stripped) = relative.strip_prefix('/') {
            relative = stripped;
        }
        if relative.is_empty() {
            return Vec::new();
        }

        let segments: Vec<&str> = relative
            .split('/')
            .filter(|segment| !segment.is_empty())
            .collect();
        if segments.is_empty() {
            return Vec::new();
        }

        let mut expanded = Vec::new();
        Self::expand_real_path_segments(real_base_path, storage_path, &segments, 0, &mut expanded);
        expanded.sort();
        expanded.dedup();
        expanded
    }

    fn expand_real_path_segments(
        real_current: &str,
        storage_current: &str,
        segments: &[&str],
        index: usize,
        expanded: &mut Vec<String>,
    ) {
        if expanded.len() >= REAL_PATH_WILDCARD_EXPAND_LIMIT {
            return;
        }

        if index >= segments.len() {
            if fs::is_directory(real_current) {
                expanded.push(storage_current.to_string());
            }
            return;
        }

        let segment = segments[index];
        if has_path_wildcard(segment) {
            let Ok(entries) = std_fs::read_dir(real_current) else {
                return;
            };
            for entry in entries.flatten() {
                if expanded.len() >= REAL_PATH_WILDCARD_EXPAND_LIMIT {
                    return;
                }
                let Ok(file_type) = entry.file_type() else {
                    continue;
                };
                if !file_type.is_dir() || file_type.is_symlink() {
                    continue;
                }

                let name = entry.file_name().to_string_lossy().to_string();
                if !paths::matches(segment, &name, false) {
                    continue;
                }

                let real_next = paths::join(real_current, &name);
                let storage_next = paths::join(storage_current, &name);
                Self::expand_real_path_segments(
                    &real_next,
                    &storage_next,
                    segments,
                    index + 1,
                    expanded,
                );
            }
            return;
        }

        let real_next = paths::join(real_current, segment);
        if !fs::is_directory(&real_next) {
            return;
        }
        let storage_next = paths::join(storage_current, segment);
        Self::expand_real_path_segments(&real_next, &storage_next, segments, index + 1, expanded);
    }
}

pub(super) fn reduce_real_path_rules(rules: Vec<String>) -> Vec<String> {
    let mut effective: Vec<String> = Vec::with_capacity(rules.len());
    for rule in rules {
        let mut is_redundant = false;
        for kept in &effective {
            if paths::matches(kept, &rule, true) {
                is_redundant = true;
                break;
            }
        }
        if !is_redundant {
            effective.push(rule);
        }
    }
    effective
}

pub(super) fn has_path_wildcard(path: &str) -> bool {
    path.contains('*') || path.contains('?')
}
