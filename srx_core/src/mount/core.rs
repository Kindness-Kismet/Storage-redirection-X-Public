use super::MountPlanner;
use crate::platform::{fs, paths};
use libc::{
    CLONE_NEWNS, MS_BIND, MS_PRIVATE, MS_REC, chmod, chown, mount, readlink, stat as c_stat,
    unshare,
};
use std::ffi::{CStr, CString};
use std::fs as std_fs;
use std::sync::atomic::{AtomicU64, Ordering};

const MEDIA_RW_GID: u32 = 1023;
const MAPPED_DIR_MODE: libc::mode_t = 0o2770;
const REDIRECT_DIR_REPAIR_MAX_DEPTH: usize = 3;
const REDIRECT_DIR_REPAIR_MAX_COUNT: usize = 512;
const BIND_SUCCESS_LOG_STEP: u64 = 128;
const BIND_VERIFY_PASS_LOG_STEP: u64 = 256;
static BIND_SUCCESS_COUNT: AtomicU64 = AtomicU64::new(0);
static BIND_VERIFY_PASS_COUNT: AtomicU64 = AtomicU64::new(0);

#[inline]
fn should_log_step(count: u64, step: u64) -> bool {
    count == 1 || count.is_multiple_of(step)
}

impl MountPlanner {
    // 按需 unshare 并设置 /storage 为 MS_PRIVATE
    pub(super) fn ensure_mount_namespace_prepared(&mut self) -> bool {
        if self.is_namespace_ready {
            return true;
        }

        if self.should_unshare {
            let ret = unsafe { unshare(CLONE_NEWNS) };
            if ret != 0 {
                log::error!("mount ns: unshare failed errno={}", last_errno());
                return false;
            }
        }

        let Ok(storage) = CString::new("/storage") else {
            return false;
        };
        let ret = unsafe {
            mount(
                std::ptr::null(),
                storage.as_ptr(),
                std::ptr::null(),
                (MS_REC | MS_PRIVATE) as libc::c_ulong,
                std::ptr::null(),
            )
        };
        if ret != 0 {
            log::warn!(
                "mount ns: /storage MS_PRIVATE failed errno={}",
                last_errno()
            );
        }

        let mut buf = [0u8; 256];
        let Ok(ns_path) = CString::new("/proc/self/ns/mnt") else {
            return false;
        };
        let len = unsafe { readlink(ns_path.as_ptr(), buf.as_mut_ptr() as *mut _, buf.len() - 1) };
        if len > 0 {
            let text = String::from_utf8_lossy(&buf[..len as usize]);
            log::info!("mount ns ready ns={}", text);
        } else {
            log::warn!("mount ns: readlink failed errno={}", last_errno());
        }

        self.is_namespace_ready = true;
        true
    }

    pub(super) fn ensure_directory_exists(&self, path: &str, should_chown: bool) -> bool {
        let uid = if should_chown { self.app_uid } else { -1 };
        fs::create_directory(path, uid)
    }

    // 创建或修正映射目录的所有者，使应用可写入
    pub(super) fn ensure_writable_mapped_directory(&self, path: &str, owner_uid: i32) -> bool {
        log::debug!("mount dir prep path={} owner={}", path, owner_uid);
        let is_existing = fs::is_directory(path);
        if !is_existing && !fs::create_directory(path, owner_uid) {
            log::warn!("mount dir: missing and mkdir failed path={}", path);
            return false;
        }

        self.fix_writable_directory(path, owner_uid)
    }

    pub(super) fn ensure_writable_mapped_directory_chain(
        &self,
        path: &str,
        owner_uid: i32,
    ) -> bool {
        log::debug!("mount dir chain prep path={} owner={}", path, owner_uid);
        if !fs::is_directory(path) && !fs::create_directory(path, owner_uid) {
            log::warn!("mount dir chain: missing and mkdir failed path={}", path);
            return false;
        }

        let normalized = paths::normalize(path);
        let data_media_root = format!("/data/media/{}", self.user_id);
        let data_media_prefix = format!("{}/", data_media_root);
        if !paths::starts_with(&normalized, &data_media_prefix) {
            return self.fix_writable_directory(&normalized, owner_uid);
        }

        let relative = &normalized[data_media_prefix.len()..];
        let segments: Vec<&str> = relative
            .split('/')
            .filter(|segment| !segment.is_empty())
            .collect();
        if segments.is_empty() {
            return self.fix_writable_directory(&normalized, owner_uid);
        }

        let mut current = data_media_root;
        let mut ok = true;
        for (index, segment) in segments.iter().enumerate() {
            current = paths::join(&current, segment);
            let is_final = index + 1 == segments.len();
            // Keep standard top-level public folders such as Download/Pictures intact,
            // but repair custom mapping containers below them so MediaProvider can write.
            if index == 0 && !is_final {
                continue;
            }
            if fs::is_directory(&current) && !self.fix_writable_directory(&current, owner_uid) {
                ok = false;
            }
        }
        ok
    }

    pub(super) fn repair_redirect_target_directories(&self, root: &str) {
        let mut repaired_count = 0usize;
        self.repair_redirect_target_directories_inner(root, 0, &mut repaired_count);
        if repaired_count > 0 {
            log::info!("redirect dir repair root={} dirs={}", root, repaired_count);
        }
    }

    fn repair_redirect_target_directories_inner(
        &self,
        path: &str,
        depth: usize,
        repaired_count: &mut usize,
    ) {
        if depth >= REDIRECT_DIR_REPAIR_MAX_DEPTH
            || *repaired_count >= REDIRECT_DIR_REPAIR_MAX_COUNT
        {
            return;
        }

        let Ok(entries) = std_fs::read_dir(path) else {
            return;
        };
        for entry in entries.flatten() {
            if *repaired_count >= REDIRECT_DIR_REPAIR_MAX_COUNT {
                return;
            }
            let Ok(file_type) = entry.file_type() else {
                continue;
            };
            if !file_type.is_dir() || file_type.is_symlink() {
                continue;
            }
            let dir_path = entry.path().to_string_lossy().to_string();
            if self.fix_writable_directory(&dir_path, self.app_uid) {
                *repaired_count += 1;
            }
            self.repair_redirect_target_directories_inner(&dir_path, depth + 1, repaired_count);
        }
    }

    fn fix_writable_directory(&self, path: &str, owner_uid: i32) -> bool {
        let Ok(c_path) = CString::new(path) else {
            return false;
        };

        let mut ok = true;
        if owner_uid >= 0 {
            let ret = unsafe { chown(c_path.as_ptr(), owner_uid as u32, MEDIA_RW_GID) };
            if ret != 0 {
                ok = false;
                log::warn!(
                    "mount dir: chown failed errno={} {} path={}",
                    last_errno(),
                    errno_text(),
                    path
                );
            }
        }

        let ret = unsafe { chmod(c_path.as_ptr(), MAPPED_DIR_MODE) };
        if ret != 0 {
            ok = false;
            log::warn!(
                "mount dir: chmod failed errno={} {} path={}",
                last_errno(),
                errno_text(),
                path
            );
        }

        ok
    }

    pub(super) fn to_data_media_backend_path(&self, storage_path: &str) -> String {
        let prefix = format!("/storage/emulated/{}/", self.user_id);
        if !paths::starts_with(storage_path, &prefix) {
            return String::new();
        }
        let suffix = &storage_path[prefix.len()..];
        format!("/data/media/{}/{}", self.user_id, suffix)
    }

    pub(super) fn normalize_path(&self, path: &str) -> String {
        paths::normalize(path)
    }

    pub(super) fn resolve_user_path(&self, path: &str) -> String {
        paths::resolve_user_path(path, self.user_id)
    }

    pub(super) fn resolve_placeholders(&self, path: &str) -> String {
        let has_redirect_placeholder =
            path.contains("${REDIRECT_TARGET}") || path.contains("$REDIRECT_TARGET");
        if has_redirect_placeholder && self.redirect_target.is_empty() {
            log::warn!(
                "mount path: redirect target missing, cannot expand placeholder path={}",
                path
            );
        }

        paths::resolve_placeholders(path, &self.app_data_dir, &self.redirect_target)
    }

    pub(super) fn bind_mount(&self, source: &str, target: &str, is_recursive: bool) -> bool {
        let Ok(c_source) = CString::new(source) else {
            return false;
        };
        let Ok(c_target) = CString::new(target) else {
            return false;
        };

        let mut flags = MS_BIND;
        if is_recursive {
            flags |= MS_REC;
        }

        let ret = unsafe {
            mount(
                c_source.as_ptr(),
                c_target.as_ptr(),
                std::ptr::null(),
                flags as libc::c_ulong,
                std::ptr::null(),
            )
        };
        if ret != 0 {
            log::error!(
                "bind mount failed src={} dst={} errno={} {}",
                source,
                target,
                last_errno(),
                errno_text()
            );
            return false;
        }

        self.record_mounted_target(target);

        let bind_count = BIND_SUCCESS_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
        if should_log_step(bind_count, BIND_SUCCESS_LOG_STEP) {
            log::debug!("bind ok src={} dst={} n={}", source, target, bind_count);
        }
        let Ok(c_source_stat) = CString::new(source) else {
            return true;
        };
        let Ok(c_target_stat) = CString::new(target) else {
            return true;
        };
        let mut st_source = std::mem::MaybeUninit::<c_stat>::uninit();
        let mut st_target = std::mem::MaybeUninit::<c_stat>::uninit();
        let is_source_ok =
            unsafe { libc::stat(c_source_stat.as_ptr(), st_source.as_mut_ptr()) } == 0;
        let is_target_ok =
            unsafe { libc::stat(c_target_stat.as_ptr(), st_target.as_mut_ptr()) } == 0;
        if is_source_ok && is_target_ok {
            let st_source = unsafe { st_source.assume_init() };
            let st_target = unsafe { st_target.assume_init() };
            let is_same =
                st_source.st_dev == st_target.st_dev && st_source.st_ino == st_target.st_ino;
            if is_same {
                let verify_ok_count = BIND_VERIFY_PASS_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
                if should_log_step(verify_ok_count, BIND_VERIFY_PASS_LOG_STEP) {
                    log::debug!(
                        "bind verify ok src={} dst={} n={}",
                        source,
                        target,
                        verify_ok_count
                    );
                }
            } else {
                log::warn!(
                    "bind verify mismatch src={} dst={} sdev={} tdev={} sino={} tino={}",
                    source,
                    target,
                    st_source.st_dev,
                    st_target.st_dev,
                    st_source.st_ino,
                    st_target.st_ino
                );
            }
        } else {
            log::warn!(
                "bind verify failed src={} dst={} reason=stat",
                source,
                target
            );
        }
        true
    }

    fn record_mounted_target(&self, target: &str) {
        if target.is_empty() {
            return;
        }
        let mut targets = self.mounted_targets.borrow_mut();
        if !targets.iter().any(|item| item == target) {
            targets.push(target.to_string());
        }
    }
}

fn last_errno() -> i32 {
    unsafe { *libc::__errno() }
}

fn errno_text() -> String {
    let code = last_errno();
    unsafe {
        CStr::from_ptr(libc::strerror(code))
            .to_string_lossy()
            .to_string()
    }
}
