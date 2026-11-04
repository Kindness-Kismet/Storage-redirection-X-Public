use crate::domain::PathMapping;
use crate::platform::fs;
use crate::platform::unique_fd::UniqueFd;
use crate::zygisk::abi;

#[allow(clippy::too_many_arguments)]
pub(super) fn build_companion_request_payload(
    pid: i32,
    uid: i32,
    package_name: &str,
    app_data_dir: &str,
    redirect_target: &str,
    allowed_real_paths: &[String],
    excluded_real_paths: &[String],
    readonly_real_paths: &[String],
    path_mappings: &[PathMapping],
    is_mapping_mode_only: bool,
) -> String {
    let mut mappings = Vec::new();
    for mapping in path_mappings {
        mappings.push(serde_json::json!({
            "request_path": mapping.request_path,
            "final_path": mapping.final_path,
        }));
    }

    let payload = serde_json::json!({
        "pid": pid,
        "uid": uid,
        "package": package_name,
        "app_data_dir": app_data_dir,
        "redirect_target": redirect_target,
        "allowed_real_paths": allowed_real_paths,
        "excluded_real_paths": excluded_real_paths,
        "readonly_real_paths": readonly_real_paths,
        "mapping_mode_only": is_mapping_mode_only,
        "path_mappings": mappings,
    });

    serde_json::to_string(&payload).unwrap_or_default()
}

// pre 发送后保留 socket，post 读取挂载结果并关闭。
pub(super) fn send_companion_request_payload(
    api: Option<&abi::Api>,
    args: &abi::AppSpecializeArgs,
    payload: &str,
) -> Option<UniqueFd> {
    let api = api?;
    if payload.is_empty() {
        return None;
    }

    let fd = api.connect_companion();
    if fd < 0 {
        log::warn!("companion connect failed");
        return None;
    }

    let socket = UniqueFd::new(fd);
    if !api.exempt_app_fd(fd, args) {
        log::warn!("companion fd exemption failed fd={}", fd);
        return None;
    }

    let payload_len = payload.len() as u32;
    let sent =
        fs::write_all(fd, &payload_len.to_ne_bytes()) && fs::write_all(fd, payload.as_bytes());
    if !sent {
        log::warn!("companion send failed");
        return None;
    }

    log::info!("companion req sent fd={}", fd);
    Some(socket)
}
