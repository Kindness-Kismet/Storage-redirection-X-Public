use crate::platform::paths;
use crate::redirect::policy;

pub(super) fn normalize_fuse_storage_path(path: &str, user_id: i32) -> String {
    if path.is_empty() || user_id < 0 {
        return String::new();
    }

    let normalized = paths::normalize(path);
    if paths::extract_user_id_from_storage_path(&normalized) >= 0 {
        return normalized;
    }

    let relative = normalized.trim_start_matches('/');
    if relative.is_empty() {
        return String::new();
    }
    paths::normalize(&format!("/storage/emulated/{}/{}", user_id, relative))
}

pub(super) fn resolve_private_owner_sqlite_path(path: &str) -> Option<(String, String, i32)> {
    let normalized_path = paths::normalize(path);
    if normalized_path.is_empty() || !paths::is_sqlite_database_or_sidecar_path(&normalized_path) {
        return None;
    }

    let user_id = paths::extract_user_id_from_storage_path(&normalized_path);
    if user_id < 0 {
        return None;
    }

    let owner_package = paths::extract_android_private_path_owner(&normalized_path);
    if owner_package.is_empty()
        || policy::is_media_intermediate_package(&owner_package)
        || policy::is_system_writer_package(&owner_package)
    {
        return None;
    }

    Some((normalized_path, owner_package, user_id))
}

pub(super) fn is_sqlite_shm_sidecar_path(path: &str) -> bool {
    let lower = path.to_ascii_lowercase();
    lower.ends_with(".db-shm") || lower.ends_with(".sqlite-shm") || lower.ends_with(".sqlite3-shm")
}

pub(super) fn storage_to_data_media_path(path: &str) -> String {
    const STORAGE_PREFIX: &str = "/storage/emulated/";
    const DATA_MEDIA_PREFIX: &str = "/data/media/";
    if !path.starts_with(STORAGE_PREFIX) {
        return path.to_string();
    }
    format!("{}{}", DATA_MEDIA_PREFIX, &path[STORAGE_PREFIX.len()..])
}

pub(super) fn resolve_caller_packages_with_explicit(
    caller_uid: i32,
    caller_package: &str,
) -> Vec<String> {
    let mut packages = policy::get_packages_for_uid(caller_uid);
    let caller_package = caller_package.trim();
    if !caller_package.is_empty()
        && !packages
            .iter()
            .any(|package| package.eq_ignore_ascii_case(caller_package))
    {
        packages.push(caller_package.to_string());
    }
    packages.sort();
    packages.dedup();
    packages
}

pub(super) fn is_private_owner_sqlite_caller_package(
    package_name: &str,
    owner_package: &str,
) -> bool {
    !package_name.is_empty()
        && package_name != owner_package
        && !policy::is_media_intermediate_package(package_name)
        && !policy::is_system_writer_package(package_name)
}

pub(super) fn private_owner_sqlite_path_mentions_caller_package(
    path: &str,
    package_name: &str,
) -> bool {
    let normalized_path = paths::normalize(path);
    let user_id = paths::extract_user_id_from_storage_path(&normalized_path);
    if user_id < 0 {
        return false;
    }
    let owner_package = paths::extract_android_private_path_owner(&normalized_path);
    if owner_package.is_empty() {
        return false;
    }

    let private_root = format!(
        "/storage/emulated/{}/Android/media/{}/",
        user_id, owner_package
    );
    let Some(relative_path) = normalized_path.strip_prefix(&private_root) else {
        return false;
    };
    let relative_path = relative_path.to_ascii_lowercase();

    package_name.rsplit('.').any(|token| {
        let token = token.trim();
        token.len() >= 4
            && token.chars().any(|ch| ch.is_ascii_alphabetic())
            && relative_path.contains(&token.to_ascii_lowercase())
    })
}
