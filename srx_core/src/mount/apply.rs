use super::MountPlanner;
use super::rules::{REAL_PATH_WILDCARD_EXPAND_LIMIT, has_path_wildcard, reduce_real_path_rules};
use crate::domain::PathMapping;
use crate::platform::{fs, paths};

impl MountPlanner {
    pub fn apply_sdcard_redirect(
        &mut self,
        allowed_real_paths: &[String],
        excluded_real_paths: &[String],
        readonly_real_paths: &[String],
        path_mappings: &[PathMapping],
    ) -> bool {
        let resolved_target_storage =
            self.resolve_user_path(&self.normalize_path(&self.redirect_target));
        if resolved_target_storage.is_empty() {
            log::error!("redirect target empty");
            return false;
        }

        let resolved_target = self.to_data_media_backend_path(&resolved_target_storage);
        if resolved_target.is_empty() {
            log::error!(
                "redirect target not under storage/emulated: {}",
                resolved_target_storage
            );
            return false;
        }

        log::info!("redirect target={}", resolved_target_storage);
        log::debug!("redirect backend={}", resolved_target);
        log::info!(
            "mount args pkg={} uid={} user={} allow={} excl={} ro={} map={}",
            self.package_name,
            self.app_uid,
            self.user_id,
            allowed_real_paths.len(),
            excluded_real_paths.len(),
            readonly_real_paths.len(),
            path_mappings.len()
        );

        if !self.ensure_mount_namespace_prepared() {
            log::error!("mount ns init failed");
            return false;
        }

        let storage_path = format!("/storage/emulated/{}", self.user_id);
        let android_path = format!("{}/Android", storage_path);
        log::info!(
            "key mounts storage={} android={}",
            storage_path,
            android_path
        );

        let real_storage_anchor = format!("/storage/.srx_anchor_{}", self.user_id);
        let mut is_real_storage_anchor_ready = false;
        if self.ensure_directory_exists(&real_storage_anchor, false) {
            for source_candidate in self.expand_storage_alias_paths(&storage_path) {
                if !fs::is_directory(&source_candidate) {
                    continue;
                }
                if self.bind_mount(&source_candidate, &real_storage_anchor, true) {
                    is_real_storage_anchor_ready = true;
                    log::info!(
                        "real storage anchored {} -> {}",
                        source_candidate,
                        real_storage_anchor
                    );
                    break;
                }
            }
        }
        if !is_real_storage_anchor_ready {
            log::warn!(
                "real storage anchor failed, fallback to data/media: {}",
                real_storage_anchor
            );
        }

        let cleanup_anchor = |ready: bool| {
            if ready {
                self.detach_mount(&real_storage_anchor);
                let _ = fs::remove_directory(&real_storage_anchor);
            }
        };

        let data_media_root = format!("/data/media/{}", self.user_id);
        if !fs::is_directory(&data_media_root) {
            cleanup_anchor(is_real_storage_anchor_ready);
            log::error!("data/media missing: {}", data_media_root);
            return false;
        }

        let android_source_path = format!("{}/Android", data_media_root);
        if !self.ensure_directory_exists(&resolved_target, false) {
            cleanup_anchor(is_real_storage_anchor_ready);
            log::error!("mkdir redirect failed: {}", resolved_target);
            return false;
        }

        if !self.ensure_writable_mapped_directory(&resolved_target, self.app_uid) {
            log::warn!("fix redirect root perm failed: {}", resolved_target);
        }
        self.repair_redirect_target_directories(&resolved_target);

        let redirect_android_path = format!("{}/Android", resolved_target);
        if !self.ensure_directory_exists(&redirect_android_path, true) {
            cleanup_anchor(is_real_storage_anchor_ready);
            log::error!("mkdir android placeholder failed");
            return false;
        }

        let mut is_storage_redirect_applied = false;
        if !self.bind_mount_with_storage_aliases(
            &resolved_target,
            &storage_path,
            true,
            super::PrimaryMountFailure::AbortAll,
            Some("storage main mount failed"),
            Some("storage alias mount failed"),
            Some("storage alias mount ok"),
            Some(&mut is_storage_redirect_applied),
        ) {
            cleanup_anchor(is_real_storage_anchor_ready);
            log::error!("storage redirect failed");
            return false;
        }

        if !is_storage_redirect_applied {
            cleanup_anchor(is_real_storage_anchor_ready);
            log::error!("storage redirect failed (no mount point)");
            return false;
        }

        let mut is_android_restore_applied = false;
        if !self.bind_mount_with_storage_aliases(
            &android_source_path,
            &android_path,
            true,
            super::PrimaryMountFailure::AbortAll,
            Some("android restore failed"),
            Some("android alias restore failed"),
            Some("android alias restore ok"),
            Some(&mut is_android_restore_applied),
        ) {
            cleanup_anchor(is_real_storage_anchor_ready);
            log::error!("android restore failed");
            return false;
        }
        if !is_android_restore_applied {
            cleanup_anchor(is_real_storage_anchor_ready);
            log::error!("android restore failed (no mount point)");
            return false;
        }

        let mut restored_real_paths = vec![android_path];
        if !allowed_real_paths.is_empty() {
            let real_base_path = if is_real_storage_anchor_ready {
                real_storage_anchor.as_str()
            } else {
                data_media_root.as_str()
            };

            let mut resolved_paths: Vec<String> = Vec::with_capacity(allowed_real_paths.len());
            for path in allowed_real_paths {
                let resolved =
                    self.resolve_user_path(&self.resolve_placeholders(&self.normalize_path(path)));
                if resolved.is_empty() {
                    continue;
                }
                if paths::has_unsafe_segments(&resolved) {
                    continue;
                }
                if resolved == storage_path {
                    log::warn!("skip allow (whole storage not supported): {}", resolved);
                    continue;
                }
                if !paths::starts_with(&resolved, &format!("{}/", storage_path)) {
                    log::warn!("skip allow (not under storage): {}", resolved);
                    continue;
                }
                resolved_paths.push(resolved);
            }

            resolved_paths.sort();
            resolved_paths.dedup();

            let effective_rules = reduce_real_path_rules(resolved_paths);
            let mut effective_paths: Vec<String> = Vec::with_capacity(effective_rules.len());
            for rule in effective_rules {
                if has_path_wildcard(&rule) {
                    let expanded =
                        Self::expand_real_path_wildcard(&rule, &storage_path, real_base_path);
                    if expanded.is_empty() {
                        log::warn!("allow wildcard no match: {}", rule);
                    } else if expanded.len() >= REAL_PATH_WILDCARD_EXPAND_LIMIT {
                        log::warn!(
                            "allow wildcard limit path={} limit={}",
                            rule,
                            REAL_PATH_WILDCARD_EXPAND_LIMIT
                        );
                    }
                    effective_paths.extend(expanded);
                } else {
                    effective_paths.push(rule);
                }
            }
            effective_paths.sort();
            effective_paths.dedup();

            for allowed_path in effective_paths {
                let mut relative = allowed_path[storage_path.len()..].to_string();
                if relative.starts_with('/') {
                    relative.remove(0);
                }
                if relative.is_empty() {
                    continue;
                }

                let real_source = format!("{}/{}", real_base_path, relative);
                if is_real_storage_anchor_ready {
                    if !fs::is_directory(&real_source) {
                        log::warn!("real path missing, skip: {}", real_source);
                        continue;
                    }
                } else if !self.ensure_writable_mapped_directory(&real_source, self.app_uid) {
                    log::warn!("real path missing and mkdir failed: {}", real_source);
                    continue;
                }

                if !self.ensure_directory_exists(&allowed_path, true) {
                    log::warn!("mkdir allow failed: {}", allowed_path);
                    continue;
                }

                let mut is_restored_allowed_path = false;
                let _ = self.bind_mount_with_storage_aliases(
                    &real_source,
                    &allowed_path,
                    true,
                    super::PrimaryMountFailure::StopCurrentTarget,
                    None,
                    Some("allow alias restore failed"),
                    Some("allow alias restore ok"),
                    Some(&mut is_restored_allowed_path),
                );
                if is_restored_allowed_path {
                    log::info!("allow restored {}", allowed_path);
                    restored_real_paths.push(allowed_path);
                }
            }
        }

        cleanup_anchor(is_real_storage_anchor_ready);

        // 排除覆盖恢复的真实目录，显式映射随后覆盖排除，保持与路径路由一致。
        if !self.apply_excluded_real_paths(
            excluded_real_paths,
            &restored_real_paths,
            &storage_path,
            &resolved_target,
        ) {
            return false;
        }

        if !path_mappings.is_empty() {
            let resolved_mappings = self.resolve_path_mappings(path_mappings, &storage_path);
            log::info!(
                "map resolve in={} effective={}",
                path_mappings.len(),
                resolved_mappings.len()
            );
            let mut is_any_applied = false;
            let _ = self.apply_resolved_path_mappings(
                &resolved_mappings,
                &storage_path,
                &data_media_root,
                true,
                false,
                Some(&mut is_any_applied),
            );
            let _ = is_any_applied;
        }

        if !self.apply_readonly_real_paths(readonly_real_paths, &storage_path) {
            return false;
        }

        log::info!("redirect done");
        true
    }

    pub fn apply_path_mappings_only(&mut self, path_mappings: &[PathMapping]) -> bool {
        if !self.ensure_mount_namespace_prepared() {
            log::error!("mount ns init failed");
            return false;
        }

        if path_mappings.is_empty() {
            log::info!("map-only: no mappings, skip");
            return true;
        }

        let storage_path = format!("/storage/emulated/{}", self.user_id);
        let data_media_root = format!("/data/media/{}", self.user_id);
        if !fs::is_directory(&data_media_root) {
            log::error!("data/media missing: {}", data_media_root);
            return false;
        }

        let resolved_mappings = self.resolve_path_mappings(path_mappings, &storage_path);
        log::info!(
            "map-only resolve in={} effective={}",
            path_mappings.len(),
            resolved_mappings.len()
        );

        let mut is_any_applied = false;
        let _ = self.apply_resolved_path_mappings(
            &resolved_mappings,
            &storage_path,
            &data_media_root,
            false,
            false,
            Some(&mut is_any_applied),
        );

        if !is_any_applied {
            log::warn!("map-only: nothing applied");
        } else {
            log::info!("map-only done");
        }

        true
    }
}
