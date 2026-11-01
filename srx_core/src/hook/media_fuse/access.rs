use super::caller::{
    current_fuse_caller_uid, current_thread_caller_package_for_uid,
    should_allow_private_owner_sqlite_for_owner, should_force_private_owner_sqlite_for_owner,
};
use super::recent::{
    has_recent_private_owner_sqlite_access_for_caller, remember_recent_private_owner_sqlite_access,
};
use super::resolve::{
    is_private_owner_sqlite_caller_package, is_sqlite_shm_sidecar_path,
    normalize_fuse_storage_path, private_owner_sqlite_path_mentions_caller_package,
    resolve_caller_packages_with_explicit, resolve_private_owner_sqlite_path,
};
use super::{ANDROID_APP_UID_START, SQLITE_SHM_MIN_SIZE};
use crate::config::SettingsHub;
use crate::platform::{self, paths};
use crate::redirect::policy;

pub fn should_allow_private_owner_sqlite_access(path: &str, caller_uid: i32) -> bool {
    let caller_package = current_thread_caller_package_for_uid(caller_uid);
    should_allow_private_owner_sqlite_access_for_caller(path, caller_uid, &caller_package)
}

pub fn should_allow_configured_real_path_access(path: &str, caller_uid: i32) -> bool {
    if caller_uid < ANDROID_APP_UID_START {
        return false;
    }

    let user_id = platform::user_id_from_uid(caller_uid);
    let normalized_path = normalize_fuse_storage_path(path, user_id);
    if normalized_path.is_empty()
        || paths::has_unsafe_segments(&normalized_path)
        || paths::extract_user_id_from_storage_path(&normalized_path) != user_id
    {
        return false;
    }

    let mut packages = policy::get_packages_for_uid(caller_uid);
    if packages.is_empty() {
        policy::refresh_shared_uid_cache();
        packages = policy::get_packages_for_uid(caller_uid);
    }

    let config = SettingsHub::instance();
    for package in packages {
        if package.is_empty()
            || policy::is_media_intermediate_package(&package)
            || policy::is_system_writer_package(&package)
        {
            continue;
        }

        let excluded_paths = config.get_excluded_real_paths(&package, caller_uid);
        if excluded_paths
            .iter()
            .any(|rule| paths::matches(rule, &normalized_path, true))
        {
            continue;
        }

        let allowed_paths = config.get_allowed_real_paths(&package, caller_uid);
        if !allowed_paths
            .iter()
            .any(|rule| paths::matches(rule, &normalized_path, true))
        {
            continue;
        }

        return true;
    }

    false
}

pub fn should_allow_private_owner_sqlite_access_for_caller(
    path: &str,
    caller_uid: i32,
    caller_package: &str,
) -> bool {
    let Some((normalized_path, owner_package, user_id)) = resolve_private_owner_sqlite_path(path)
    else {
        return false;
    };
    if caller_uid < ANDROID_APP_UID_START || platform::user_id_from_uid(caller_uid) != user_id {
        return false;
    }

    let owner_uid = policy::get_uid_for_package(&owner_package);
    if owner_uid >= ANDROID_APP_UID_START && platform::user_id_from_uid(owner_uid) != user_id {
        return false;
    }
    if owner_uid >= ANDROID_APP_UID_START && owner_uid == caller_uid {
        remember_recent_private_owner_sqlite_access(
            &normalized_path,
            &owner_package,
            caller_uid,
            std::slice::from_ref(&owner_package),
        );
        log::debug!(
            "allow media fuse owner sqlite caller_uid={} owner={} path={}",
            caller_uid,
            owner_package,
            normalized_path
        );
        return true;
    }

    let caller_packages = resolve_caller_packages_with_explicit(caller_uid, caller_package);
    if caller_packages
        .iter()
        .any(|package| package == &owner_package || policy::is_system_writer_package(package))
    {
        return false;
    }

    let allow_by_owner = should_allow_private_owner_sqlite_for_owner(&owner_package, user_id);
    let allow_by_token = caller_packages.iter().any(|package| {
        is_private_owner_sqlite_caller_package(package, &owner_package)
            && private_owner_sqlite_path_mentions_caller_package(&normalized_path, package)
    });
    if !allow_by_owner && !allow_by_token {
        return false;
    }

    remember_recent_private_owner_sqlite_access(
        &normalized_path,
        &owner_package,
        caller_uid,
        &caller_packages,
    );

    log::debug!(
        "allow media fuse private owner sqlite caller_uid={} caller={:?} owner={} owner_uid={} path={}",
        caller_uid,
        caller_packages,
        owner_package,
        owner_uid,
        normalized_path
    );
    true
}

pub fn should_allow_private_owner_sqlite_owner_backend(path: &str) -> Option<i32> {
    let (normalized_path, owner_package, user_id) = resolve_private_owner_sqlite_path(path)?;

    if !should_force_private_owner_sqlite_for_owner(&owner_package, user_id) {
        return None;
    }

    let owner_uid = policy::get_uid_for_package(&owner_package);
    if owner_uid >= ANDROID_APP_UID_START {
        if platform::user_id_from_uid(owner_uid) != user_id {
            return None;
        }
        log::debug!(
            "allow media fuse private owner sqlite backend owner={} owner_uid={} path={}",
            owner_package,
            owner_uid,
            normalized_path
        );
        return Some(owner_uid);
    }

    let synthetic_owner_uid = user_id
        .saturating_mul(platform::ANDROID_USER_ID_OFFSET)
        .saturating_add(ANDROID_APP_UID_START);
    log::debug!(
        "allow media fuse private owner sqlite backend owner={} synthetic_uid={} path={}",
        owner_package,
        synthetic_owner_uid,
        normalized_path
    );
    Some(synthetic_owner_uid)
}

pub fn should_force_userspace_for_private_owner_sqlite_path(path: &str) -> bool {
    let Some((normalized_path, owner_package, user_id)) = resolve_private_owner_sqlite_path(path)
    else {
        return false;
    };

    if should_force_private_owner_sqlite_for_owner(&owner_package, user_id) {
        log::info!(
            "force media fuse userspace for private owner sqlite owner={} user_id={} path={}",
            owner_package,
            user_id,
            normalized_path
        );
        return true;
    }

    let caller_uid = current_fuse_caller_uid();
    let caller_package = current_thread_caller_package_for_uid(caller_uid);
    if !should_allow_private_owner_sqlite_access_for_caller(
        &normalized_path,
        caller_uid,
        &caller_package,
    ) && !has_recent_private_owner_sqlite_access_for_caller(
        &normalized_path,
        caller_uid,
        &caller_package,
    ) {
        return false;
    }
    log::info!(
        "force media fuse userspace for private owner sqlite caller_uid={} owner={} user_id={} path={}",
        caller_uid,
        owner_package,
        user_id,
        normalized_path
    );
    true
}

pub fn adjusted_private_owner_sqlite_truncate_length(
    path: &str,
    requested_length: libc::off_t,
) -> libc::off_t {
    if (0..SQLITE_SHM_MIN_SIZE).contains(&requested_length)
        && resolve_private_owner_sqlite_path(path).is_some()
        && is_sqlite_shm_sidecar_path(path)
    {
        SQLITE_SHM_MIN_SIZE
    } else {
        requested_length
    }
}
