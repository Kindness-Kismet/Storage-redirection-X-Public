use super::SettingsHub;
use crate::platform::{self, fs, paths};
use std::ffi::CString;

const MEDIA_RW_GID: u32 = 1023;
const DIR_MODE_FINAL: libc::mode_t = 0o2770;
const DIR_MODE_CONTAINER: libc::mode_t = 0o2771;

impl SettingsHub {
    pub fn prepare_enabled_path_mapping_targets(&self) {
        let snapshots = self.get_enabled_path_mapping_snapshots();
        if snapshots.is_empty() {
            return;
        }

        for snapshot in snapshots {
            for mapping in snapshot.path_mappings {
                let storage_target = paths::resolve_user_path(
                    &paths::normalize(&mapping.final_path),
                    snapshot.user_id,
                );
                let target = storage_to_data_media_backend_path(&storage_target, snapshot.user_id);
                if target.is_empty() {
                    continue;
                }
                let _ = prepare_directory_chain(&target, snapshot.app_uid);
            }
        }
    }
}

fn storage_to_data_media_backend_path(storage_target: &str, user_id: i32) -> String {
    if storage_target.is_empty() {
        return String::new();
    }
    let prefix = format!("/storage/emulated/{}/", user_id);
    if !paths::starts_with(storage_target, &prefix) {
        return String::new();
    }
    format!(
        "/data/media/{}/{}",
        user_id,
        &storage_target[prefix.len()..]
    )
}

fn prepare_directory_chain(target: &str, owner_uid: i32) -> bool {
    if target.is_empty() || !target.starts_with("/data/media/") {
        return false;
    }

    let user_id = platform::user_id_from_uid(owner_uid);
    let user_root = format!("/data/media/{}", user_id);
    let prefix = format!("{}/", user_root);
    if !paths::starts_with(target, &prefix) {
        return false;
    }

    let relative = &target[prefix.len()..];
    let segments: Vec<&str> = relative
        .split('/')
        .filter(|segment| !segment.is_empty())
        .collect();
    if segments.is_empty() {
        return false;
    }

    let mut current = user_root.clone();
    let mut ok = true;
    for (index, segment) in segments.iter().enumerate() {
        current = paths::join(&current, segment);
        if index == 0 {
            if !fs::is_directory(&current) {
                log::warn!("mapping prep skip public root missing dir={}", current);
                return false;
            }
            continue;
        }

        if !fs::create_directory(&current, owner_uid) {
            log::warn!("mapping prep mkdir failed target={}", current);
            ok = false;
            continue;
        }

        let mode = if index + 1 == segments.len() {
            DIR_MODE_FINAL
        } else {
            DIR_MODE_CONTAINER
        };
        if !fix_owner_and_mode(&current, owner_uid, mode) {
            ok = false;
        }
    }

    ok
}

fn fix_owner_and_mode(path: &str, owner_uid: i32, mode: libc::mode_t) -> bool {
    let Ok(c_path) = CString::new(path) else {
        return false;
    };

    let mut ok = true;
    let chown_ret = unsafe { libc::chown(c_path.as_ptr(), owner_uid as u32, MEDIA_RW_GID) };
    if chown_ret != 0 {
        ok = false;
        log::warn!(
            "mapping prep chown failed errno={} target={}",
            unsafe { *libc::__errno() },
            path
        );
    }

    let chmod_ret = unsafe { libc::chmod(c_path.as_ptr(), mode) };
    if chmod_ret != 0 {
        ok = false;
        log::warn!(
            "mapping prep chmod failed errno={} target={}",
            unsafe { *libc::__errno() },
            path
        );
    }

    ok
}
