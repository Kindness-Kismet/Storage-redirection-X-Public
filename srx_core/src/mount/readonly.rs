use super::MountPlanner;
use super::rules::reduce_real_path_rules;
use crate::platform::fs;
use std::path::PathBuf;

impl MountPlanner {
    // 排除与映射已挂好后再改为只读，递归复制的子挂载保持原有读写属性
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
            if !self.apply_readonly_aliases(&readonly) {
                return false;
            }
            log::info!("readonly applied {}", readonly);
        }
        true
    }

    fn apply_readonly_aliases(&self, readonly: &str) -> bool {
        let mut handled_targets: Vec<PathBuf> = Vec::new();
        for alias in self.expand_storage_alias_paths(readonly) {
            let is_primary = alias == readonly;
            if !is_primary && !fs::is_directory(&alias) {
                continue;
            }
            // /storage/self/primary 等别名是符号链接，与主路径是同一个挂载点
            let resolved = std::fs::canonicalize(&alias).unwrap_or_else(|_| PathBuf::from(&alias));
            if handled_targets.contains(&resolved) {
                continue;
            }
            if let Err(errno) = self.remount_alias_readonly(readonly, &alias) {
                if is_primary {
                    log::error!("readonly apply failed: {} errno={}", alias, errno);
                    return false;
                }
                log::warn!("readonly alias apply failed: {} errno={}", alias, errno);
                continue;
            }
            handled_targets.push(resolved);
        }
        true
    }

    // 已恢复且未被遮住的目录本身是挂载根，可直接重挂；否则先自绑定，避免改到上层挂载
    fn remount_alias_readonly(&self, readonly: &str, alias: &str) -> Result<(), i32> {
        match self.remount_readonly(alias) {
            Err(libc::EINVAL) => {}
            result => return result,
        }
        if !self.bind_mount(readonly, alias, true) {
            return Err(libc::EINVAL);
        }
        self.remount_readonly(alias)
    }
}
