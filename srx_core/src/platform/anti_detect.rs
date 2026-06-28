use std::ffi::CString;
use std::fs::File;
use std::io::{BufRead, BufReader};
use std::sync::atomic::{AtomicU64, Ordering};

const PR_SET_VMA: libc::c_int = 0x53564d41;
const PR_SET_VMA_ANON_NAME: libc::c_ulong = 0;
const ANON_REGION_LOG_STEP: u64 = 128;
// 命名 guest 代码缓存会破坏转译器内部管理并触发信号异常
const TRANSLATOR_MARKERS: &[&str] = &[
    "libndk_translation.so", // berberis (AOSP / Google)
    "libberberis",           // berberis 相关库前缀
    "libhoudini",            // Intel Houdini
    "houdini",               // /system/lib/arm[64]/nb/houdini 等路径
    "libnb.so",              // Intel native bridge
];
static ANON_REGION_LOG_COUNT: AtomicU64 = AtomicU64::new(0);

#[inline]
fn should_log_step(count: u64, step: u64) -> bool {
    count == 1 || count.is_multiple_of(step)
}

// 扫描 /proc/self/maps 给无名的可执行匿名内存命名，返回命名成功数量
pub fn name_anonymous_executable_regions() -> usize {
    let mut count = 0;
    let file = match File::open("/proc/self/maps") {
        Ok(f) => f,
        Err(_) => return 0,
    };
    let reader = BufReader::new(file);
    let lines: Vec<String> = reader.lines().map_while(Result::ok).collect();

    // 命中转译器标志则整体跳过，避免误伤 guest 代码缓存
    if lines
        .iter()
        .any(|l| TRANSLATOR_MARKERS.iter().any(|m| l.contains(m)))
    {
        log::info!("translator detected, skip anon rename");
        return 0;
    }

    for line in lines {
        // maps 行格式: start-end perms offset dev inode pathname
        // 例: 305a885000-305a903000 r-xp 00000000 00:00 0
        let parts: Vec<&str> = line.split_whitespace().collect();
        if parts.len() < 5 {
            continue;
        }

        let range = parts[0];
        let perms = parts[1];
        let dev = parts[3];

        // 只处理 r-xp 且 dev=00:00（匿名）且无 pathname 的区域
        if perms != "r-xp" || dev != "00:00" {
            continue;
        }

        if parts.len() > 5 && !parts[5].is_empty() {
            continue;
        }

        let mut range_parts = range.split('-');
        let start_str = match range_parts.next() {
            Some(s) => s,
            None => continue,
        };
        let end_str = match range_parts.next() {
            Some(s) => s,
            None => continue,
        };
        let start = match usize::from_str_radix(start_str, 16) {
            Ok(v) => v,
            Err(_) => continue,
        };
        let end = match usize::from_str_radix(end_str, 16) {
            Ok(v) => v,
            Err(_) => continue,
        };
        let len = end.saturating_sub(start);
        if len == 0 {
            continue;
        }

        if set_vma_name(start, len, "dalvik-jit-code-cache").is_ok() {
            let log_count = ANON_REGION_LOG_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
            if should_log_step(log_count, ANON_REGION_LOG_STEP) {
                log::info!(
                    "anon region named range=0x{:x}-0x{:x} kb={} name=dalvik-jit-code-cache n={}",
                    start,
                    end,
                    len / 1024,
                    log_count
                );
            }
            count += 1;
        }
    }

    count
}

fn set_vma_name(addr: usize, len: usize, name: &str) -> Result<(), ()> {
    let c_name = CString::new(name).map_err(|_| ())?;
    let result = unsafe {
        libc::prctl(
            PR_SET_VMA,
            PR_SET_VMA_ANON_NAME,
            addr as libc::c_ulong,
            len as libc::c_ulong,
            c_name.as_ptr() as libc::c_ulong,
        )
    };
    if result != 0 {
        return Err(());
    }
    Ok(())
}
