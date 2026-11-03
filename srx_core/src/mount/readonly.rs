use super::rules::reduce_real_path_rules;
use super::{MountPlanner, PrimaryMountFailure};
use crate::platform::fs;

impl MountPlanner {
    // 排除与映射已挂好后再自绑定只读层，递归复制的子挂载保持原有读写属性
    pub(super) fn apply_readonly_real_paths(
        &self,
        readonly_rules: &[String],
        storage_path: &str,
    ) -> bool {
        let readonly_paths =
            self.expand_storage_view_rules(readonly_rules, storage_path, "readonly");
        for readonly in reduce_real_path_rules(readonly_paths) {
            // 目录不存在时建空占位，使应用无法再创建同名目录
            if !fs::is_directory(&readonly) {
                if !self.ensure_directory_exists(&readonly, true) {
                    log::error!("readonly placeholder mkdir failed: {}", readonly);
                    return false;
                }
                log::info!("readonly placeholder created {}", readonly);
            }
            if !self.bind_mount_with_storage_aliases(
                &readonly,
                &readonly,
                true,
                PrimaryMountFailure::AbortAll,
                Some("readonly bind failed"),
                Some("readonly alias bind failed"),
                Some("readonly alias bind ok"),
                None,
            ) {
                return false;
            }
            if !self.remount_readonly(&readonly) {
                return false;
            }
            for alias in self.expand_storage_alias_paths(&readonly) {
                if alias != readonly && fs::is_directory(&alias) {
                    let _ = self.remount_readonly(&alias);
                }
            }
            log::info!("readonly applied {}", readonly);
        }
        true
    }
}
