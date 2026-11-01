// Procfs 虚拟化：抹除 SRX 自身创建的子挂载、maps 条目并还原 FUSE 视图
use crate::platform::paths;
use libc::{c_int, c_void};
use std::ffi::CString;

#[derive(Copy, Clone, PartialEq, Eq)]
pub enum ProcKind {
    Mountinfo,
    Mounts,
    Maps,
}

// 检查路径是否命中需要虚拟化净化的 procfs 目标
pub fn check_proc_sensitive_path(path: &str) -> Option<ProcKind> {
    if !paths::starts_with(path, "/proc/") {
        return None;
    }
    if path == "/proc/self/mountinfo" {
        return Some(ProcKind::Mountinfo);
    }
    if path == "/proc/self/mounts" || path == "/proc/mounts" {
        return Some(ProcKind::Mounts);
    }
    if path == "/proc/self/maps" {
        return Some(ProcKind::Maps);
    }
    let rest = &path["/proc/".len()..];
    if let Some(idx) = rest.find('/') {
        let pid_str = &rest[..idx];
        if pid_str.bytes().all(|b| b.is_ascii_digit()) {
            let sub = &rest[idx + 1..];
            if sub == "mountinfo" {
                return Some(ProcKind::Mountinfo);
            }
            if sub == "mounts" {
                return Some(ProcKind::Mounts);
            }
            if sub == "maps" {
                return Some(ProcKind::Maps);
            }
        }
    }
    None
}

// 净化 /proc/self/mountinfo：剔除 SRX 子挂载并将主存储还原为标准 FUSE
pub fn sanitize_mountinfo(content: &str) -> String {
    let mut output = String::with_capacity(content.len());
    for line in content.lines() {
        if line.contains(".srx_") || line.contains("/data/adb/") {
            continue;
        }

        let parts: Vec<&str> = line.split_whitespace().collect();
        if parts.len() < 10 {
            output.push_str(line);
            output.push('\n');
            continue;
        }

        let mount_point = parts[4];
        if paths::starts_with(mount_point, "/storage/emulated/") {
            let relative = &mount_point["/storage/emulated/".len()..];
            // 剔除 /storage/emulated/<user_id>/ 结构下的子挂载（如 /Android 等）
            if relative.contains('/') {
                continue;
            }

            // 重构主存储条目，还原原生 Android FUSE 属性
            let id = parts[0];
            let parent_id = parts[1];
            let dev = parts[2];
            let vfs_opts = parts[5];
            output.push_str(&format!(
                "{} {} {} / {} {} - fuse /dev/fuse rw,nosuid,nodev,noexec,noatime\n",
                id, parent_id, dev, mount_point, vfs_opts
            ));
            continue;
        }

        output.push_str(line);
        output.push('\n');
    }
    output
}

// 净化 /proc/self/mounts：抹除存储子挂载并还原 /dev/fuse
pub fn sanitize_mounts(content: &str) -> String {
    let mut output = String::with_capacity(content.len());
    for line in content.lines() {
        if line.contains(".srx_") || line.contains("/data/adb/") {
            continue;
        }

        let parts: Vec<&str> = line.split_whitespace().collect();
        if parts.len() < 2 {
            output.push_str(line);
            output.push('\n');
            continue;
        }

        let mount_point = parts[1];
        if paths::starts_with(mount_point, "/storage/emulated/") {
            let relative = &mount_point["/storage/emulated/".len()..];
            if relative.contains('/') {
                continue;
            }
            output.push_str(&format!(
                "/dev/fuse {} fuse rw,nosuid,nodev,noexec,noatime 0 0\n",
                mount_point
            ));
            continue;
        }

        output.push_str(line);
        output.push('\n');
    }
    output
}

// 净化 /proc/self/maps：剔除 SRX 模块路径与敏感映射
pub fn sanitize_maps(content: &str) -> String {
    let mut output = String::with_capacity(content.len());
    for line in content.lines() {
        if line.contains("storage.redirect.x")
            || line.contains("libsrx_core")
            || line.contains(".srx_")
            || line.contains("/data/adb/")
        {
            continue;
        }
        output.push_str(line);
        output.push('\n');
    }
    output
}

// 创建并填充纯净 procfs 虚拟句柄（匿名 memfd）
pub fn open_sanitized_proc_fd(path: &str) -> Option<c_int> {
    let kind = check_proc_sensitive_path(path)?;
    let real_path = match kind {
        ProcKind::Mountinfo => "/proc/self/mountinfo",
        ProcKind::Mounts => "/proc/self/mounts",
        ProcKind::Maps => "/proc/self/maps",
    };

    let content = read_proc_raw(real_path)?;
    let sanitized = match kind {
        ProcKind::Mountinfo => sanitize_mountinfo(&content),
        ProcKind::Mounts => sanitize_mounts(&content),
        ProcKind::Maps => sanitize_maps(&content),
    };

    let memfd = create_memfd("")?;
    let bytes = sanitized.as_bytes();
    let written = unsafe {
        libc::syscall(
            libc::SYS_write,
            memfd,
            bytes.as_ptr() as *const c_void,
            bytes.len(),
        )
    };
    if written < 0 {
        unsafe { libc::syscall(libc::SYS_close, memfd) };
        return None;
    }
    unsafe { libc::syscall(libc::SYS_lseek, memfd, 0, libc::SEEK_SET) };
    Some(memfd)
}

fn read_proc_raw(path: &str) -> Option<String> {
    let c_path = CString::new(path).ok()?;
    let fd = unsafe {
        libc::syscall(
            libc::SYS_openat,
            libc::AT_FDCWD,
            c_path.as_ptr(),
            libc::O_RDONLY | libc::O_CLOEXEC,
            0,
        ) as c_int
    };
    if fd < 0 {
        return None;
    }
    let mut buf = Vec::with_capacity(8192);
    let mut chunk = [0u8; 4096];
    loop {
        let n = unsafe {
            libc::syscall(
                libc::SYS_read,
                fd,
                chunk.as_mut_ptr() as *mut c_void,
                chunk.len(),
            )
        };
        if n > 0 {
            buf.extend_from_slice(&chunk[..n as usize]);
        } else if n == 0 {
            break;
        } else {
            unsafe { libc::syscall(libc::SYS_close, fd) };
            return None;
        }
    }
    unsafe { libc::syscall(libc::SYS_close, fd) };
    String::from_utf8(buf).ok()
}

fn create_memfd(name: &str) -> Option<c_int> {
    let c_name = CString::new(name).ok()?;
    const MFD_CLOEXEC: libc::c_uint = 0x0001;
    let fd =
        unsafe { libc::syscall(libc::SYS_memfd_create, c_name.as_ptr(), MFD_CLOEXEC) as c_int };
    if fd >= 0 { Some(fd) } else { None }
}
