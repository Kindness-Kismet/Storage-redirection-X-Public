use crate::platform::{module_paths, paths};
use libc::{O_CLOEXEC, O_CREAT, O_EXCL, O_WRONLY, open, stat};
use std::ffi::CString;
use std::fs;

const FNV_OFFSET_BASIS: u64 = 1469598103934665603;
const FNV_PRIME: u64 = 1099511628211;
const FINGERPRINT_SLOW_MS: i64 = 10;

pub fn compute_config_fingerprint(config_dir: &str) -> u64 {
    let started_ms = paths::monotonic_ms();
    let mut hash = FNV_OFFSET_BASIS;
    hash = fnv_update_str(hash, "srx_config_v2");
    hash = add_file_stat(hash, &format!("{}/global.json", config_dir));

    let apps_dir = format!("{}/apps", config_dir);
    let Ok(dir_entries) = fs::read_dir(&apps_dir) else {
        hash = fnv_update_str(hash, "apps_dir_missing");
        log_config_fingerprint_perf(config_dir, 0, started_ms, hash);
        return hash;
    };

    let mut config_paths: Vec<String> = Vec::new();
    for entry in dir_entries.flatten() {
        let name = entry.file_name();
        let name_str = name.to_string_lossy();
        if !name_str.ends_with(".json") {
            continue;
        }
        config_paths.push(format!("{}/{}", apps_dir, name_str));
    }

    config_paths.sort();
    hash = fnv_update_u64(hash, config_paths.len() as u64);
    let app_count = config_paths.len();
    for path in config_paths {
        hash = add_file_stat(hash, &path);
    }

    log_config_fingerprint_perf(config_dir, app_count, started_ms, hash);
    hash
}

// marker 文件 O_EXCL 成功即首次出现，用于一次性输出摘要日志
pub fn should_log_config_summary_once(config_dir: &str) -> bool {
    let fingerprint = compute_config_fingerprint(config_dir);
    let _ = fs::create_dir_all(module_paths::LOG_DIR);

    if let Ok(entries) = fs::read_dir(module_paths::LOG_DIR) {
        let mut marker_paths = Vec::new();
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            if !name.starts_with("config_") || !name.ends_with(".marker") {
                continue;
            }
            marker_paths.push(format!("{}/{}", module_paths::LOG_DIR, name));
        }

        const MAX_MARKERS: usize = 8;
        if marker_paths.len() > MAX_MARKERS {
            marker_paths.sort();
            let remove_count = marker_paths.len() - MAX_MARKERS;
            for path in marker_paths.iter().take(remove_count) {
                let _ = fs::remove_file(path);
            }
        }
    }

    let marker_path = format!(
        "{}/config_{}.marker",
        module_paths::LOG_DIR,
        format_fingerprint_hex(fingerprint)
    );

    let Ok(c_path) = CString::new(marker_path) else {
        return true;
    };
    let fd = unsafe {
        open(
            c_path.as_ptr(),
            O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC,
            0o600,
        )
    };
    if fd >= 0 {
        unsafe { libc::close(fd) };
        return true;
    }
    if last_errno() == libc::EEXIST {
        return false;
    }
    true
}

fn add_file_stat(mut hash: u64, path: &str) -> u64 {
    let Ok(c_path) = CString::new(path) else {
        hash = fnv_update_str(hash, path);
        hash = fnv_update_u64(hash, 0);
        return hash;
    };

    let mut st = std::mem::MaybeUninit::<stat>::uninit();
    let ret = unsafe { libc::stat(c_path.as_ptr(), st.as_mut_ptr()) };
    if ret != 0 {
        hash = fnv_update_str(hash, path);
        hash = fnv_update_u64(hash, 0);
        return hash;
    }

    let st = unsafe { st.assume_init() };
    hash = fnv_update_str(hash, path);
    hash = fnv_update_i64(hash, st.st_mtime);
    hash = fnv_update_i64(hash, st.st_mtime_nsec);
    hash = fnv_update_i64(hash, st.st_size);
    hash
}

fn fnv_update_str(hash: u64, value: &str) -> u64 {
    let bytes = value.as_bytes();
    fnv_update(hash, bytes)
}

fn fnv_update_u64(hash: u64, value: u64) -> u64 {
    fnv_update(hash, &value.to_le_bytes())
}

fn fnv_update_i64(hash: u64, value: i64) -> u64 {
    fnv_update(hash, &value.to_le_bytes())
}

fn fnv_update(mut hash: u64, bytes: &[u8]) -> u64 {
    for &b in bytes {
        hash ^= b as u64;
        hash = hash.wrapping_mul(FNV_PRIME);
    }
    hash
}

fn format_fingerprint_hex(value: u64) -> String {
    format!("{:016x}", value)
}

fn log_config_fingerprint_perf(config_dir: &str, app_count: usize, started_ms: i64, hash: u64) {
    let elapsed_ms = paths::monotonic_ms().saturating_sub(started_ms);
    if elapsed_ms >= FINGERPRINT_SLOW_MS || app_count >= 100 {
        log::info!(
            "perf config fingerprint dir={} apps={} ms={} fp={:x}",
            config_dir,
            app_count,
            elapsed_ms,
            hash
        );
    }
}

fn last_errno() -> i32 {
    unsafe { *libc::__errno() }
}
