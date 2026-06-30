use super::{EnabledPathMappingSnapshot, ResolvedUserProfileSnapshot, SettingsHub};
use crate::domain::PathMapping;
use crate::platform;
use crate::redirect::policy;
use std::sync::atomic::Ordering;

const SELF_PACKAGE_NAME: &str = "com.storage.redirect.x";

impl SettingsHub {
    pub fn is_file_monitor_enabled(&self) -> bool {
        let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
        state.is_file_monitor_enabled
    }

    pub fn is_fuse_fixer_enabled(&self) -> bool {
        self.is_fuse_fixer_enabled.load(Ordering::Relaxed)
    }

    pub fn should_redirect(&self, package_name: &str, app_uid: i32) -> bool {
        let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
        if !state.is_loaded || package_name == SELF_PACKAGE_NAME {
            return false;
        }
        let app = match state.apps.get(package_name) {
            Some(app) => app,
            None => return false,
        };
        let user_id = platform::user_id_from_uid(app_uid);
        let user = match app.user_profiles.get(&user_id) {
            Some(user) => user,
            None => return false,
        };
        user.is_enabled
    }

    pub fn should_monitor(&self, package_name: &str, app_uid: i32) -> bool {
        if package_name == SELF_PACKAGE_NAME {
            return false;
        }

        let (is_loaded, is_file_monitor_enabled) = {
            let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
            (state.is_loaded, state.is_file_monitor_enabled)
        };

        if !is_loaded || !is_file_monitor_enabled {
            return false;
        }

        if policy::is_system_writer_package(package_name) || policy::is_shared_uid_process(app_uid)
        {
            log::info!(
                "monitor on: writer proc pkg={} uid={}",
                package_name,
                app_uid
            );
            return true;
        }

        false
    }

    pub fn get_allowed_real_paths(&self, package_name: &str, app_uid: i32) -> Vec<String> {
        let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
        if !state.is_loaded || package_name == SELF_PACKAGE_NAME {
            return Vec::new();
        }
        let app = match state.apps.get(package_name) {
            Some(app) => app,
            None => return Vec::new(),
        };
        let user_id = platform::user_id_from_uid(app_uid);
        let user = match app.user_profiles.get(&user_id) {
            Some(user) => user,
            None => return Vec::new(),
        };
        if !user.is_enabled {
            return Vec::new();
        }
        user.allowed_real_paths.clone()
    }

    pub fn get_excluded_real_paths(&self, package_name: &str, app_uid: i32) -> Vec<String> {
        let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
        if !state.is_loaded || package_name == SELF_PACKAGE_NAME {
            return Vec::new();
        }
        let app = match state.apps.get(package_name) {
            Some(app) => app,
            None => return Vec::new(),
        };
        let user_id = platform::user_id_from_uid(app_uid);
        let user = match app.user_profiles.get(&user_id) {
            Some(user) => user,
            None => return Vec::new(),
        };
        if !user.is_enabled {
            return Vec::new();
        }
        user.excluded_real_paths.clone()
    }

    pub fn get_path_mappings(&self, package_name: &str, app_uid: i32) -> Vec<PathMapping> {
        let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
        if !state.is_loaded || package_name == SELF_PACKAGE_NAME {
            return Vec::new();
        }
        let app = match state.apps.get(package_name) {
            Some(app) => app,
            None => return Vec::new(),
        };
        let user_id = platform::user_id_from_uid(app_uid);
        let user = match app.user_profiles.get(&user_id) {
            Some(user) => user,
            None => return Vec::new(),
        };
        if !user.is_enabled {
            return Vec::new();
        }
        user.path_mappings.clone()
    }

    pub fn get_resolved_user_profile_snapshot(
        &self,
        package_name: &str,
        app_uid: i32,
    ) -> Option<ResolvedUserProfileSnapshot> {
        let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
        if !state.is_loaded || package_name == SELF_PACKAGE_NAME {
            return None;
        }
        let app = state.apps.get(package_name)?;
        let user_id = platform::user_id_from_uid(app_uid);
        let user = app.user_profiles.get(&user_id)?;
        if !user.is_enabled {
            return None;
        }

        Some(ResolvedUserProfileSnapshot {
            user_id,
            redirect_target: format!(
                "/storage/emulated/{}/Android/data/{}/sdcard",
                user_id, package_name
            ),
            allowed_real_paths: user.allowed_real_paths.clone(),
            excluded_real_paths: user.excluded_real_paths.clone(),
            path_mappings: user.path_mappings.clone(),
            is_mapping_mode_only: false,
        })
    }

    pub fn has_enabled_redirect_apps_for_user(&self, app_uid: i32) -> bool {
        let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
        if !state.is_loaded {
            return false;
        }
        let user_id = platform::user_id_from_uid(app_uid);
        for app in state.apps.values() {
            if let Some(user) = app.user_profiles.get(&user_id)
                && user.is_enabled
            {
                return true;
            }
        }
        false
    }

    pub fn get_app_count(&self) -> usize {
        let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
        state.apps.len()
    }

    pub fn get_enabled_path_mapping_snapshots(&self) -> Vec<EnabledPathMappingSnapshot> {
        let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
        if !state.is_loaded {
            return Vec::new();
        }

        let mut snapshots = Vec::new();
        for (package_name, app) in &state.apps {
            if package_name == SELF_PACKAGE_NAME || policy::is_system_writer_package(package_name) {
                continue;
            }

            let package_uid = policy::get_uid_for_package(package_name);
            if package_uid < platform::ANDROID_APP_UID_START {
                continue;
            }
            let app_id = package_uid % platform::ANDROID_USER_ID_OFFSET;

            for (user_id, user) in &app.user_profiles {
                if !user.is_enabled || user.path_mappings.is_empty() || *user_id < 0 {
                    continue;
                }
                let app_uid = *user_id * platform::ANDROID_USER_ID_OFFSET + app_id;
                snapshots.push(EnabledPathMappingSnapshot {
                    package_name: package_name.clone(),
                    user_id: *user_id,
                    app_uid,
                    path_mappings: user.path_mappings.clone(),
                });
            }
        }
        snapshots
    }
}
