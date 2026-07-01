use super::MountPlanner;
use crate::domain::PathMapping;
use crate::platform::{fs, paths};

impl MountPlanner {
    pub(super) fn resolve_path_mappings(
        &self,
        path_mappings: &[PathMapping],
        storage_path: &str,
    ) -> Vec<PathMapping> {
        let mut resolved = Vec::with_capacity(path_mappings.len());

        for mapping in path_mappings {
            let mut current_path = self.resolve_user_path(
                &self.resolve_placeholders(&self.normalize_path(&mapping.request_path)),
            );
            let mut target_path = self.resolve_user_path(
                &self.resolve_placeholders(&self.normalize_path(&mapping.final_path)),
            );

            if current_path.is_empty() || target_path.is_empty() {
                continue;
            }
            if paths::has_unsafe_segments(&current_path) || paths::has_unsafe_segments(&target_path)
            {
                continue;
            }

            if !paths::is_absolute(&current_path) {
                current_path = self.normalize_path(&paths::join(storage_path, &current_path));
            }
            if !paths::is_absolute(&target_path) {
                target_path = self.normalize_path(&paths::join(storage_path, &target_path));
            }

            if current_path == storage_path || target_path == storage_path {
                log::warn!(
                    "skip map (whole storage not supported): cur={} tgt={}",
                    current_path,
                    target_path
                );
                continue;
            }

            if !paths::starts_with(&current_path, &format!("{}/", storage_path))
                || !paths::starts_with(&target_path, &format!("{}/", storage_path))
            {
                log::warn!(
                    "skip map (not under storage): cur={} tgt={}",
                    current_path,
                    target_path
                );
                continue;
            }

            if current_path == target_path {
                continue;
            }

            resolved.push(PathMapping::new(current_path, target_path));
        }

        resolved.sort_by(|a, b| {
            if a.request_path.len() != b.request_path.len() {
                a.request_path.len().cmp(&b.request_path.len())
            } else {
                a.request_path.cmp(&b.request_path)
            }
        });

        resolved
    }

    pub(super) fn apply_resolved_path_mappings(
        &self,
        resolved_mappings: &[PathMapping],
        storage_path: &str,
        target_source_root: &str,
        should_chown_current_dirs: bool,
        should_use_existing_target_source_only: bool,
        is_any_applied_out: Option<&mut bool>,
    ) -> bool {
        let mut is_any_applied = false;

        for mapping in resolved_mappings {
            let mut target_relative = mapping.final_path[storage_path.len()..].to_string();
            if target_relative.starts_with('/') {
                target_relative.remove(0);
            }
            if target_relative.is_empty() {
                continue;
            }

            let target_source = format!("{}/{}", target_source_root, target_relative);

            if should_use_existing_target_source_only {
                if !fs::is_directory(&target_source) {
                    let target_data_media =
                        format!("/data/media/{}/{}", self.user_id, target_relative);
                    if !self.ensure_writable_mapped_directory(&target_data_media, self.app_uid) {
                        log::warn!("map target missing and mkdir failed: {}", target_source);
                        continue;
                    }
                }
            } else if !self.ensure_writable_mapped_directory(&target_source, self.app_uid) {
                log::warn!("target missing and mkdir failed: {}", target_source);
                continue;
            }

            if !self.ensure_directory_exists(&mapping.request_path, should_chown_current_dirs) {
                let mut current_relative = mapping.request_path[storage_path.len()..].to_string();
                if current_relative.starts_with('/') {
                    current_relative.remove(0);
                }
                let data_media_fallback =
                    format!("/data/media/{}/{}", self.user_id, current_relative);
                if !self.ensure_directory_exists(&data_media_fallback, should_chown_current_dirs) {
                    log::warn!("mkdir map current failed: {}", mapping.request_path);
                    continue;
                }
            }

            let mut is_current_path_mounted = false;
            let _ = self.bind_mount_with_storage_aliases(
                &target_source,
                &mapping.request_path,
                true,
                super::PrimaryMountFailure::StopCurrentTarget,
                None,
                Some("map alias mount failed"),
                Some("map alias ok"),
                Some(&mut is_current_path_mounted),
            );

            if is_current_path_mounted {
                is_any_applied = true;
                log::info!("map {} -> {}", mapping.request_path, mapping.final_path);
            }
        }

        if let Some(out) = is_any_applied_out {
            *out = is_any_applied;
        }
        true
    }
}
