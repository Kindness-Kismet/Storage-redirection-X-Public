use super::rules::reduce_real_path_rules;
use super::{MountPlanner, PrimaryMountFailure};
use crate::platform::paths;

impl MountPlanner {
    pub(super) fn apply_excluded_real_paths(
        &self,
        excluded_rules: &[String],
        restored_real_paths: &[String],
        storage_path: &str,
        redirect_backend: &str,
    ) -> bool {
        let storage_prefix = format!("{}/", storage_path);
        let mut excluded_paths =
            self.expand_storage_view_rules(excluded_rules, storage_path, "exclude");
        // 未被真实目录覆盖的路径已位于沙盒，只处理与恢复挂载重叠的排除项。
        excluded_paths.retain(|excluded| {
            restored_real_paths.iter().any(|restored| {
                paths::matches(restored, excluded, true) || paths::matches(excluded, restored, true)
            })
        });
        for excluded in reduce_real_path_rules(excluded_paths) {
            let relative = &excluded[storage_prefix.len()..];
            let source = paths::join(redirect_backend, relative);
            if !self.ensure_writable_mapped_directory(&source, self.app_uid)
                || !self.ensure_directory_exists(&excluded, true)
            {
                log::error!("exclude directory prep failed: {}", excluded);
                return false;
            }
            if !self.bind_mount_with_storage_aliases(
                &source,
                &excluded,
                true,
                PrimaryMountFailure::AbortAll,
                Some("exclude mount failed"),
                Some("exclude alias mount failed"),
                Some("exclude alias mount ok"),
                None,
            ) {
                return false;
            }
            log::info!("exclude redirected {}", excluded);
        }
        true
    }
}
