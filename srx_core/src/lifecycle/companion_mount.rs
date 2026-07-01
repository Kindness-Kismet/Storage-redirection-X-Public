mod diagnostics;
mod stats;
mod status_marker;
mod sys;

use super::companion_request::CompanionMountRequest;
use crate::mount::MountPlanner;
use crate::platform::paths::monotonic_ms;
use crate::platform::unique_fd::UniqueFd;
use diagnostics::log_child_diagnostics;
use libc::{
    AF_UNIX, CLONE_NEWNS, O_CLOEXEC, O_RDONLY, SIGKILL, SIGTERM, SO_RCVTIMEO, SOCK_DGRAM,
    SOL_SOCKET, c_int, c_void, close, kill, open, read, readlink, recv, send, setns, setsockopt,
    socketpair, waitpid,
};
use stats::update_redirect_stats;
use status_marker::write_mount_status_marker;
use sys::{c_str, decode_wait_status, errno_text, last_errno};

// 父进程等待挂载结果的超时 — 之前的 2s 在高负载/FUSE 异常场景下
// 容易直接进入 SIGKILL 流程，把 mount writer 持锁的子进程强杀，
// 进而损坏 FUSE 命名空间状态拖死 MediaProvider。
const PARENT_RECV_PRIMARY_TIMEOUT_SEC: i64 = 5;
// SIGTERM 后再给的 grace，让子进程在用户态完成清理或回报结果后退出。
const PARENT_RECV_GRACE_TIMEOUT_SEC: i64 = 1;
const COMPANION_MOUNT_SLOW_MS: i64 = 20;

// 等待目标进程就绪后在子进程中执行挂载
pub fn execute_companion_mount_request(request: &CompanionMountRequest) -> bool {
    let started_ms = monotonic_ms();
    let wait_started_ms = monotonic_ms();
    let is_ready = wait_for_process(request.pid, 5000);
    let wait_ms = monotonic_ms().saturating_sub(wait_started_ms);
    if !is_ready {
        log::warn!("wait proc not ready pid={}", request.pid);
    }
    let mount_started_ms = monotonic_ms();
    let is_success = run_mount_in_forked_child(request);
    let mount_ms = monotonic_ms().saturating_sub(mount_started_ms);
    let marker_started_ms = monotonic_ms();
    let marker_ok =
        write_mount_status_marker(&request.app_data_dir, request.pid, request.uid, is_success);
    let marker_ms = monotonic_ms().saturating_sub(marker_started_ms);
    log_companion_mount_perf(
        request, is_success, marker_ok, wait_ms, mount_ms, marker_ms, started_ms,
    );
    is_success
}

fn log_companion_mount_perf(
    request: &CompanionMountRequest,
    is_success: bool,
    marker_ok: bool,
    wait_ms: i64,
    mount_ms: i64,
    marker_ms: i64,
    started_ms: i64,
) {
    let total_ms = monotonic_ms().saturating_sub(started_ms);
    if total_ms < COMPANION_MOUNT_SLOW_MS && is_success && marker_ok {
        return;
    }
    log::info!(
        "perf companion mount pkg={} pid={} uid={} ok={} marker={} allow={} map={} map_only={} wait_ms={} mount_ms={} marker_ms={} total_ms={}",
        request.package_name,
        request.pid,
        request.uid,
        is_success,
        marker_ok,
        request.allowed_real_paths.len(),
        request.path_mappings.len(),
        request.is_mapping_mode_only,
        wait_ms,
        mount_ms,
        marker_ms,
        total_ms
    );
}

// 切换到目标进程的挂载命名空间
fn set_mount_namespace(pid: i32) -> bool {
    let ns_path = format!("/proc/{}/ns/mnt", pid);
    let Ok(c_path) = std::ffi::CString::new(ns_path.clone()) else {
        log::error!("ns path invalid pid={} path={}", pid, ns_path);
        return false;
    };
    let fd = unsafe { open(c_path.as_ptr(), O_RDONLY | O_CLOEXEC) };
    if fd < 0 {
        let errno = last_errno();
        log::error!(
            "ns open failed pid={} errno={} {}",
            pid,
            errno,
            errno_text(errno)
        );
        return false;
    }
    let file = UniqueFd::new(fd);

    if unsafe { setns(file.get(), CLONE_NEWNS) } != 0 {
        let errno = last_errno();
        log::error!(
            "setns failed pid={} errno={} {}",
            pid,
            errno,
            errno_text(errno)
        );
        return false;
    }

    log::info!("entered ns pid={}", pid);
    let mut buf = [0u8; 256];
    let Some(self_ns_path) = c_str("/proc/self/ns/mnt") else {
        log::warn!("ns readlink path failed");
        return true;
    };
    let len = unsafe {
        readlink(
            self_ns_path.as_ptr(),
            buf.as_mut_ptr() as *mut _,
            buf.len() - 1,
        )
    };
    if len > 0 {
        buf[len as usize] = 0;
        let text = String::from_utf8_lossy(&buf[..len as usize]);
        log::info!("ns now={}", text);
    } else {
        let errno = last_errno();
        log::warn!(
            "ns read failed pid={} errno={} {}",
            pid,
            errno,
            errno_text(errno)
        );
    }
    true
}

// 轮询目标进程 SELinux 上下文，等待脱离 zygote 状态
fn wait_for_process(pid: i32, timeout_ms: i32) -> bool {
    let poll_interval_us = 5 * 1000;
    let timeout_us = timeout_ms * 1000;
    let mut elapsed_us = 0;
    let attr_path = format!("/proc/{}/attr/current", pid);
    let mut last_context = String::new();

    let Ok(c_path) = std::ffi::CString::new(attr_path.clone()) else {
        log::warn!("attr path invalid pid={}", pid);
        return false;
    };

    while elapsed_us < timeout_us {
        let fd = unsafe { open(c_path.as_ptr(), O_RDONLY | O_CLOEXEC) };
        if fd < 0 {
            let errno = last_errno();
            log::warn!(
                "attr open failed pid={} errno={} {}",
                pid,
                errno,
                errno_text(errno)
            );
            return false;
        }
        let file = UniqueFd::new(fd);
        let mut buf = [0u8; 256];
        let n = unsafe { read(file.get(), buf.as_mut_ptr() as *mut c_void, buf.len() - 1) };
        if n < 0 {
            let errno = last_errno();
            log::warn!(
                "attr read failed pid={} errno={} {}",
                pid,
                errno,
                errno_text(errno)
            );
            return false;
        }
        if n > 0 {
            if let Ok(text) = std::str::from_utf8(&buf[..n as usize]) {
                let context = text.trim().to_string();
                last_context = context.clone();
                if !context.contains("zygote") {
                    log::debug!("proc ctx ready pid={} ctx={}", pid, context);
                    return true;
                }
            } else {
                log::warn!("attr not utf8 pid={} bytes={}", pid, n);
            }
        }

        unsafe { libc::usleep(poll_interval_us as u32) };
        elapsed_us += poll_interval_us;
    }

    log::warn!(
        "proc ctx timeout pid={} ms={} last={}",
        pid,
        timeout_ms,
        if last_context.is_empty() {
            "<empty>"
        } else {
            &last_context
        }
    );
    false
}

fn send_mount_result(sock: c_int, result: i32) -> bool {
    let expected_size = std::mem::size_of::<i32>() as isize;
    let sent = unsafe {
        send(
            sock,
            &result as *const _ as *const c_void,
            std::mem::size_of::<i32>(),
            0,
        )
    };
    if sent != expected_size {
        if sent < 0 {
            let errno = last_errno();
            log::warn!(
                "send result failed sock={} errno={} {}",
                sock,
                errno,
                errno_text(errno)
            );
        } else {
            log::warn!(
                "send result short sock={} sent={} want={}",
                sock,
                sent,
                expected_size
            );
        }
        return false;
    }
    log::debug!("send result sock={} ret={}", sock, result);
    true
}

// 父进程等待子进程挂载结果并回收子进程
fn handle_parent_process(child: i32, sock: c_int) -> bool {
    set_recv_timeout(sock, child, PARENT_RECV_PRIMARY_TIMEOUT_SEC);

    let mut result: i32 = -1;
    let expected_size = std::mem::size_of::<i32>() as isize;
    let mut n = recv_result(sock, &mut result);

    // 主超时未拿到结果时按 SIGTERM -> grace -> SIGKILL 渐进推进，
    // 避免在子进程仍持有 mount writer 时立刻 SIGKILL 损伤 FUSE 状态。
    if n != expected_size {
        log_recv_failure(child, n, expected_size, "primary");
        log_child_diagnostics(child, "primary_timeout");

        if unsafe { kill(child, SIGTERM) } != 0 {
            let errno = last_errno();
            log::warn!(
                "term child failed child={} errno={} {}",
                child,
                errno,
                errno_text(errno)
            );
        }

        set_recv_timeout(sock, child, PARENT_RECV_GRACE_TIMEOUT_SEC);
        n = recv_result(sock, &mut result);
        if n == expected_size {
            log::warn!("child late result child={} ret={}", child, result);
        } else {
            log_recv_failure(child, n, expected_size, "grace");
            log_child_diagnostics(child, "grace_timeout");
            log::warn!("child stuck after term child={} forcing kill", child);
            if unsafe { kill(child, SIGKILL) } != 0 {
                let errno = last_errno();
                log::warn!(
                    "kill child failed child={} errno={} {}",
                    child,
                    errno,
                    errno_text(errno)
                );
            }
        }
    }
    unsafe { close(sock) };

    let mut status: c_int = 0;
    let wait_ret = unsafe { waitpid(child, &mut status as *mut _, 0) };
    if wait_ret < 0 {
        let errno = last_errno();
        log::warn!(
            "waitpid failed child={} errno={} {}",
            child,
            errno,
            errno_text(errno)
        );
    } else {
        log::info!(
            "child reaped child={} status={} raw={}",
            child,
            decode_wait_status(status),
            status
        );
    }

    let is_success = result == 0;
    if is_success {
        update_redirect_stats();
    } else {
        log::warn!("mount failed child={} recv={} ret={}", child, n, result);
    }
    is_success
}

fn set_recv_timeout(sock: c_int, child: i32, seconds: i64) {
    let tv = libc::timeval {
        tv_sec: seconds,
        tv_usec: 0,
    };
    let opt_ret = unsafe {
        setsockopt(
            sock,
            SOL_SOCKET,
            SO_RCVTIMEO,
            &tv as *const _ as *const c_void,
            std::mem::size_of::<libc::timeval>() as u32,
        )
    };
    if opt_ret != 0 {
        let errno = last_errno();
        log::warn!(
            "setsockopt failed child={} sec={} errno={} {}",
            child,
            seconds,
            errno,
            errno_text(errno)
        );
    }
}

fn recv_result(sock: c_int, result: &mut i32) -> isize {
    unsafe {
        recv(
            sock,
            result as *mut _ as *mut c_void,
            std::mem::size_of::<i32>(),
            0,
        )
    }
}

fn log_recv_failure(child: i32, n: isize, expected: isize, phase: &str) {
    if n < 0 {
        let errno = last_errno();
        log::warn!(
            "recv result failed child={} phase={} errno={} {}",
            child,
            phase,
            errno,
            errno_text(errno)
        );
    } else {
        log::warn!(
            "recv result short child={} phase={} recv={} want={}",
            child,
            phase,
            n,
            expected
        );
    }
}

// 子进程切换命名空间并执行实际挂载
fn handle_child_process(request: &CompanionMountRequest, sock: c_int) -> bool {
    if !set_mount_namespace(request.pid) {
        log::error!(
            "child setns failed pid={} pkg={}",
            request.pid,
            request.package_name
        );
        let _ = send_mount_result(sock, -1);
        unsafe { close(sock) };
        return false;
    }

    let mut mount_mgr = MountPlanner::new(
        &request.package_name,
        request.uid,
        &request.app_data_dir,
        &request.redirect_target,
        false,
    );

    let is_success = if request.is_mapping_mode_only {
        log::info!("map-only mount count={}", request.path_mappings.len());
        mount_mgr.apply_path_mappings_only(&request.path_mappings)
    } else {
        mount_mgr.apply_sdcard_redirect(&request.allowed_real_paths, &request.path_mappings)
    };

    let result = if is_success { 0 } else { -1 };
    if !is_success {
        log::warn!(
            "child mount failed pid={} pkg={} map_only={}",
            request.pid,
            request.package_name,
            request.is_mapping_mode_only
        );
    }
    log::info!(
        "companion mount {} pid={}",
        if is_success { "ok" } else { "fail" },
        request.pid
    );

    if !send_mount_result(sock, result) {
        log::warn!(
            "child send result failed pid={} pkg={}",
            request.pid,
            request.package_name
        );
    }
    unsafe { close(sock) };
    is_success
}

// 通过 socketpair 创建子进程执行挂载操作
fn run_mount_in_forked_child(request: &CompanionMountRequest) -> bool {
    log::info!(
        "mount prep pid={} uid={} pkg={} allow={} map={} map_only={}",
        request.pid,
        request.uid,
        request.package_name,
        request.allowed_real_paths.len(),
        request.path_mappings.len(),
        request.is_mapping_mode_only
    );

    let mut sockets = [0; 2];
    let ret = unsafe { socketpair(AF_UNIX, SOCK_DGRAM, 0, sockets.as_mut_ptr()) };
    if ret != 0 {
        let errno = last_errno();
        log::error!(
            "socketpair failed pid={} pkg={} errno={} {}",
            request.pid,
            request.package_name,
            errno,
            errno_text(errno)
        );
        return false;
    }

    let child = unsafe { libc::fork() };
    if child < 0 {
        let errno = last_errno();
        log::error!(
            "fork failed pid={} pkg={} errno={} {}",
            request.pid,
            request.package_name,
            errno,
            errno_text(errno)
        );
        unsafe {
            close(sockets[0]);
            close(sockets[1]);
        }
        return false;
    }

    if child > 0 {
        log::debug!("parent wait child={}", child);
        unsafe { close(sockets[1]) };
        return handle_parent_process(child, sockets[0]);
    }

    log::debug!(
        "child start pid={} pkg={}",
        request.pid,
        request.package_name
    );
    unsafe { close(sockets[0]) };
    let sock = sockets[1];
    let is_success = handle_child_process(request, sock);
    unsafe { libc::_exit(if is_success { 0 } else { 1 }) };
}
