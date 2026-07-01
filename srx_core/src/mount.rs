use crate::platform::{self, paths};
mod alias;
mod apply;
mod core;
mod map;

pub struct MountPlanner {
    should_unshare: bool,
    is_namespace_ready: bool,
    package_name: String,
    app_uid: i32,
    user_id: i32,
    app_data_dir: String,
    redirect_target: String,
}

#[derive(Copy, Clone)]
enum PrimaryMountFailure {
    AbortAll,
    StopCurrentTarget,
}

impl MountPlanner {
    pub fn new(
        package_name: &str,
        app_uid: i32,
        app_data_dir: &str,
        redirect_target: &str,
        should_unshare: bool,
    ) -> Self {
        let user_id = platform::user_id_from_uid(app_uid);
        log::debug!(
            "mount redirect pkg={} uid={} user={}",
            package_name,
            app_uid,
            user_id
        );
        Self {
            should_unshare,
            is_namespace_ready: false,
            package_name: package_name.to_string(),
            app_uid,
            user_id,
            app_data_dir: app_data_dir.to_string(),
            redirect_target: paths::normalize(redirect_target),
        }
    }
}
