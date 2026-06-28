use crate::platform::unique_fd::UniqueFd;
use libc::{O_CLOEXEC, O_RDONLY, c_void, open, read};

const PROC_TEXT_READ_LIMIT: usize = 8192;

pub(super) fn log_child_diagnostics(child: i32, phase: &str) {
    let wchan = read_proc_text(&format!("/proc/{}/wchan", child))
        .unwrap_or_else(|| "<unavailable>".to_string());
    let status_summary = read_proc_status_summary(&format!("/proc/{}/status", child))
        .unwrap_or_else(|| "<unavailable>".to_string());
    let stack = read_proc_text(&format!("/proc/{}/stack", child))
        .unwrap_or_else(|| "<unavailable>".to_string());

    log::warn!(
        "child stuck child={} phase={} wchan={} status={}",
        child,
        phase,
        wchan.trim(),
        status_summary
    );
    let stack_trimmed = stack.trim();
    if !stack_trimmed.is_empty() && stack_trimmed != "<unavailable>" {
        log::warn!(
            "child stuck child={} phase={} stack:\n{}",
            child,
            phase,
            stack_trimmed
        );
    }
}

fn read_proc_text(path: &str) -> Option<String> {
    let Ok(c_path) = std::ffi::CString::new(path) else {
        return None;
    };
    let fd = unsafe { open(c_path.as_ptr(), O_RDONLY | O_CLOEXEC) };
    if fd < 0 {
        return None;
    }
    let file = UniqueFd::new(fd);
    let mut text = String::new();
    let mut buf = [0u8; 1024];
    loop {
        let n = unsafe { read(file.get(), buf.as_mut_ptr() as *mut c_void, buf.len()) };
        if n <= 0 {
            break;
        }
        let Ok(s) = std::str::from_utf8(&buf[..n as usize]) else {
            break;
        };
        text.push_str(s);
        if text.len() >= PROC_TEXT_READ_LIMIT {
            break;
        }
    }
    Some(text)
}

fn read_proc_status_summary(path: &str) -> Option<String> {
    let raw = read_proc_text(path)?;
    let mut name = String::from("?");
    let mut state = String::from("?");
    for line in raw.lines() {
        if let Some(rest) = line.strip_prefix("Name:") {
            name = rest.trim().to_string();
        } else if let Some(rest) = line.strip_prefix("State:") {
            state = rest.trim().to_string();
        }
    }
    Some(format!("name={} state={}", name, state))
}
