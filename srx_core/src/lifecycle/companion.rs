use super::companion_mount::execute_companion_mount_request;
use super::companion_request::{CompanionMountRequest, parse_companion_mount_request};
use crate::logging::Logger;
use crate::platform::fs;
use libc::{SIG_IGN, SIGPIPE, c_int, signal};

const MAX_PAYLOAD_BYTES: u32 = 1024 * 1024;

// 伴生进程主流程：读取请求、解析、执行挂载、返回状态
pub fn run_companion_pipeline(client_fd: c_int) {
    Logger::init(Some("zygisk_companion"));

    unsafe {
        signal(SIGPIPE, SIG_IGN);
    }

    let payload_len = match read_payload_length(client_fd) {
        Some(len) => len,
        None => {
            log::error!("payload length read failed");
            return;
        }
    };

    if payload_len == 0 || payload_len > MAX_PAYLOAD_BYTES {
        log::error!("payload length invalid len={}", payload_len);
        write_status(client_fd, 0);
        return;
    }
    log::debug!("payload len={}", payload_len);

    let payload = match read_payload_data(client_fd, payload_len) {
        Some(data) => data,
        None => {
            log::error!("payload read failed");
            write_status(client_fd, 0);
            return;
        }
    };

    let request = match parse_companion_mount_request(&payload) {
        Ok(req) => req,
        Err(err) => {
            log::error!("request parse failed err={}", err);
            write_status(client_fd, 0);
            return;
        }
    };

    if !request.package_name.is_empty() {
        Logger::init(Some(&request.package_name));
    }

    log_mount_request(&request);

    let status = if execute_companion_mount_request(&request) {
        1
    } else {
        0
    };
    write_status(client_fd, status);
}

fn read_payload_length(client_fd: c_int) -> Option<u32> {
    let mut buffer = [0u8; 4];
    if !fs::read_all(client_fd, &mut buffer) {
        log::warn!("read length failed fd={}", client_fd);
        return None;
    }
    Some(u32::from_ne_bytes(buffer))
}

// 按长度读取负载并强制 UTF-8 解码
fn read_payload_data(client_fd: c_int, payload_len: u32) -> Option<String> {
    let mut buffer = vec![0u8; payload_len as usize];
    if !fs::read_all(client_fd, &mut buffer) {
        log::warn!("read payload failed fd={} len={}", client_fd, payload_len);
        return None;
    }
    match String::from_utf8(buffer) {
        Ok(text) => Some(text),
        Err(_) => {
            log::warn!("payload not utf8 fd={} len={}", client_fd, payload_len);
            None
        }
    }
}

fn write_status(client_fd: c_int, status: i32) {
    let _ = fs::write_all(client_fd, &status.to_ne_bytes());
}

fn log_mount_request(request: &CompanionMountRequest) {
    log::info!(
        "req pid={} pkg={} allow={} map={} map_only={}",
        request.pid,
        request.package_name,
        request.allowed_real_paths.len(),
        request.path_mappings.len(),
        request.is_mapping_mode_only
    );

    if !request.allowed_real_paths.is_empty() {
        for path in &request.allowed_real_paths {
            log::info!("req allow={}", path);
        }
    }
    if !request.path_mappings.is_empty() {
        for mapping in &request.path_mappings {
            log::info!("req map {} -> {}", mapping.request_path, mapping.final_path);
        }
    }
}
