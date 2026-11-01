// 日志守护进程：经 socket 收包，按 tag 分流到滚动日志与统计文件
#[path = "../../monitor/query.rs"]
mod monitor_query;
mod rolling;
mod transport;

use libc::{c_void, recv};
use rolling::RollingLog;
use std::fs;
use std::io::{self, Write};
use std::mem;
use std::path::Path;
use transport::{ClientSocket, bind_log_socket};

const LOGS_DIR: &str = "/data/adb/modules/storage.redirect.x/logs";
const RUNNING_LOG: &str = "/data/adb/modules/storage.redirect.x/logs/running.log";
const STATS_FILE: &str = "/data/adb/modules/storage.redirect.x/stats";
const MAX_RUNNING_LINES: usize = 30_000;
const RECV_BUFFER_SIZE: usize = 8192;

const TAG_STATS: &str = "Stats";
const TAG_CONTROL: &str = "Control";
const CONTROL_CLEAR_ALL: &str = "clear-all";
const CONTROL_FLUSH_ALL: &str = "flush-all";

fn main() {
    let command = std::env::args().nth(1);
    let arg = std::env::args().nth(2);
    let result = match command.as_deref() {
        None => run_daemon(),
        Some("monitor-query") => {
            let args: Vec<String> = std::env::args().collect();
            let before = args
                .get(2)
                .and_then(|s| s.parse::<i64>().ok())
                .unwrap_or(0)
                .max(0);
            let after = args
                .get(3)
                .and_then(|s| s.parse::<i64>().ok())
                .unwrap_or(0)
                .max(0);
            let limit = args
                .get(4)
                .and_then(|s| s.parse::<usize>().ok())
                .unwrap_or(500)
                .clamp(1, 500);
            monitor_query::run(
                Path::new("/data/adb/modules/storage.redirect.x"),
                before,
                after,
                limit,
            )
        }
        Some("control") => run_control(arg),
        Some(_) => Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "usage: srx_logd [monitor-query | control <command>]",
        )),
    };
    if let Err(error) = result {
        let _ = writeln!(io::stderr(), "srx_logd: {error}");
        std::process::exit(1);
    }
}

fn run_daemon() -> io::Result<()> {
    fs::create_dir_all(LOGS_DIR)?;
    let fd = bind_log_socket()?;
    let mut state = LogState::new()?;
    let mut buffer = [0u8; RECV_BUFFER_SIZE];

    loop {
        let size = unsafe { recv(fd, buffer.as_mut_ptr() as *mut c_void, buffer.len(), 0) };
        if size <= 0 {
            continue;
        }
        let Ok(text) = std::str::from_utf8(&buffer[..size as usize]) else {
            continue;
        };
        state.handle(text);
    }
}

fn run_monitor_command(command: &str) -> io::Result<()> {
    let status = std::process::Command::new("/system/bin/app_process")
        .env(
            "CLASSPATH",
            "/data/adb/modules/storage.redirect.x/monitor.dex",
        )
        .args([
            "/system/bin",
            "org.srx.monitor.MonitorServer",
            "/data/adb/modules/storage.redirect.x",
            command,
        ])
        .status()?;
    if status.success() {
        Ok(())
    } else {
        Err(io::Error::other("monitor control failed"))
    }
}

fn run_control(command: Option<String>) -> io::Result<()> {
    let Some(command) = command.filter(|value| !value.is_empty()) else {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "missing control command",
        ));
    };
    if command == "monitor-policy" {
        return run_monitor_command("policy");
    }
    if command == "clear-monitor" || command == CONTROL_CLEAR_ALL {
        run_monitor_command("clear")?;
    }
    let Some(socket) = ClientSocket::new() else {
        return Err(io::Error::last_os_error());
    };
    socket.send('I', TAG_CONTROL, &command)
}

struct LogState {
    running: RollingLog,
    stats_total: i64,
}

impl LogState {
    fn new() -> io::Result<Self> {
        Ok(Self {
            running: RollingLog::open(RUNNING_LOG, MAX_RUNNING_LINES)?,
            stats_total: read_stats_total(),
        })
    }

    fn handle(&mut self, packet: &str) {
        let Some((level, tag, message)) = parse_packet(packet) else {
            return;
        };

        match tag {
            TAG_STATS => self.handle_stats(message),
            TAG_CONTROL => self.handle_control(message),
            _ => {
                let line = format_running_line(level, tag, message);
                self.running.append(&line);
            }
        }
    }

    fn handle_stats(&mut self, message: &str) {
        let Some(value) = message.strip_prefix('+') else {
            return;
        };
        let Ok(delta) = value.trim().parse::<i64>() else {
            return;
        };
        if delta <= 0 {
            return;
        }

        self.stats_total = self.stats_total.saturating_add(delta);
        self.flush_stats();
    }

    fn handle_control(&mut self, message: &str) {
        match message {
            CONTROL_CLEAR_ALL => self.clear_all(),
            CONTROL_FLUSH_ALL => self.flush_all(),
            _ => {}
        }
    }

    fn clear_all(&mut self) {
        self.running.clear();
    }

    fn flush_all(&mut self) {
        self.running.flush();
    }

    fn flush_stats(&mut self) {
        let _ = fs::write(STATS_FILE, format!("{}\n", self.stats_total));
    }
}

impl Drop for LogState {
    fn drop(&mut self) {
        self.running.flush();
    }
}

fn parse_packet(packet: &str) -> Option<(&str, &str, &str)> {
    let mut parts = packet.splitn(3, '\t');
    let level = parts.next()?;
    let tag = parts.next()?;
    let message = parts.next()?;
    if level.is_empty() || tag.is_empty() || message.is_empty() {
        return None;
    }
    Some((level, tag, message))
}

fn format_running_line(level: &str, tag: &str, message: &str) -> String {
    let now = timestamp_text();
    if message.starts_with("[Rs") || message.starts_with("[Kt") {
        return format!("{now} {message}");
    }

    let source = if tag == "SRX" { "Jv" } else { "Rs" };
    format!("{now} [{source}{}] {message}", level_text(level))
}

fn level_text(level: &str) -> &'static str {
    match level.as_bytes().first().copied() {
        Some(b'V') => "Verbose",
        Some(b'D') => "Debug",
        Some(b'I') => "Info",
        Some(b'W') => "Warn",
        Some(b'E') => "Error",
        _ => "Info",
    }
}

fn timestamp_text() -> String {
    let mut now: libc::time_t = 0;
    unsafe {
        libc::time(&mut now as *mut libc::time_t);
    }
    let mut tm_value: libc::tm = unsafe { mem::zeroed() };
    let tm_ptr = unsafe { libc::localtime_r(&now as *const libc::time_t, &mut tm_value) };
    if tm_ptr.is_null() {
        return "00/00 00:00:00".to_string();
    }

    let mut buffer = [0u8; 32];
    let fmt = b"%m/%d %H:%M:%S\0";
    let written = unsafe {
        libc::strftime(
            buffer.as_mut_ptr() as *mut _,
            buffer.len(),
            fmt.as_ptr() as *const _,
            &tm_value as *const _,
        )
    };
    if written == 0 {
        return "00/00 00:00:00".to_string();
    }
    String::from_utf8_lossy(&buffer[..written]).to_string()
}

fn read_stats_total() -> i64 {
    fs::read_to_string(STATS_FILE)
        .ok()
        .and_then(|text| text.trim().parse::<i64>().ok())
        .unwrap_or(0)
}
