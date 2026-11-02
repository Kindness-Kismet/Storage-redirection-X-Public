use super::ANDROID_APP_UID_START;
use super::resolve::{
    is_private_owner_sqlite_caller_package, resolve_caller_packages_with_explicit,
    resolve_private_owner_sqlite_path,
};
use crate::platform::{self, paths};
use once_cell::sync::Lazy;
use std::collections::VecDeque;
use std::sync::Mutex;

const RECENT_SQLITE_ACCESS_WINDOW_MS: i64 = 30_000;
const MAX_RECENT_SQLITE_ACCESSES: usize = 16;

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

pub(super) fn remember_recent_private_owner_sqlite_access(
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

pub(super) fn has_recent_private_owner_sqlite_access_for_caller(
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
