use crate::platform::paths;
use libc::{
    IN_CLOSE_WRITE, IN_CREATE, IN_DELETE, IN_MOVED_FROM, IN_MOVED_TO, c_int, inotify_add_watch,
    inotify_event, inotify_init1,
};
use std::ffi::CString;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicI64, Ordering};

const EVENT_MASK: u32 = IN_CREATE | IN_DELETE | IN_CLOSE_WRITE | IN_MOVED_FROM | IN_MOVED_TO;
const FALLBACK_POLL_INTERVAL_MS: i64 = 500;

static INOTIFY_FD: AtomicI32 = AtomicI32::new(-1);
static FALLBACK_POLL_ENABLED: AtomicBool = AtomicBool::new(false);
static LAST_FALLBACK_POLL_MS: AtomicI64 = AtomicI64::new(0);

// 初始化 inotify 并添加监听，返回 fd（用于 exempt）
// 必须在 pre_app_specialize 阶段调用（此时有 root 权限）
pub fn init(config_dir: &str) -> i32 {
    let fd = unsafe { inotify_init1(libc::IN_NONBLOCK | libc::IN_CLOEXEC) };
    if fd < 0 {
        FALLBACK_POLL_ENABLED.store(true, Ordering::Release);
        log::warn!("inotify init failed");
        return -1;
    }

    let mut fallback_poll_enabled = false;
    if !add_watch(fd, config_dir) {
        fallback_poll_enabled = true;
        log::warn!("watch config dir failed {}", config_dir);
    }

    let apps_dir = format!("{}/apps", config_dir);
    if !add_watch(fd, &apps_dir) {
        fallback_poll_enabled = true;
        log::debug!("apps dir missing or unwatchable");
    }

    INOTIFY_FD.store(fd, Ordering::Release);
    FALLBACK_POLL_ENABLED.store(fallback_poll_enabled, Ordering::Release);
    log::info!("config watcher ready fd={}", fd);
    fd
}

// 非阻塞读取 inotify 事件，仅在收到 .json 配置事件时返回 true
pub fn poll_changed() -> bool {
    let fd = INOTIFY_FD.load(Ordering::Acquire);
    if fd < 0 {
        return false;
    }

    let mut buf = [0u8; 1024];
    let len = unsafe { libc::read(fd, buf.as_mut_ptr() as *mut _, buf.len()) };
    if len <= 0 {
        return false;
    }

    let mut changed = false;
    let mut offset = 0;
    while offset < len as usize {
        let event = unsafe { &*(buf.as_ptr().add(offset) as *const inotify_event) };
        let event_len = std::mem::size_of::<inotify_event>() + event.len as usize;

        if is_config_event(event) {
            changed = true;
        }

        offset += event_len;
    }
    changed
}

// 综合 inotify 与兜底轮询，决定是否执行一次 reload_if_changed
pub fn should_reload() -> bool {
    poll_changed() || should_fallback_poll()
}

fn should_fallback_poll() -> bool {
    if !FALLBACK_POLL_ENABLED.load(Ordering::Acquire) {
        return false;
    }

    let now_ms = paths::monotonic_ms();
    let last_ms = LAST_FALLBACK_POLL_MS.load(Ordering::Relaxed);
    if now_ms.saturating_sub(last_ms) < FALLBACK_POLL_INTERVAL_MS {
        return false;
    }

    LAST_FALLBACK_POLL_MS
        .compare_exchange(last_ms, now_ms, Ordering::AcqRel, Ordering::Relaxed)
        .is_ok()
}

fn add_watch(fd: c_int, path: &str) -> bool {
    let Ok(c_path) = CString::new(path) else {
        return false;
    };
    let wd = unsafe { inotify_add_watch(fd, c_path.as_ptr(), EVENT_MASK) };
    wd >= 0
}

// 仅处理非目录的 .json 文件事件
fn is_config_event(event: &inotify_event) -> bool {
    if (event.mask & libc::IN_ISDIR) != 0 {
        return false;
    }
    if event.len > 0 {
        let name_ptr = unsafe { (event as *const inotify_event).add(1) as *const u8 };
        let name_slice = unsafe { std::slice::from_raw_parts(name_ptr, event.len as usize) };
        if let Ok(name) = std::str::from_utf8(name_slice) {
            return name.trim_end_matches('\0').ends_with(".json");
        }
    }
    true
}
