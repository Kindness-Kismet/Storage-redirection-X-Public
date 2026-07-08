use std::string::String;

// 合并斜杠、去尾斜杠，再逐层解析存储别名
pub fn normalize(path: &str) -> String {
    if path.is_empty() {
        return path.to_string();
    }

    let mut result = String::with_capacity(path.len());
    let mut is_last_slash = false;
    for ch in path.chars() {
        if ch == '/' {
            if !is_last_slash {
                result.push('/');
                is_last_slash = true;
            }
        } else {
            result.push(ch);
            is_last_slash = false;
        }
    }

    if result.len() > 1 && result.ends_with('/') {
        result.pop();
    }

    let result = resolve_sdcard_alias(&result);
    let result = resolve_self_primary_alias(&result);
    let result = resolve_mnt_user_primary_alias(&result);
    let result = resolve_mnt_runtime_alias(&result);
    let result = resolve_mnt_emulated_alias(&result);
    resolve_data_media_alias(&result)
}

pub fn resolve_user_path(path: &str, user_id: i32) -> String {
    if user_id == 0 {
        return path.to_string();
    }

    let mut resolved = path.to_string();
    let user_str = user_id.to_string();
    resolved = resolved.replace(
        "/storage/emulated/0/",
        &format!("/storage/emulated/{}/", user_str),
    );
    if resolved == "/storage/emulated/0" {
        resolved = format!("/storage/emulated/{}", user_str);
    }

    resolved = resolved.replace("/data/user/0/", &format!("/data/user/{}/", user_str));
    resolved
}

// 替换 ${APP_DATA_DIR} / ${REDIRECT_TARGET} 占位符
pub fn resolve_placeholders(path: &str, app_data_dir: &str, redirect_target: &str) -> String {
    let mut resolved = path.to_string();
    if !app_data_dir.is_empty() {
        resolved = resolved.replace("${APP_DATA_DIR}", app_data_dir);
        resolved = resolved.replace("$APP_DATA_DIR", app_data_dir);
    }

    if !redirect_target.is_empty() {
        resolved = resolved.replace("${REDIRECT_TARGET}", redirect_target);
        resolved = resolved.replace("$REDIRECT_TARGET", redirect_target);
    }

    resolved
}

pub fn starts_with(path: &str, prefix: &str) -> bool {
    path.starts_with(prefix)
}

// is_recursive 允许 target 深入 rule 之下任意层级
pub fn matches(rule_path: &str, target_path: &str, is_recursive: bool) -> bool {
    if rule_path.is_empty() || target_path.is_empty() {
        return false;
    }
    if !rule_path.contains('*') && !rule_path.contains('?') {
        return match_plain_path(rule_path, target_path, is_recursive);
    }

    let rule_segments: Vec<&str> = rule_path.split('/').filter(|s| !s.is_empty()).collect();
    let target_segments: Vec<&str> = target_path.split('/').filter(|s| !s.is_empty()).collect();
    if rule_segments.is_empty() || target_segments.len() < rule_segments.len() {
        return false;
    }

    for (rule_segment, target_segment) in rule_segments.iter().zip(target_segments.iter()) {
        if !match_segment_pattern(rule_segment, target_segment) {
            return false;
        }
    }

    if target_segments.len() == rule_segments.len() {
        return true;
    }

    is_recursive
}

// 支持 * 和 ? 通配，单段匹配不跨 /
fn match_segment_pattern(pattern: &str, text: &str) -> bool {
    if !pattern.contains('*') && !pattern.contains('?') {
        return pattern == text;
    }

    let pattern_chars: Vec<char> = pattern.chars().collect();
    let text_chars: Vec<char> = text.chars().collect();
    let mut pattern_idx = 0usize;
    let mut text_idx = 0usize;
    let mut star_idx: Option<usize> = None;
    let mut match_idx = 0usize;

    while text_idx < text_chars.len() {
        if pattern_idx < pattern_chars.len()
            && (pattern_chars[pattern_idx] == '?'
                || pattern_chars[pattern_idx] == text_chars[text_idx])
        {
            pattern_idx += 1;
            text_idx += 1;
            continue;
        }

        if pattern_idx < pattern_chars.len() && pattern_chars[pattern_idx] == '*' {
            star_idx = Some(pattern_idx);
            match_idx = text_idx;
            pattern_idx += 1;
            continue;
        }

        if let Some(star_pos) = star_idx {
            pattern_idx = star_pos + 1;
            match_idx += 1;
            text_idx = match_idx;
            continue;
        }

        return false;
    }

    while pattern_idx < pattern_chars.len() && pattern_chars[pattern_idx] == '*' {
        pattern_idx += 1;
    }

    pattern_idx == pattern_chars.len()
}

fn match_plain_path(rule_path: &str, target_path: &str, is_recursive: bool) -> bool {
    if rule_path == target_path {
        return true;
    }
    if !is_recursive {
        return false;
    }

    if rule_path == "/" {
        return target_path.starts_with('/');
    }

    target_path
        .strip_prefix(rule_path)
        .is_some_and(|suffix| suffix.starts_with('/'))
}

// 存在 . 或 .. 段视为不安全
pub fn has_unsafe_segments(path: &str) -> bool {
    if path.is_empty() {
        return true;
    }

    let mut start = 0usize;
    let bytes = path.as_bytes();
    while start <= bytes.len() {
        let end = match path[start..].find('/') {
            Some(idx) => start + idx,
            None => path.len(),
        };
        let segment = &path[start..end];
        if segment == "." || segment == ".." {
            return true;
        }
        if end == path.len() {
            break;
        }
        start = end + 1;
    }

    false
}

pub fn join(base: &str, relative: &str) -> String {
    if base.is_empty() {
        return relative.to_string();
    }
    if relative.is_empty() {
        return base.to_string();
    }
    if relative.starts_with('/') {
        return relative.to_string();
    }

    let mut result = base.to_string();
    if !result.ends_with('/') {
        result.push('/');
    }
    result.push_str(relative);
    result
}

pub fn parent(path: &str) -> String {
    if path.is_empty() || path == "/" {
        return path.to_string();
    }

    let normalized = normalize(path);
    if let Some(pos) = normalized.rfind('/') {
        if pos == 0 {
            return "/".to_string();
        }
        return normalized[..pos].to_string();
    }

    String::new()
}

pub fn is_sqlite_database_or_sidecar_path(path: &str) -> bool {
    let file_name = path.rsplit('/').next().unwrap_or(path).to_ascii_lowercase();
    [
        ".db",
        ".db-shm",
        ".db-wal",
        ".db-journal",
        ".sqlite",
        ".sqlite-shm",
        ".sqlite-wal",
        ".sqlite-journal",
        ".sqlite3",
        ".sqlite3-shm",
        ".sqlite3-wal",
        ".sqlite3-journal",
    ]
    .iter()
    .any(|suffix| file_name.ends_with(suffix))
}

pub fn extract_android_private_path_owner(path: &str) -> String {
    let normalized = normalize(path);
    let Some(user_id) = storage_user_segment(&normalized) else {
        return String::new();
    };
    let prefix = format!("/storage/emulated/{}/Android/", user_id);
    let Some(relative) = normalized.strip_prefix(&prefix) else {
        return String::new();
    };

    let mut segments = relative.split('/');
    let Some(category) = segments.next() else {
        return String::new();
    };
    if !matches!(category, "data" | "media" | "obb") {
        return String::new();
    }
    segments.next().unwrap_or_default().to_string()
}

pub fn is_absolute(path: &str) -> bool {
    !path.is_empty() && path.starts_with('/')
}

fn storage_user_segment(path: &str) -> Option<&str> {
    const PREFIX: &str = "/storage/emulated/";
    let suffix = path.strip_prefix(PREFIX)?;
    let end = suffix.find('/').unwrap_or(suffix.len());
    if end == 0 {
        return None;
    }
    let user_id = &suffix[..end];
    if user_id.chars().all(|ch| ch.is_ascii_digit()) {
        Some(user_id)
    } else {
        None
    }
}

pub fn is_storage_path(path: &str) -> bool {
    let normalized = normalize(path);
    starts_with(&normalized, "/storage/emulated/") || normalized == "/storage/emulated"
}

// /sdcard -> /storage/emulated/0
fn resolve_sdcard_alias(path: &str) -> String {
    if let Some(suffix) = path.strip_prefix("/sdcard") {
        return format!("/storage/emulated/0{}", suffix);
    }
    path.to_string()
}

// /storage/self/primary -> /storage/emulated/0
fn resolve_self_primary_alias(path: &str) -> String {
    const PREFIX: &str = "/storage/self/primary";
    if let Some(suffix) = path.strip_prefix(PREFIX) {
        return format!("/storage/emulated/0{}", suffix);
    }
    path.to_string()
}

// /mnt/user/N/primary -> /storage/emulated/N
fn resolve_mnt_user_primary_alias(path: &str) -> String {
    const PREFIX: &str = "/mnt/user/";
    if !path.starts_with(PREFIX) {
        return path.to_string();
    }

    let user_start = PREFIX.len();
    let user_end = match path[user_start..].find('/') {
        Some(idx) => user_start + idx,
        None => return path.to_string(),
    };
    if user_start >= user_end {
        return path.to_string();
    }

    if !path[user_start..user_end]
        .chars()
        .all(|c| c.is_ascii_digit())
    {
        return path.to_string();
    }

    const PRIMARY_SEGMENT: &str = "/primary";
    if !path[user_end..].starts_with(PRIMARY_SEGMENT) {
        return path.to_string();
    }

    let user_id = &path[user_start..user_end];
    format!(
        "/storage/emulated/{}{}",
        user_id,
        &path[user_end + PRIMARY_SEGMENT.len()..]
    )
}

// /mnt/runtime/{default,read,write,full}/emulated/N -> /storage/emulated/N
fn resolve_mnt_runtime_alias(path: &str) -> String {
    const PREFIX: &str = "/mnt/runtime/";
    if !path.starts_with(PREFIX) {
        return path.to_string();
    }

    let tier_start = PREFIX.len();
    let tier_end = match path[tier_start..].find('/') {
        Some(idx) => tier_start + idx,
        None => return path.to_string(),
    };
    let tier = &path[tier_start..tier_end];
    if tier != "default" && tier != "read" && tier != "write" && tier != "full" {
        return path.to_string();
    }

    const EMULATED_SEGMENT: &str = "/emulated/";
    if !path[tier_end..].starts_with(EMULATED_SEGMENT) {
        return path.to_string();
    }

    let user_start = tier_end + EMULATED_SEGMENT.len();
    let user_end = match path[user_start..].find('/') {
        Some(idx) => user_start + idx,
        None => path.len(),
    };
    if user_start >= user_end {
        return path.to_string();
    }
    if !path[user_start..user_end]
        .chars()
        .all(|c| c.is_ascii_digit())
    {
        return path.to_string();
    }

    let user_id = &path[user_start..user_end];
    if user_end == path.len() {
        return format!("/storage/emulated/{}", user_id);
    }
    format!("/storage/emulated/{}{}", user_id, &path[user_end..])
}

// /mnt/{user,installer,androidwritable,pass_through}/OWNER/emulated/N -> /storage/emulated/N
fn resolve_mnt_emulated_alias(path: &str) -> String {
    const PREFIXES: [&str; 4] = [
        "/mnt/user/",
        "/mnt/installer/",
        "/mnt/androidwritable/",
        "/mnt/pass_through/",
    ];

    let matched_prefix = PREFIXES.iter().find(|prefix| path.starts_with(*prefix));
    let Some(prefix) = matched_prefix else {
        return path.to_string();
    };

    let prefix_len = prefix.len();
    let owner_start = prefix_len;
    let owner_end = match path[owner_start..].find('/') {
        Some(idx) => owner_start + idx,
        None => return path.to_string(),
    };
    if owner_start >= owner_end {
        return path.to_string();
    }
    if !path[owner_start..owner_end]
        .chars()
        .all(|c| c.is_ascii_digit())
    {
        return path.to_string();
    }

    const EMULATED_SEGMENT: &str = "/emulated/";
    if !path[owner_end..].starts_with(EMULATED_SEGMENT) {
        return path.to_string();
    }

    let user_start = owner_end + EMULATED_SEGMENT.len();
    let user_end = match path[user_start..].find('/') {
        Some(idx) => user_start + idx,
        None => path.len(),
    };
    if user_start >= user_end {
        return path.to_string();
    }
    if !path[user_start..user_end]
        .chars()
        .all(|c| c.is_ascii_digit())
    {
        return path.to_string();
    }

    let user_id = &path[user_start..user_end];
    if user_end == path.len() {
        return format!("/storage/emulated/{}", user_id);
    }
    format!("/storage/emulated/{}{}", user_id, &path[user_end..])
}

// /data/media/N -> /storage/emulated/N
fn resolve_data_media_alias(path: &str) -> String {
    const PREFIX: &str = "/data/media/";
    if !path.starts_with(PREFIX) {
        return path.to_string();
    }

    let user_start = PREFIX.len();
    let user_end = match path[user_start..].find('/') {
        Some(idx) => user_start + idx,
        None => path.len(),
    };
    if user_start >= user_end {
        return path.to_string();
    }
    if !path[user_start..user_end]
        .chars()
        .all(|c| c.is_ascii_digit())
    {
        return path.to_string();
    }

    let user_id = &path[user_start..user_end];
    if user_end == path.len() {
        return format!("/storage/emulated/{}", user_id);
    }
    format!("/storage/emulated/{}{}", user_id, &path[user_end..])
}

// 解析 /storage/emulated/<user_id>/... 中的 user_id，失败返回 -1
pub fn extract_user_id_from_storage_path(path: &str) -> i32 {
    const PREFIX: &str = "/storage/emulated/";
    if !path.starts_with(PREFIX) {
        return -1;
    }

    let user_start = PREFIX.len();
    let user_end = path[user_start..]
        .find('/')
        .map(|idx| user_start + idx)
        .unwrap_or(path.len());
    if user_start >= user_end {
        return -1;
    }

    if !path[user_start..user_end]
        .chars()
        .all(|ch| ch.is_ascii_digit())
    {
        return -1;
    }

    path[user_start..user_end].parse().unwrap_or(-1)
}

pub fn monotonic_ms() -> i64 {
    let mut ts = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    unsafe {
        libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut ts as *mut _);
    }
    ts.tv_sec * 1000 + ts.tv_nsec / 1_000_000
}
