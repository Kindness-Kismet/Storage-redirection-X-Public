use libc::{c_char, c_int};
use log::{Level as LogLevel, LevelFilter, Log, Metadata, Record};
use std::ffi::CString;
use std::sync::Once;

const LOG_LEVEL_VERBOSE: i32 = 0;
const LOG_LEVEL_DEBUG: i32 = 1;
const LOG_LEVEL_INFO: i32 = 2;
const LOG_LEVEL_WARN: i32 = 3;
const LOG_LEVEL_ERROR: i32 = 4;

const CURRENT_LOG_LEVEL: i32 = LOG_LEVEL_DEBUG;
const DEFAULT_LOG_TAG: &str = "StorageRedirect";
const FILE_MONITOR_LOG_TAG: &str = "FileMonitorOp";
const STATS_LOG_TAG: &str = "Stats";

const ANDROID_LOG_VERBOSE: i32 = 2;
const ANDROID_LOG_DEBUG: i32 = 3;
const ANDROID_LOG_INFO: i32 = 4;
const ANDROID_LOG_WARN: i32 = 5;
const ANDROID_LOG_ERROR: i32 = 6;

#[repr(i32)]
#[derive(Copy, Clone, Eq, PartialEq)]
pub enum Level {
    Verbose = LOG_LEVEL_VERBOSE,
    Debug = LOG_LEVEL_DEBUG,
    Info = LOG_LEVEL_INFO,
    Warn = LOG_LEVEL_WARN,
    Error = LOG_LEVEL_ERROR,
}

static LOG_INIT: Once = Once::new();
static LOG_ADAPTER: LogAdapter = LogAdapter;

struct LogAdapter;

pub struct Logger;

impl Logger {
    pub fn init(_package_name: Option<&str>) {
        ensure_log_adapter();
    }
}

impl Log for LogAdapter {
    fn enabled(&self, metadata: &Metadata) -> bool {
        is_level_enabled(map_log_level(metadata.level()))
    }

    fn log(&self, record: &Record) {
        if !self.enabled(record.metadata()) {
            return;
        }
        let level = map_log_level(record.level());
        let tag = resolve_record_tag(record.target());
        let raw_message = record.args().to_string();
        let redacted_message = redact_sensitive_paths(&raw_message);
        let message = format_record_message(level, tag, &redacted_message);
        write_log(level, tag, &message);
    }

    fn flush(&self) {}
}

// 默认通道补 Rs 前缀，其余通道保持原样
fn format_record_message(level: Level, tag: &str, message: &str) -> String {
    if message.is_empty() {
        return String::new();
    }
    if tag == DEFAULT_LOG_TAG {
        return format!("[Rs{}] {}", level_to_text(level), message);
    }
    message.to_string()
}

// 日志只保留路径归属与扩展名，避免泄露用户文件名
fn redact_sensitive_paths(message: &str) -> String {
    let mut output = String::with_capacity(message.len());
    let mut cursor = 0;
    for (start, end) in find_android_path_ranges(message) {
        output.push_str(&message[cursor..start]);
        output.push_str(&redact_android_path(&message[start..end]));
        cursor = end;
    }
    if cursor == 0 {
        return message.to_string();
    }
    output.push_str(&message[cursor..]);
    output
}

fn find_android_path_ranges(message: &str) -> Vec<(usize, usize)> {
    let mut ranges = Vec::new();
    let mut offset = 0;
    while let Some(relative_start) = message[offset..].find('/') {
        let start = offset + relative_start;
        let Some(prefix_len) = android_path_prefix_len(&message[start..]) else {
            offset = start + 1;
            continue;
        };

        let end = find_path_end(message, start + prefix_len);
        ranges.push((start, end));
        offset = end;
    }
    ranges
}

fn android_path_prefix_len(text: &str) -> Option<usize> {
    [
        "/storage/emulated/",
        "/storage/self/",
        "/sdcard/",
        "/data/media/",
        "/mnt/user/",
        "/mnt/media_rw/",
    ]
    .iter()
    .find_map(|prefix| text.starts_with(prefix).then_some(prefix.len()))
}

fn find_path_end(message: &str, start: usize) -> usize {
    let rest = &message[start..];
    let hard_end = rest.find(['|', ',', ';', ')', ']', '}']);
    let relative_end = hard_end
        .or_else(|| rest.find(|ch: char| ch.is_whitespace()))
        .unwrap_or(rest.len());
    let mut end = start + relative_end;
    while end > start && matches!(message.as_bytes()[end - 1], b':' | b'.') {
        end -= 1;
    }
    end
}

fn redact_android_path(path: &str) -> String {
    if path.is_empty() {
        return String::new();
    }

    let mut trimmed_end = path.len();
    while trimmed_end > 0 && matches!(path.as_bytes()[trimmed_end - 1], b'\'' | b'\"') {
        trimmed_end -= 1;
    }
    let core = &path[..trimmed_end];
    let suffix = &path[trimmed_end..];
    if core.is_empty() || core.ends_with('/') {
        return path.to_string();
    }

    let Some((parent, leaf)) = core.rsplit_once('/') else {
        return path.to_string();
    };
    if leaf.is_empty() {
        return path.to_string();
    }

    format!("{}/{}{}", parent, redact_leaf_name(leaf), suffix)
}

fn redact_leaf_name(leaf: &str) -> String {
    let extension = leaf
        .rsplit_once('.')
        .and_then(|(_, ext)| is_safe_extension(ext).then_some(ext));
    match extension {
        Some(ext) => format!("<redacted>.{}", ext),
        None => "<redacted>".to_string(),
    }
}

fn is_safe_extension(extension: &str) -> bool {
    let len = extension.len();
    (1..=8).contains(&len) && extension.chars().all(|ch| ch.is_ascii_alphanumeric())
}

fn ensure_log_adapter() {
    LOG_INIT.call_once(|| {
        let _ = log::set_logger(&LOG_ADAPTER);
        log::set_max_level(current_level_filter());
    });
}

pub fn is_level_enabled(level: Level) -> bool {
    (level as i32) >= CURRENT_LOG_LEVEL
}

pub fn write_log(level: Level, tag: &str, message: &str) {
    if tag.is_empty() || message.is_empty() {
        return;
    }

    let priority = level_to_priority(level);
    android_log(priority, tag, message);
}

fn map_log_level(level: LogLevel) -> Level {
    match level {
        LogLevel::Error => Level::Error,
        LogLevel::Warn => Level::Warn,
        LogLevel::Info => Level::Info,
        LogLevel::Debug => Level::Debug,
        LogLevel::Trace => Level::Verbose,
    }
}

fn current_level_filter() -> LevelFilter {
    match CURRENT_LOG_LEVEL {
        LOG_LEVEL_VERBOSE => LevelFilter::Trace,
        LOG_LEVEL_DEBUG => LevelFilter::Debug,
        LOG_LEVEL_INFO => LevelFilter::Info,
        LOG_LEVEL_WARN => LevelFilter::Warn,
        LOG_LEVEL_ERROR => LevelFilter::Error,
        _ => LevelFilter::Info,
    }
}

// 默认写入运行日志通道，文件监控与统计单独分流
fn resolve_record_tag(target: &str) -> &str {
    if target == FILE_MONITOR_LOG_TAG {
        return FILE_MONITOR_LOG_TAG;
    }
    if target == STATS_LOG_TAG {
        return STATS_LOG_TAG;
    }
    DEFAULT_LOG_TAG
}

fn level_to_priority(level: Level) -> i32 {
    match level {
        Level::Verbose => ANDROID_LOG_VERBOSE,
        Level::Debug => ANDROID_LOG_DEBUG,
        Level::Info => ANDROID_LOG_INFO,
        Level::Warn => ANDROID_LOG_WARN,
        Level::Error => ANDROID_LOG_ERROR,
    }
}

fn level_to_text(level: Level) -> &'static str {
    match level {
        Level::Verbose => "Verbose",
        Level::Debug => "Debug",
        Level::Info => "Info",
        Level::Warn => "Warn",
        Level::Error => "Error",
    }
}

fn android_log(priority: i32, tag: &str, message: &str) {
    let Ok(tag_c) = CString::new(tag) else {
        return;
    };
    let Ok(msg_c) = CString::new(message) else {
        return;
    };
    unsafe {
        __android_log_print(priority, tag_c.as_ptr(), c"%s".as_ptr(), msg_c.as_ptr());
    }
}

unsafe extern "C" {
    fn __android_log_print(prio: c_int, tag: *const c_char, fmt: *const c_char, ...) -> c_int;
}
