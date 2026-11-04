use super::{SettingsHub, UserProfile};
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

    pub fn is_user_profile_enabled_in_memory(&self, package_name: &str, user_id: i32) -> bool {
        let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
        if !state.is_loaded || package_name == SELF_PACKAGE_NAME || user_id < 0 {
            return false;
        }
        state
            .apps
            .get(package_name)
            .and_then(|app| app.user_profiles.get(&user_id))
            .map(|user| user.is_enabled)
            .unwrap_or(false)
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
        if matches!(
            package_name,
            SELF_PACKAGE_NAME | "android" | "com.android.systemui" | "system_server"
        ) || platform::user_id_from_uid(app_uid) != 0
        {
            return false;
        }

        let (is_loaded, is_file_monitor_enabled) = {
            let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
            (state.is_loaded, state.is_file_monitor_enabled)
        };

        if !is_loaded || !is_file_monitor_enabled {
            return false;
        }

        if policy::is_system_writer_package(package_name)
            || policy::is_shared_uid_process(app_uid)
            || package_name == "com.android.providers.downloads"
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
        self.enabled_user_paths(package_name, app_uid, |user| &user.allowed_real_paths)
    }

    pub fn get_excluded_real_paths(&self, package_name: &str, app_uid: i32) -> Vec<String> {
        self.enabled_user_paths(package_name, app_uid, |user| &user.excluded_real_paths)
    }

    pub fn get_readonly_real_paths(&self, package_name: &str, app_uid: i32) -> Vec<String> {
        self.enabled_user_paths(package_name, app_uid, |user| &user.readonly_real_paths)
    }

    fn enabled_user_paths(
        &self,
        package_name: &str,
        app_uid: i32,
        select: impl FnOnce(&UserProfile) -> &Vec<String>,
    ) -> Vec<String> {
        let state = self.state.lock().unwrap_or_else(|err| err.into_inner());
        if !state.is_loaded || package_name == SELF_PACKAGE_NAME {
            return Vec::new();
        }
        let Some(app) = state.apps.get(package_name) else {
            return Vec::new();
        };
        let user_id = platform::user_id_from_uid(app_uid);
        match app.user_profiles.get(&user_id) {
            Some(user) if user.is_enabled => select(user).clone(),
            _ => Vec::new(),
        }
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
}
