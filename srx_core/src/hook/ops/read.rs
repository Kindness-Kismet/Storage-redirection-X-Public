use super::super::context;
use super::super::runtime;
use libc::{c_int, c_void, fstat, readlink};
use std::ffi::CString;
use std::mem::MaybeUninit;
use std::sync::atomic::{AtomicI32, AtomicU64, Ordering};

static CACHED_FUSE_FD: AtomicI32 = AtomicI32::new(-1);
static CACHED_FUSE_FD_DEV: AtomicU64 = AtomicU64::new(0);
static CACHED_FUSE_FD_INO: AtomicU64 = AtomicU64::new(0);
static FUSE_CACHE_INVALIDATE_COUNT: AtomicU64 = AtomicU64::new(0);
const FUSE_CACHE_LOG_SAMPLE_STEP: u64 = 128;

#[repr(C)]
struct FuseInHeader {
    len: u32,
    opcode: u32,
    unique: u64,
    nodeid: u64,
    uid: u32,
    gid: u32,
    pid: u32,
}

// 借用 FUSE 协议头提取真实调用方 UID/PID
pub unsafe extern "C" fn hooked_read(fd: c_int, buf: *mut c_void, count: usize) -> isize {
    let self_ptr = hooked_read as *mut c_void;
    let result = runtime::call_prev_lazy(
        self_ptr,
        || libc::read(fd, buf, count),
        |prev| {
            let f: unsafe extern "C" fn(c_int, *mut c_void, usize) -> isize =
                std::mem::transmute(prev);
            f(fd, buf, count)
        },
    );

    if result < std::mem::size_of::<FuseInHeader>() as isize || buf.is_null() {
        return result;
    }

    let cached_fd = CACHED_FUSE_FD.load(Ordering::Relaxed);
    if cached_fd == -2 {
        return result;
    }

    if cached_fd == -1 {
        if !is_fuse_fd(fd) || !cache_fuse_fd(fd) {
            return result;
        }
    } else if fd == cached_fd && !is_cached_fuse_fd_match(fd) {
        invalidate_fuse_cache(fd, "fd_identity_changed");
        return result;
    }

    if fd != CACHED_FUSE_FD.load(Ordering::Relaxed) {
        return result;
    }

    let header = unsafe { std::ptr::read_unaligned(buf as *const FuseInHeader) };
    if !is_valid_fuse_header(&header, result as usize) {
        invalidate_fuse_cache(fd, "invalid_header");
        return result;
    }

    context::set_fuse_caller_uid(header.uid as i32);
    if header.pid > 0 && header.pid <= i32::MAX as u32 {
        context::set_fuse_caller_pid(header.pid as i32);
    }

    result
}

fn cache_fuse_fd(fd: c_int) -> bool {
    let Some((dev, ino)) = get_fd_identity(fd) else {
        return false;
    };

    CACHED_FUSE_FD.store(fd, Ordering::Relaxed);
    CACHED_FUSE_FD_DEV.store(dev, Ordering::Relaxed);
    CACHED_FUSE_FD_INO.store(ino, Ordering::Relaxed);
    log::info!("fuse fd lazy detected fd={} dev={} ino={}", fd, dev, ino);
    true
}

fn invalidate_fuse_cache(fd: c_int, reason: &str) {
    CACHED_FUSE_FD.store(-1, Ordering::Relaxed);
    CACHED_FUSE_FD_DEV.store(0, Ordering::Relaxed);
    CACHED_FUSE_FD_INO.store(0, Ordering::Relaxed);
    context::clear_fuse_caller_uid();

    let count = FUSE_CACHE_INVALIDATE_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
    if should_log_sample(count) {
        log::warn!(
            "fuse cache invalidated reason={} fd={} n={}",
            reason,
            fd,
            count
        );
    }
}

fn is_cached_fuse_fd_match(fd: c_int) -> bool {
    let expected_dev = CACHED_FUSE_FD_DEV.load(Ordering::Relaxed);
    let expected_ino = CACHED_FUSE_FD_INO.load(Ordering::Relaxed);
    if expected_dev == 0 || expected_ino == 0 {
        return false;
    }

    let Some((current_dev, current_ino)) = get_fd_identity(fd) else {
        return false;
    };

    current_dev == expected_dev && current_ino == expected_ino
}

fn is_valid_fuse_header(header: &FuseInHeader, read_len: usize) -> bool {
    let min_len = std::mem::size_of::<FuseInHeader>() as u32;
    if header.len < min_len || header.len as usize > read_len {
        return false;
    }
    if header.opcode == 0 || header.unique == 0 {
        return false;
    }
    header.uid <= i32::MAX as u32
}

fn is_fuse_fd(fd: c_int) -> bool {
    let link_path = format!("/proc/self/fd/{}", fd);
    let Ok(c_path) = CString::new(link_path) else {
        return false;
    };

    let mut link_buf = [0u8; 96];
    let len = unsafe {
        readlink(
            c_path.as_ptr(),
            link_buf.as_mut_ptr() as *mut _,
            link_buf.len() - 1,
        )
    };
    if len <= 0 {
        return false;
    }
    link_buf[len as usize] = 0;
    let text = String::from_utf8_lossy(&link_buf[..len as usize]);
    text == "/dev/fuse"
}

fn get_fd_identity(fd: c_int) -> Option<(u64, u64)> {
    let mut stat = MaybeUninit::<libc::stat>::uninit();
    let ret = unsafe { fstat(fd, stat.as_mut_ptr()) };
    if ret != 0 {
        return None;
    }

    let stat = unsafe { stat.assume_init() };
    Some((stat.st_dev, stat.st_ino))
}

#[inline]
fn should_log_sample(count: u64) -> bool {
    count == 1 || count.is_multiple_of(FUSE_CACHE_LOG_SAMPLE_STEP)
}
