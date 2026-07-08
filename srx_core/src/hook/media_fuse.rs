use super::context;
use super::stats::InterceptHub;
use crate::config::SettingsHub;
use crate::platform::{self, paths};
use crate::redirect::policy;
use once_cell::sync::Lazy;
use std::collections::VecDeque;
use std::ffi::CString;
use std::sync::Mutex;

const ANDROID_APP_UID_START: i32 = 10000;
const FUSE_CALLER_MAX_AGE_MS: i64 = 1500;
const RECENT_SQLITE_ACCESS_WINDOW_MS: i64 = 30_000;
const MAX_RECENT_SQLITE_ACCESSES: usize = 16;
const SQLITE_SHM_MIN_SIZE: libc::off_t = 32 * 1024;

#[derive(Clone, Debug)]
struct RecentPrivateOwnerSqliteAccess {
    normalized_path: String,
    owner_package: String,
    caller_uid: i32,
    caller_packages: Vec<String>,
    updated_ms: i64,
}

static RECENT_PRIVATE_OWNER_SQLITE_ACCESS: Lazy<Mutex<VecDeque<RecentPrivateOwnerSqliteAccess>>> =
    Lazy::new(|| Mutex::new(VecDeque::new()));

pub fn should_allow_private_owner_sqlite_access(path: &str, caller_uid: i32) -> bool {
    let caller_package = current_thread_caller_package_for_uid(caller_uid);
    should_allow_private_owner_sqlite_access_for_caller(path, caller_uid, &caller_package)
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

pub fn has_recent_private_owner_sqlite_access(path: &str) -> Option<i32> {
    let (normalized_path, owner_package, user_id) = resolve_private_owner_sqlite_path(path)?;

    let Ok(mut accesses) = RECENT_PRIVATE_OWNER_SQLITE_ACCESS.lock() else {
        return None;
    };
    let now_ms = paths::monotonic_ms();
    retain_recent_private_owner_sqlite_accesses(&mut accesses, now_ms);

    accesses.iter().rev().find_map(|access| {
        if access.normalized_path == normalized_path
            && access.owner_package == owner_package
            && platform::user_id_from_uid(access.caller_uid) == user_id
        {
            Some(access.caller_uid)
        } else {
            None
        }
    })
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

pub fn prepare_private_owner_sqlite_sidecar(path: &str) -> bool {
    let Some((normalized_path, owner_package, user_id)) = resolve_private_owner_sqlite_path(path)
    else {
        return false;
    };
    if !is_sqlite_shm_sidecar_path(&normalized_path) {
        return false;
    }
    if !should_prepare_private_owner_sqlite_sidecar(&normalized_path, &owner_package, user_id) {
        return false;
    }

    let backend_path = storage_to_data_media_path(&normalized_path);
    if backend_path == normalized_path || !backend_path.starts_with("/data/media/") {
        return false;
    }

    prepare_sqlite_shm_backend(&normalized_path, &backend_path)
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

fn resolve_private_owner_sqlite_path(path: &str) -> Option<(String, String, i32)> {
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

fn is_sqlite_shm_sidecar_path(path: &str) -> bool {
    let lower = path.to_ascii_lowercase();
    lower.ends_with(".db-shm") || lower.ends_with(".sqlite-shm") || lower.ends_with(".sqlite3-shm")
}

fn prepare_sqlite_shm_backend(storage_path: &str, backend_path: &str) -> bool {
    if !ensure_backend_parent_dir(backend_path, storage_path) {
        return false;
    }

    let Ok(c_path) = CString::new(backend_path) else {
        return false;
    };

    let fd = unsafe { libc::open(c_path.as_ptr(), libc::O_RDWR | libc::O_CLOEXEC) };
    if fd < 0 {
        log::warn!(
            "prepare sqlite shm backend open failed errno={} storage={} backend={}",
            current_errno(),
            storage_path,
            backend_path
        );
        return false;
    }

    let size = fd_size(fd);
    if size >= SQLITE_SHM_MIN_SIZE {
        unsafe {
            libc::close(fd);
        }
        return true;
    }

    if size > 0 {
        let truncate_zero = unsafe { libc::ftruncate(fd, 0) };
        if truncate_zero != 0 {
            log::warn!(
                "prepare sqlite shm backend reset failed errno={} size={} storage={} backend={}",
                current_errno(),
                size,
                storage_path,
                backend_path
            );
            unsafe {
                libc::close(fd);
            }
            return false;
        }
    }

    let result = unsafe { libc::ftruncate(fd, SQLITE_SHM_MIN_SIZE) };
    let errno = current_errno();
    let final_size = fd_size(fd);
    unsafe {
        libc::close(fd);
    }

    if result == 0 {
        log::info!(
            "prepare sqlite shm backend ok size={} final_size={} storage={} backend={}",
            size,
            final_size,
            storage_path,
            backend_path
        );
        true
    } else {
        log::warn!(
            "prepare sqlite shm backend failed errno={} size={} final_size={} storage={} backend={}",
            errno,
            size,
            final_size,
            storage_path,
            backend_path
        );
        false
    }
}

fn fd_size(fd: libc::c_int) -> libc::off_t {
    let mut statbuf = std::mem::MaybeUninit::<libc::stat>::uninit();
    let result = unsafe { libc::fstat(fd, statbuf.as_mut_ptr()) };
    if result != 0 {
        return -1;
    }
    let statbuf = unsafe { statbuf.assume_init() };
    statbuf.st_size as libc::off_t
}

fn current_errno() -> i32 {
    unsafe { *libc::__errno() }
}

pub(crate) fn ensure_backend_parent_dir(backend_path: &str, storage_path: &str) -> bool {
    let Some(parent) = std::path::Path::new(backend_path).parent() else {
        return true;
    };
    if parent.exists() {
        return true;
    }

    match std::fs::create_dir_all(parent) {
        Ok(()) => {
            log::info!(
                "ensure sqlite shm backend parent dir ok storage={} backend_parent={}",
                storage_path,
                parent.display()
            );
            true
        }
        Err(err) => {
            log::warn!(
                "ensure sqlite shm backend parent dir failed storage={} backend_parent={} err={}",
                storage_path,
                parent.display(),
                err
            );
            false
        }
    }
}

fn storage_to_data_media_path(path: &str) -> String {
    const STORAGE_PREFIX: &str = "/storage/emulated/";
    const DATA_MEDIA_PREFIX: &str = "/data/media/";
    if !path.starts_with(STORAGE_PREFIX) {
        return path.to_string();
    }
    format!("{}{}", DATA_MEDIA_PREFIX, &path[STORAGE_PREFIX.len()..])
}

fn current_fuse_caller_uid() -> i32 {
    let caller_uid = InterceptHub::instance().get_fuse_caller_uid();
    if caller_uid < ANDROID_APP_UID_START {
        return -1;
    }

    let age_ms = context::get_fuse_caller_uid_age_ms();
    if (0..=FUSE_CALLER_MAX_AGE_MS).contains(&age_ms) {
        caller_uid
    } else {
        -1
    }
}

fn current_thread_caller_package_for_uid(caller_uid: i32) -> String {
    if caller_uid < ANDROID_APP_UID_START {
        return String::new();
    }

    let hub = InterceptHub::instance();
    if hub.get_current_caller_uid() == caller_uid {
        hub.get_current_caller_package()
    } else {
        String::new()
    }
}

fn resolve_caller_packages_with_explicit(caller_uid: i32, caller_package: &str) -> Vec<String> {
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

fn remember_recent_private_owner_sqlite_access(
    normalized_path: &str,
    owner_package: &str,
    caller_uid: i32,
    caller_packages: &[String],
) {
    let caller_packages: Vec<String> = caller_packages
        .iter()
        .filter(|package| {
            package.eq_ignore_ascii_case(owner_package)
                || is_private_owner_sqlite_caller_package(package, owner_package)
        })
        .cloned()
        .collect();
    if caller_uid < ANDROID_APP_UID_START || caller_packages.is_empty() {
        return;
    }

    let Ok(mut accesses) = RECENT_PRIVATE_OWNER_SQLITE_ACCESS.lock() else {
        return;
    };
    let now_ms = paths::monotonic_ms();
    retain_recent_private_owner_sqlite_accesses(&mut accesses, now_ms);
    accesses.retain(|access| {
        !(access.normalized_path == normalized_path
            && access.owner_package == owner_package
            && access.caller_uid == caller_uid)
    });
    accesses.push_back(RecentPrivateOwnerSqliteAccess {
        normalized_path: normalized_path.to_string(),
        owner_package: owner_package.to_string(),
        caller_uid,
        caller_packages,
        updated_ms: now_ms,
    });
    while accesses.len() > MAX_RECENT_SQLITE_ACCESSES {
        accesses.pop_front();
    }
}

fn has_recent_private_owner_sqlite_access_for_caller(
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

    let caller_packages = resolve_caller_packages_with_explicit(caller_uid, caller_package);
    if caller_packages.is_empty() {
        return false;
    }

    let Ok(mut accesses) = RECENT_PRIVATE_OWNER_SQLITE_ACCESS.lock() else {
        return false;
    };
    let now_ms = paths::monotonic_ms();
    retain_recent_private_owner_sqlite_accesses(&mut accesses, now_ms);

    accesses.iter().rev().any(|access| {
        access.normalized_path == normalized_path
            && access.owner_package == owner_package
            && access.caller_uid == caller_uid
            && caller_packages.iter().any(|package| {
                is_private_owner_sqlite_caller_package(package, &owner_package)
                    && access
                        .caller_packages
                        .iter()
                        .any(|recent_package| recent_package.eq_ignore_ascii_case(package))
            })
    })
}

fn retain_recent_private_owner_sqlite_accesses(
    accesses: &mut VecDeque<RecentPrivateOwnerSqliteAccess>,
    now_ms: i64,
) {
    accesses.retain(|access| {
        (0..=RECENT_SQLITE_ACCESS_WINDOW_MS).contains(&now_ms.saturating_sub(access.updated_ms))
    });
}

fn should_allow_private_owner_sqlite_for_owner(owner_package: &str, user_id: i32) -> bool {
    should_force_private_owner_sqlite_for_owner(owner_package, user_id)
}

fn should_force_private_owner_sqlite_for_owner(owner_package: &str, user_id: i32) -> bool {
    SettingsHub::instance().is_user_profile_enabled_in_memory(owner_package, user_id)
}

fn should_prepare_private_owner_sqlite_sidecar(
    path: &str,
    owner_package: &str,
    user_id: i32,
) -> bool {
    if should_force_private_owner_sqlite_for_owner(owner_package, user_id) {
        return true;
    }

    let caller_uid = current_fuse_caller_uid();
    let caller_package = current_thread_caller_package_for_uid(caller_uid);
    should_allow_private_owner_sqlite_access_for_caller(path, caller_uid, &caller_package)
        || has_recent_private_owner_sqlite_access_for_caller(path, caller_uid, &caller_package)
}

fn is_private_owner_sqlite_caller_package(package_name: &str, owner_package: &str) -> bool {
    !package_name.is_empty()
        && package_name != owner_package
        && !policy::is_media_intermediate_package(package_name)
        && !policy::is_system_writer_package(package_name)
}

fn private_owner_sqlite_path_mentions_caller_package(path: &str, package_name: &str) -> bool {
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn caller_package_token_matches_xradiant_private_sqlite() {
        assert!(private_owner_sqlite_path_mentions_caller_package(
            "/storage/emulated/0/Android/media/com.eg.android.AlipayGphone/XRadiant/XRadiant.db-shm",
            "com.leo.xposed.xradiant"
        ));
    }

    #[test]
    fn unrelated_package_token_does_not_match_generic_sqlite() {
        assert!(!private_owner_sqlite_path_mentions_caller_package(
            "/storage/emulated/0/Android/media/com.eg.android.AlipayGphone/cache/app.db-shm",
            "com.leo.xposed.xradiant"
        ));
    }

    #[test]
    fn recent_sqlite_access_keeps_owner_caller_for_backend_retry() {
        let path = "/storage/emulated/0/Android/media/com.eg.android.AlipayGphone/XRadiant/XRadiant.db-shm";
        let owner = "com.eg.android.AlipayGphone";
        let owner_uid = 10274;

        RECENT_PRIVATE_OWNER_SQLITE_ACCESS
            .lock()
            .unwrap_or_else(|err| err.into_inner())
            .clear();
        remember_recent_private_owner_sqlite_access(path, owner, owner_uid, &[owner.to_string()]);

        assert_eq!(
            has_recent_private_owner_sqlite_access(path),
            Some(owner_uid)
        );
    }
}
