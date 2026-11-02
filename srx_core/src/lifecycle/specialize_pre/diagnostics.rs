use crate::domain::PathMapping;
use crate::java_hook;
use crate::redirect::policy;

pub(super) fn install_java_hook(
    env: *mut jni_sys::JNIEnv,
    is_query_enabled: bool,
    should_install_monitor: bool,
    is_monitor_enabled: bool,
) {
    if !java_hook::is_available() {
        log::info!("java hook skip: hooker dex unavailable");
        return;
    }
    if java_hook::init_once(
        env,
        is_query_enabled,
        should_install_monitor,
        is_monitor_enabled,
    ) {
        log::info!("java hook init ok");
    } else {
        log::warn!("java hook init failed");
    }
}

pub(super) fn should_bypass_file_monitor_ui_process(package_name: &str, nice_name: &str) -> bool {
    policy::is_file_monitor_ui_package(package_name) || is_legacy_file_monitor_ui_process(nice_name)
}

fn is_legacy_file_monitor_ui_process(nice_name: &str) -> bool {
    nice_name == "android.process.mediaUI" || nice_name.ends_with(":PhotoPicker")
}

pub(super) fn log_allowed_real_paths(paths: &[String]) {
    for path in paths {
        log::info!("cfg allow={}", path);
    }
}

pub(super) fn log_excluded_real_paths(paths: &[String]) {
    for path in paths {
        log::info!("cfg excl={}", path);
    }
}

pub(super) fn log_path_mappings(mappings: &[PathMapping]) {
    for mapping in mappings {
        log::info!("cfg map {} -> {}", mapping.request_path, mapping.final_path);
    }
}
