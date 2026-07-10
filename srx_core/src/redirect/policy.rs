// 系统代写与共享 UID 策略
use crate::platform::module_paths::{
    MODULE_SYSTEM_WRITER_UIDS_FILE, SHARED_SYSTEM_WRITER_UIDS_FILE,
};
use once_cell::sync::Lazy;
use std::collections::{HashMap, HashSet};
use std::fs::File;
use std::io::{BufRead, BufReader};
use std::sync::Mutex;
use std::time::UNIX_EPOCH;

const MEDIA_PROVIDER_GOOGLE: &str = "com.google.android.providers.media.module";
const MEDIA_PROVIDER_AOSP: &str = "com.android.providers.media.module";
const MEDIA_PROVIDER_LEGACY: &str = "com.android.providers.media";
const DOWNLOAD_PROVIDER: &str = "com.android.providers.downloads";
const DOWNLOAD_PROVIDER_UI: &str = "com.android.providers.downloads.ui";
const EXTERNAL_STORAGE_PROVIDER: &str = "com.android.externalstorage";
const MTP_PACKAGE: &str = "com.android.mtp";
const SYSTEM_WRITER_UID_FILE_CANDIDATES: [&str; 2] = [
    SHARED_SYSTEM_WRITER_UIDS_FILE,
    MODULE_SYSTEM_WRITER_UIDS_FILE,
];

#[derive(Default)]
struct SharedUidState {
    uid_by_package: HashMap<String, i32>,
    packages_by_uid: HashMap<i32, Vec<String>>,
    system_writer_shared_uids: HashSet<i32>,
    last_fingerprint: u64,
    is_loaded: bool,
}

static SHARED_UID_STATE: Lazy<Mutex<SharedUidState>> =
    Lazy::new(|| Mutex::new(SharedUidState::default()));

pub fn is_system_writer_package(package_name: &str) -> bool {
    is_system_writer_candidate(package_name)
}

pub fn is_media_provider_package(package_name: &str) -> bool {
    is_media_provider_candidate(package_name)
}

// 媒体链路中间进程：MediaProvider、代写 provider/service，以及系统文件/照片选择 UI。
pub fn is_media_intermediate_package(package_name: &str) -> bool {
    if package_name.is_empty() {
        return false;
    }

    if is_media_provider_candidate(package_name) {
        return true;
    }

    package_name == DOWNLOAD_PROVIDER
        || package_name == MTP_PACKAGE
        || package_name == EXTERNAL_STORAGE_PROVIDER
        || is_file_monitor_ui_package(package_name)
}

pub fn is_file_monitor_ui_package(package_name: &str) -> bool {
    if package_name.is_empty() {
        return false;
    }

    let normalized = package_name.to_ascii_lowercase();
    normalized == DOWNLOAD_PROVIDER_UI
        || normalized.contains(".documentsui")
        || normalized.contains(".photopicker")
        || normalized.contains(".filemanager")
        || normalized.contains("fileexplorer")
        || normalized.ends_with(".myfiles")
}

pub fn get_system_writer_name(_package_name: &str) -> &'static str {
    "MediaProvider"
}

// 指纹变更时重新加载
pub fn refresh_shared_uid_cache() {
    let mut state = SHARED_UID_STATE
        .lock()
        .unwrap_or_else(|err| err.into_inner());
    let fingerprint = compute_uid_file_fingerprint();
    if state.is_loaded && fingerprint == state.last_fingerprint {
        return;
    }
    load_shared_uid_cache(&mut state, fingerprint);
}

pub fn get_shared_group_members(package_name: &str) -> Vec<String> {
    let state = SHARED_UID_STATE
        .lock()
        .unwrap_or_else(|err| err.into_inner());
    let Some(uid) = state.uid_by_package.get(package_name) else {
        return vec![];
    };
    if !is_shared_uid(*uid, &state.packages_by_uid) {
        return vec![];
    }
    state.packages_by_uid.get(uid).cloned().unwrap_or_default()
}

pub fn is_shared_group_package(package_name: &str) -> bool {
    let state = SHARED_UID_STATE
        .lock()
        .unwrap_or_else(|err| err.into_inner());
    let Some(uid) = state.uid_by_package.get(package_name) else {
        return false;
    };
    is_shared_uid(*uid, &state.packages_by_uid)
}

pub fn get_packages_for_uid(uid: i32) -> Vec<String> {
    if uid < 0 {
        return vec![];
    }
    let state = SHARED_UID_STATE
        .lock()
        .unwrap_or_else(|err| err.into_inner());
    state.packages_by_uid.get(&uid).cloned().unwrap_or_default()
}

pub fn get_uid_for_package(package_name: &str) -> i32 {
    if package_name.is_empty() {
        return -1;
    }
    let state = SHARED_UID_STATE
        .lock()
        .unwrap_or_else(|err| err.into_inner());
    state
        .uid_by_package
        .get(package_name)
        .copied()
        .unwrap_or(-1)
}

// 格式: "pkg1,pkg2,pkg3"
pub fn get_shared_uid_packages_string(uid: i32) -> String {
    let packages = get_packages_for_uid(uid);
    if packages.len() < 2 {
        return String::new();
    }
    packages.join(",")
}

pub fn is_shared_uid_process(uid: i32) -> bool {
    if uid < 0 {
        return false;
    }
    let state = SHARED_UID_STATE
        .lock()
        .unwrap_or_else(|err| err.into_inner());
    state.system_writer_shared_uids.contains(&uid)
}

fn is_media_provider_candidate(package_name: &str) -> bool {
    package_name == MEDIA_PROVIDER_GOOGLE
        || package_name == MEDIA_PROVIDER_AOSP
        || package_name == MEDIA_PROVIDER_LEGACY
}

// 仅 MediaProvider 是 FUSE 服务端，DP/MTP 作为客户端写入最终经过 MediaProvider
fn is_system_writer_candidate(package_name: &str) -> bool {
    is_media_provider_candidate(package_name)
}

fn trim_ascii(value: &str) -> String {
    let trimmed = value.trim_matches(|c| c == ' ' || c == '\t' || c == '\n' || c == '\r');
    trimmed.to_string()
}

// 格式: "包名:UID"
fn parse_line(line: &str) -> Option<(String, i32)> {
    let trimmed = trim_ascii(line);
    if trimmed.is_empty() || trimmed.starts_with('#') {
        return None;
    }

    let separator = trimmed.find(':')?;
    let package_value = trim_ascii(&trimmed[..separator]);
    let uid_value = trim_ascii(&trimmed[separator + 1..]);
    if package_value.is_empty() || uid_value.is_empty() {
        return None;
    }

    if !uid_value.chars().all(|c| c.is_ascii_digit()) {
        return None;
    }

    let uid = uid_value.parse::<i32>().ok()?;
    if uid < 0 {
        return None;
    }
    Some((package_value, uid))
}

fn is_shared_uid(uid: i32, packages_by_uid: &HashMap<i32, Vec<String>>) -> bool {
    packages_by_uid
        .get(&uid)
        .map(|packages| packages.len() >= 2)
        .unwrap_or(false)
}

// 基于 mtime 与文件大小
fn compute_uid_file_fingerprint() -> u64 {
    for (index, path) in SYSTEM_WRITER_UID_FILE_CANDIDATES.iter().enumerate() {
        let Ok(meta) = std::fs::metadata(path) else {
            continue;
        };
        let size = meta.len();
        let modified = meta
            .modified()
            .ok()
            .and_then(|time| time.duration_since(UNIX_EPOCH).ok())
            .map(|duration| duration.as_nanos() as u64)
            .unwrap_or(0);
        return size ^ modified ^ ((index as u64 + 1) << 60);
    }
    0
}

fn load_shared_uid_cache(state: &mut SharedUidState, fingerprint: u64) {
    let file = SYSTEM_WRITER_UID_FILE_CANDIDATES
        .iter()
        .find_map(|path| File::open(path).ok());
    let Some(file) = file else {
        state.uid_by_package.clear();
        state.packages_by_uid.clear();
        state.system_writer_shared_uids.clear();
        state.last_fingerprint = fingerprint;
        state.is_loaded = true;
        return;
    };

    let mut uid_by_package = HashMap::new();
    let mut packages_by_uid: HashMap<i32, Vec<String>> = HashMap::new();

    let reader = BufReader::new(file);
    for line in reader.lines().map_while(Result::ok) {
        let Some((package, uid)) = parse_line(&line) else {
            continue;
        };
        uid_by_package.insert(package.clone(), uid);
        packages_by_uid.entry(uid).or_default().push(package);
    }

    for packages in packages_by_uid.values_mut() {
        packages.sort();
        packages.dedup();
    }

    let mut system_writer_shared_uids = HashSet::new();
    for (uid, packages) in &packages_by_uid {
        if packages.len() < 2 {
            continue;
        }
        if packages.iter().any(|name| is_system_writer_candidate(name)) {
            system_writer_shared_uids.insert(*uid);
        }
    }

    state.uid_by_package = uid_by_package;
    state.packages_by_uid = packages_by_uid;
    state.system_writer_shared_uids = system_writer_shared_uids;
    state.last_fingerprint = fingerprint;
    state.is_loaded = true;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn file_monitor_ui_detects_system_and_oem_file_shells() {
        assert!(is_file_monitor_ui_package("com.android.documentsui"));
        assert!(is_file_monitor_ui_package("com.android.photopicker"));
        assert!(is_file_monitor_ui_package(
            "com.android.providers.downloads.ui"
        ));
        assert!(is_file_monitor_ui_package("com.coloros.filemanager"));
        assert!(is_file_monitor_ui_package(
            "com.mi.android.globalFileExplorer"
        ));
        assert!(is_file_monitor_ui_package("com.sec.android.app.myfiles"));
        assert!(!is_file_monitor_ui_package(
            "com.android.providers.downloads"
        ));
    }

    #[test]
    fn media_intermediate_includes_file_ui_for_attribution() {
        assert!(is_media_intermediate_package(
            "com.android.providers.media.module"
        ));
        assert!(is_media_intermediate_package(
            "com.android.providers.downloads"
        ));
        assert!(is_media_intermediate_package("com.android.externalstorage"));
        assert!(is_media_intermediate_package("com.coloros.filemanager"));
    }
}
