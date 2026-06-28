use crate::domain::PathMapping;

#[derive(Default)]
pub struct CompanionMountRequest {
    pub pid: i32,
    pub uid: i32,
    pub package_name: String,
    pub app_data_dir: String,
    pub redirect_target: String,
    pub allowed_real_paths: Vec<String>,
    pub path_mappings: Vec<PathMapping>,
    pub is_mapping_mode_only: bool,
}

// 从 JSON 负载解析挂载请求，校验必填字段
pub fn parse_companion_mount_request(payload: &str) -> Result<CompanionMountRequest, String> {
    let mut request = CompanionMountRequest::default();
    let value: serde_json::Value =
        serde_json::from_str(payload).map_err(|e| format!("json parse failed: {}", e))?;

    request.pid = value.get("pid").and_then(|v| v.as_i64()).unwrap_or(-1) as i32;
    request.uid = value.get("uid").and_then(|v| v.as_i64()).unwrap_or(-1) as i32;
    request.package_name = value
        .get("package")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    request.app_data_dir = value
        .get("app_data_dir")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    request.redirect_target = value
        .get("redirect_target")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    request.is_mapping_mode_only = value
        .get("mapping_mode_only")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);

    request.allowed_real_paths = parse_allowed_real_paths(value.get("allowed_real_paths"));
    request.path_mappings = parse_path_mappings(value.get("path_mappings"));

    if request.pid <= 0 || request.uid < 0 || request.package_name.is_empty() {
        return Err("invalid fields: pid/uid/package".to_string());
    }
    if request.redirect_target.is_empty() {
        return Err("invalid field: redirect_target empty".to_string());
    }

    Ok(request)
}

// 从 JSON 数组提取允许路径列表
fn parse_allowed_real_paths(value: Option<&serde_json::Value>) -> Vec<String> {
    let Some(value) = value else {
        return Vec::new();
    };

    if !value.is_array() {
        return Vec::new();
    }

    let mut paths = Vec::new();
    for item in value.as_array().unwrap_or(&Vec::new()) {
        if let Some(path) = item.as_str()
            && !path.is_empty()
        {
            paths.push(path.to_string());
        }
    }
    paths
}

// 从 JSON 对象或数组提取路径映射列表
fn parse_path_mappings(value: Option<&serde_json::Value>) -> Vec<PathMapping> {
    let Some(value) = value else {
        return Vec::new();
    };

    let mut mappings = Vec::new();

    if value.is_object() {
        if let Some(map) = value.as_object() {
            for (current_path, target_value) in map {
                if let Some(target_path) = target_value.as_str()
                    && !current_path.is_empty()
                    && !target_path.is_empty()
                {
                    mappings.push(PathMapping::new(
                        current_path.to_string(),
                        target_path.to_string(),
                    ));
                }
            }
        }
        return mappings;
    }

    if !value.is_array() {
        return mappings;
    }

    for item in value.as_array().unwrap_or(&Vec::new()) {
        if let Some(obj) = item.as_object() {
            let current_path = obj
                .get("request_path")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            let target_path = obj.get("final_path").and_then(|v| v.as_str()).unwrap_or("");
            if !current_path.is_empty() && !target_path.is_empty() {
                mappings.push(PathMapping::new(
                    current_path.to_string(),
                    target_path.to_string(),
                ));
            }
        }
    }

    mappings
}
