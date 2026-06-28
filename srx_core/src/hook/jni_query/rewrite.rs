use super::super::stats::InterceptHub;
use super::types::{FILE_SCHEME_PREFIX, SAMPLE_LOG_INITIAL, SAMPLE_LOG_INTERVAL, STORAGE_PREFIXES};
use crate::config::SettingsHub;
use crate::redirect::{policy, process_redirect_path};
use std::ffi::CString;
use std::sync::atomic::{AtomicU32, Ordering};

static REWRITE_SAMPLE_COUNTER: AtomicU32 = AtomicU32::new(0);

// 路径命中重定向且目标不存在时返回空字符串，避免展示无效媒体行
pub(crate) fn rewrite_cursor_storage_path_for_caller(
    original_text: &str,
    caller_uid: i32,
    preserve_missing_target: bool,
) -> Option<String> {
    if caller_uid < 0 {
        return None;
    }
    let (path_text, _) = split_storage_path(original_text)?;
    let caller_package = resolve_caller_package(caller_uid, path_text);
    if caller_package.is_empty() {
        return None;
    }
    let hub = InterceptHub::instance();
    let previous_package = hub.get_current_caller_package();
    let previous_uid = hub.get_current_caller_uid();
    hub.set_current_caller_package(&caller_package);
    hub.set_current_caller_uid(caller_uid);
    let result = rewrite_cursor_storage_path_with_mode(original_text, !preserve_missing_target);
    hub.set_current_caller_package(&previous_package);
    hub.set_current_caller_uid(previous_uid);
    result
}

pub(super) fn rewrite_existing_cursor_storage_path(original_text: &str) -> Option<String> {
    rewrite_cursor_storage_path_with_mode(original_text, false)
}

fn rewrite_cursor_storage_path_with_mode(
    original_text: &str,
    can_hide_rows: bool,
) -> Option<String> {
    let hub = InterceptHub::instance();
    if hub.is_monitor_only() {
        return None;
    }
    rewrite_cursor_storage_path_inner(original_text, can_hide_rows, |path_text| {
        process_redirect_path(hub, path_text)
    })
}

fn rewrite_cursor_storage_path_inner<F>(
    original_text: &str,
    can_hide_rows: bool,
    decide: F,
) -> Option<String>
where
    F: FnOnce(&str) -> crate::redirect::RedirectDecision,
{
    let (path_text, has_file_scheme) = split_storage_path(original_text)?;
    if path_text.is_empty() {
        return None;
    }
    // MediaStore 缩略图缓存条目指向 FUSE 内部路径，重定向后不可达
    if path_text.contains("/.thumbnails/") {
        if can_hide_rows {
            sample_row_log("thumbnails", path_text, "", has_file_scheme, true);
            return Some(String::new());
        }
        return None;
    }

    let decision = decide(path_text);
    if !decision.is_redirect() {
        return None;
    }
    if decision.new_path.is_empty() {
        if can_hide_rows {
            sample_row_log("empty_target", path_text, "", has_file_scheme, true);
            return Some(String::new());
        }
        return None;
    }
    if path_exists_by_syscall(&decision.new_path) {
        let rewritten = if has_file_scheme {
            format!("{}{}", FILE_SCHEME_PREFIX, decision.new_path)
        } else {
            decision.new_path
        };
        sample_row_log("rewrite", path_text, &rewritten, has_file_scheme, false);
        return Some(rewritten);
    }
    if can_hide_rows {
        sample_row_log(
            "missing_target",
            path_text,
            &decision.new_path,
            has_file_scheme,
            true,
        );
        return Some(String::new());
    }
    None
}

fn resolve_caller_package(caller_uid: i32, path_text: &str) -> String {
    let mut packages = policy::get_packages_for_uid(caller_uid);
    if packages.is_empty() {
        policy::refresh_shared_uid_cache();
        packages = policy::get_packages_for_uid(caller_uid);
    }
    packages.sort();
    packages.dedup();
    packages.retain(|pkg| !pkg.is_empty() && !policy::is_system_writer_package(pkg));
    if packages.len() == 1 {
        return packages[0].clone();
    }
    let inferred =
        SettingsHub::instance().resolve_redirect_package_by_path_for_user(caller_uid, path_text);
    if !inferred.is_empty() {
        return inferred;
    }
    let mut enabled = packages
        .into_iter()
        .filter(|pkg| SettingsHub::instance().should_redirect(pkg, caller_uid))
        .collect::<Vec<_>>();
    if enabled.len() == 1 {
        return enabled.remove(0);
    }
    String::new()
}

// 通过 syscall 检查路径是否存在，避免走 Hook 链路
fn path_exists_by_syscall(path: &str) -> bool {
    let Ok(c_path) = CString::new(path) else {
        return false;
    };

    let ret = unsafe {
        libc::syscall(
            libc::SYS_faccessat,
            libc::AT_FDCWD,
            c_path.as_ptr(),
            libc::F_OK,
            0,
        ) as libc::c_int
    };
    ret == 0
}

// 前 SAMPLE_LOG_INITIAL 条全量输出，之后每 SAMPLE_LOG_INTERVAL 条采样一次
pub(super) fn sample_row_log(
    reason: &str,
    before: &str,
    after: &str,
    has_file_scheme: bool,
    is_filter: bool,
) {
    let index = REWRITE_SAMPLE_COUNTER.fetch_add(1, Ordering::Relaxed);
    if index >= SAMPLE_LOG_INITIAL
        && !(index - SAMPLE_LOG_INITIAL).is_multiple_of(SAMPLE_LOG_INTERVAL)
    {
        return;
    }

    let hub = InterceptHub::instance();
    if is_filter {
        log::debug!(
            "row filter reason={} caller={} uid={} path={} target={} file_scheme={} n={}",
            reason,
            hub.get_current_caller_package(),
            hub.get_current_caller_uid(),
            before,
            if after.is_empty() { "empty" } else { after },
            has_file_scheme,
            index + 1
        );
    } else {
        log::debug!(
            "row rewrite reason={} caller={} uid={} from={} to={} file_scheme={} n={}",
            reason,
            hub.get_current_caller_package(),
            hub.get_current_caller_uid(),
            before,
            after,
            has_file_scheme,
            index + 1
        );
    }
}

fn split_storage_path(text: &str) -> Option<(&str, bool)> {
    if text.is_empty() {
        return None;
    }

    if let Some(path_text) = text.strip_prefix(FILE_SCHEME_PREFIX)
        && STORAGE_PREFIXES
            .iter()
            .any(|prefix| path_text.starts_with(prefix))
    {
        return Some((path_text, true));
    }

    if STORAGE_PREFIXES
        .iter()
        .any(|prefix| text.starts_with(prefix))
    {
        return Some((text, false));
    }
    None
}
