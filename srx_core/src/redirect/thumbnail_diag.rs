use super::router::{RedirectAction, RedirectDecision};
use crate::config::SettingsHub;
use crate::platform::paths;
use std::sync::atomic::{AtomicU64, Ordering};

const THUMBNAIL_DIAG_INITIAL: u64 = 64;
const THUMBNAIL_DIAG_STEP: u64 = 64;
static THUMBNAIL_DIAG_COUNT: AtomicU64 = AtomicU64::new(0);

pub struct ThumbnailDecisionDiag<'a> {
    pub proc_package: &'a str,
    pub caller_package: &'a str,
    pub caller_uid: i32,
    pub user_id: i32,
    pub resolved_path: &'a str,
    pub enabled_in_memory: bool,
    pub enabled_in_raw: bool,
    pub is_caller_from_inferred: bool,
    pub exit_reason: &'a str,
    pub decision: &'a RedirectDecision,
}

pub fn log_system_writer_decision(args: &ThumbnailDecisionDiag<'_>) {
    let Some(media_root) = thumbnail_media_root(args.resolved_path, args.user_id) else {
        return;
    };
    let count = THUMBNAIL_DIAG_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
    if count > THUMBNAIL_DIAG_INITIAL && !count.is_multiple_of(THUMBNAIL_DIAG_STEP) {
        return;
    }

    let config = SettingsHub::instance();
    let config_version = config.config_version();
    let allowed_paths = config.get_allowed_real_paths(args.caller_package, args.caller_uid);
    let excluded_paths = config.get_excluded_real_paths(args.caller_package, args.caller_uid);
    let direct_allowed = path_list_matches(&allowed_paths, args.resolved_path, args.user_id);
    let direct_excluded = path_list_matches(&excluded_paths, args.resolved_path, args.user_id);
    let same_root_allowed = has_allowed_media_root(&allowed_paths, &media_root, args.user_id);
    let target_path = if args.decision.new_path.is_empty() {
        "<none>"
    } else {
        args.decision.new_path.as_str()
    };

    log::info!(
        "diag thumbnail proc={} caller={} uid={} user={} action={} exit={} path={} to={} root={} direct_allow={} direct_excl={} same_root_allow={} enabled_mem={} enabled_raw={} inferred={} cfg={} allow=[{}] excl=[{}] n={}",
        args.proc_package,
        args.caller_package,
        args.caller_uid,
        args.user_id,
        action_text(args.decision),
        args.exit_reason,
        args.resolved_path,
        target_path,
        media_root,
        direct_allowed,
        direct_excluded,
        same_root_allowed,
        args.enabled_in_memory,
        args.enabled_in_raw,
        args.is_caller_from_inferred,
        config_version,
        summarize_paths(&allowed_paths),
        summarize_paths(&excluded_paths),
        count
    );
}

fn thumbnail_media_root(path: &str, user_id: i32) -> Option<String> {
    if path.is_empty() || user_id < 0 {
        return None;
    }

    let storage_root = format!("/storage/emulated/{}", user_id);
    let storage_prefix = format!("{}/", storage_root);
    if !paths::starts_with(path, &storage_prefix) {
        return None;
    }

    let marker_start = if let Some(start) = path.find("/.thumbnails/") {
        start
    } else if path.ends_with("/.thumbnails") {
        path.len() - "/.thumbnails".len()
    } else {
        return None;
    };
    let media_root = &path[..marker_start];
    if media_root == storage_root {
        return None;
    }

    let root_name = &media_root[storage_prefix.len()..];
    if root_name.is_empty() || root_name.contains('/') {
        return None;
    }
    Some(media_root.to_string())
}

fn path_list_matches(path_list: &[String], target_path: &str, user_id: i32) -> bool {
    path_list.iter().any(|path| {
        let resolved = paths::resolve_user_path(&paths::normalize(path), user_id);
        paths::matches(&resolved, target_path, true)
    })
}

fn has_allowed_media_root(allowed_paths: &[String], media_root: &str, user_id: i32) -> bool {
    let root_prefix = format!("{}/", media_root);
    allowed_paths.iter().any(|path| {
        let resolved = paths::resolve_user_path(&paths::normalize(path), user_id);
        resolved == media_root || paths::starts_with(&resolved, &root_prefix)
    })
}

fn summarize_paths(paths: &[String]) -> String {
    if paths.is_empty() {
        return "-".to_string();
    }

    const MAX_PATHS: usize = 4;
    let mut summary = paths
        .iter()
        .take(MAX_PATHS)
        .map(String::as_str)
        .collect::<Vec<_>>()
        .join("|");
    if paths.len() > MAX_PATHS {
        summary.push_str(&format!("|+{}", paths.len() - MAX_PATHS));
    }
    summary
}

fn action_text(decision: &RedirectDecision) -> &'static str {
    match decision.action {
        RedirectAction::Allow => "allow",
        RedirectAction::Redirect => "redirect",
    }
}
