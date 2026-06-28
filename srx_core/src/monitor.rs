mod stack_owner;
mod thread_hint;

pub(crate) use stack_owner::infer_caller_package_by_stack;

use crate::platform::paths;
use crate::platform::paths::monotonic_ms;
use crate::redirect::policy;
use libc::{time, tm};
use once_cell::sync::Lazy;
use stack_owner::infer_package_from_java_stack;
use std::collections::{HashMap, VecDeque};
use std::sync::{
    Mutex,
    atomic::{AtomicBool, Ordering},
};
use thread_hint::infer_component_from_thread_name;

const LOGCAT_OP_TAG: &str = "FileMonitorOp";
const DUPLICATE_EVENT_WINDOW_MS: i64 = 1500;
const RECENT_CALLER_PACKAGE_WINDOW_MS: i64 = 1500;
const MAX_RECENT_EVENTS: usize = 512;

#[derive(Copy, Clone)]
pub enum OpKind {
    Create,
}

struct AuditState {
    package_name: String,
    uid: i32,
    shared_uid_packages: String,
    caller_package: String,
    caller_package_updated_ms: i64,
    recent_event_ms: HashMap<String, i64>,
    recent_event_order: VecDeque<String>,
}

impl AuditState {
    fn new() -> Self {
        Self {
            package_name: String::new(),
            uid: -1,
            shared_uid_packages: String::new(),
            caller_package: String::new(),
            caller_package_updated_ms: -1,
            recent_event_ms: HashMap::new(),
            recent_event_order: VecDeque::new(),
        }
    }
}

pub struct AuditTrail {
    is_enabled: AtomicBool,
    state: Mutex<AuditState>,
}

impl AuditTrail {
    pub fn instance() -> &'static AuditTrail {
        &AUDIT_TRAIL
    }

    pub fn init(&self, package_name: &str, uid: i32) -> bool {
        let mut state = self.state.lock().unwrap_or_else(|err| err.into_inner());
        state.package_name = package_name.to_string();
        state.uid = uid;
        state.caller_package.clear();
        state.caller_package_updated_ms = -1;
        state.recent_event_ms.clear();
        state.recent_event_order.clear();

        if uid >= 0 && policy::is_shared_uid_process(uid) {
            state.shared_uid_packages = policy::get_shared_uid_packages_string(uid);
        } else {
            state.shared_uid_packages.clear();
        }

        if !state.shared_uid_packages.is_empty() {
            log::info!(
                "monitor init pkg={} shared_uid={}",
                state.package_name,
                state.shared_uid_packages
            );
        } else {
            log::info!("monitor init pkg={}", state.package_name);
        }
        true
    }

    pub fn update_caller_package(&self, caller_package: &str) {
        let normalized = extract_caller_package(caller_package);
        let mut state = self.state.lock().unwrap_or_else(|err| err.into_inner());
        state.caller_package = normalized;
        state.caller_package_updated_ms = monotonic_ms();
    }

    pub fn get_log_fd(&self) -> i32 {
        -1
    }

    pub fn is_enabled(&self) -> bool {
        self.is_enabled.load(Ordering::Relaxed)
    }

    pub fn set_enabled(&self, is_enabled: bool) {
        self.is_enabled.store(is_enabled, Ordering::Relaxed);
    }

    pub fn record_operation_result(
        &self,
        kind: OpKind,
        caller_package: &str,
        path: &str,
        result: i32,
        error_no: i32,
        extra: &str,
    ) {
        if !self.is_enabled() {
            return;
        }

        // EEXIST 属于幂等检查，不记录
        if result == -1 && error_no == libc::EEXIST {
            return;
        }

        let mut state = self.state.lock().unwrap_or_else(|err| err.into_inner());
        let normalized = normalize_storage_path_locked(path);
        if normalized.is_empty() {
            return;
        }
        // MediaProvider 合成目录跳过审计
        if is_filtered_media_provider_path(&normalized) {
            return;
        }
        // Android/data 及其子路径为应用私有沙箱，不纳入监视
        if is_android_data_path(&normalized) {
            return;
        }

        let source_identity = resolve_caller_info_locked(&state, caller_package);
        let event_key = format!(
            "{}|{}|{}|{}|{}|{}",
            state.package_name,
            source_identity.package_name,
            op_type_to_text(kind),
            normalized,
            result,
            error_no
        );
        if should_skip_duplicate_event_locked(&mut state, &event_key) {
            return;
        }

        let mut line = format!(
            "{}|{}|{}|{}|{}|ret={}|errno={}",
            build_timestamp_locked(),
            state.package_name,
            if source_identity.package_name.is_empty() {
                "-"
            } else {
                source_identity.package_name.as_str()
            },
            op_type_to_text(kind),
            normalized,
            result,
            error_no
        );
        append_source_identity_meta(&mut line, &source_identity);
        if !extra.is_empty() {
            line.push('|');
            line.push_str(extra);
        }
        append_line_locked(&line);
    }
}

static AUDIT_TRAIL: Lazy<AuditTrail> = Lazy::new(|| AuditTrail {
    is_enabled: AtomicBool::new(true),
    state: Mutex::new(AuditState::new()),
});

// 将 /data/media 别名统一转为 /storage/emulated 前缀
fn normalize_storage_path_locked(path: &str) -> String {
    if path.is_empty() {
        return String::new();
    }

    let normalized = paths::normalize(path);
    if paths::starts_with(&normalized, "/storage/emulated/") {
        return normalized;
    }

    if paths::starts_with(&normalized, "/data/media/") {
        return format!("/storage/emulated/{}", &normalized[12..]);
    }

    String::new()
}

fn is_filtered_media_provider_path(path: &str) -> bool {
    path.contains("/.transforms/")
        || path.ends_with("/.transforms")
        || path.contains("/.picker_transcoded/")
        || path.ends_with("/.picker_transcoded")
}

fn is_android_data_path(path: &str) -> bool {
    path.contains("/Android/data/") || path.ends_with("/Android/data")
}

// 本地时间 YYYY-MM-DD HH:MM:SS
fn build_timestamp_locked() -> String {
    let mut now: libc::time_t = 0;
    unsafe { time(&mut now as *mut _) };

    let mut tm_value: tm = unsafe { std::mem::zeroed() };
    let tm_ptr = unsafe { libc::localtime_r(&now as *const _, &mut tm_value as *mut _) };
    if tm_ptr.is_null() {
        return String::new();
    }

    let mut buffer = [0u8; 32];
    let format = b"%Y-%m-%d %H:%M:%S\0";
    let written = unsafe {
        libc::strftime(
            buffer.as_mut_ptr() as *mut _,
            buffer.len(),
            format.as_ptr() as *const _,
            &tm_value as *const _,
        )
    };
    if written == 0 {
        return String::new();
    }
    String::from_utf8_lossy(&buffer[..written]).to_string()
}

fn append_line_locked(line: &str) {
    if line.is_empty() {
        return;
    }
    log::info!(target: LOGCAT_OP_TAG, "{}", line);
}

fn append_source_identity_meta(line: &mut String, identity: &SourceIdentity) {
    line.push('|');
    line.push_str("identify_method=");
    line.push_str(identity.source);
    line.push('|');
    line.push_str("identify_reliability=");
    line.push_str(identity.confidence);
}

pub(super) struct SourceIdentity {
    package_name: String,
    source: &'static str,
    confidence: &'static str,
}

impl SourceIdentity {
    pub(super) fn new(
        package_name: String,
        source: &'static str,
        confidence: &'static str,
    ) -> Self {
        Self {
            package_name,
            source,
            confidence,
        }
    }

    fn unknown() -> Self {
        Self::new(String::new(), "unknown", "none")
    }
}

// 结合直接信号、shared_uid 线索与近期调用方回退推断调用方包名
fn resolve_caller_info_locked(state: &AuditState, caller_package: &str) -> SourceIdentity {
    let resolved_caller = extract_caller_package(caller_package);
    let is_intermediate_caller = is_intermediate_caller_package(&resolved_caller);

    if !resolved_caller.is_empty() && !is_intermediate_caller {
        return SourceIdentity::new(resolved_caller, "caller", "high");
    }

    // 线程名识别：shared_uid 进程内区分 MTP
    if !state.shared_uid_packages.is_empty()
        && policy::is_shared_uid_process(state.uid)
        && let Some(resolution) = infer_component_from_thread_name()
    {
        return resolution;
    }

    // Java 栈帧回溯：shared_uid 进程内按调用栈区分具体包名
    if !state.shared_uid_packages.is_empty()
        && policy::is_shared_uid_process(state.uid)
        && let Some(resolution) = infer_package_from_java_stack(&state.shared_uid_packages)
    {
        return resolution;
    }

    if resolved_caller.is_empty()
        && policy::is_media_provider_package(&state.package_name)
        && !state.caller_package.is_empty()
        && state.caller_package_updated_ms >= 0
    {
        let caller_age_ms = monotonic_ms() - state.caller_package_updated_ms;
        if (0..=RECENT_CALLER_PACKAGE_WINDOW_MS).contains(&caller_age_ms)
            && !is_intermediate_caller_package(&state.caller_package)
        {
            return SourceIdentity::new(state.caller_package.clone(), "recent_caller", "medium");
        }
    }

    if !resolved_caller.is_empty() {
        if is_intermediate_caller {
            if !state.shared_uid_packages.is_empty() {
                return SourceIdentity::new(
                    state.shared_uid_packages.clone(),
                    "shared_uid",
                    "fallback",
                );
            }
            return SourceIdentity::unknown();
        }
        return SourceIdentity::new(resolved_caller, "caller", "high");
    }

    if !state.shared_uid_packages.is_empty() {
        return SourceIdentity::new(state.shared_uid_packages.clone(), "shared_uid", "fallback");
    }

    SourceIdentity::unknown()
}

fn extract_caller_package(caller_package: &str) -> String {
    if caller_package.is_empty() || caller_package == "-" {
        return String::new();
    }

    if let Some(value) = extract_kv_value(caller_package, "caller=") {
        let normalized = normalize_package_text(&value);
        if !normalized.is_empty() {
            return normalized;
        }
    }

    if caller_package.contains('=') || caller_package.contains('|') {
        return String::new();
    }

    normalize_package_text(caller_package)
}

fn is_intermediate_caller_package(package_name: &str) -> bool {
    policy::is_media_intermediate_package(package_name)
}

// 从 key=value|... 格式中提取指定 key 的值
fn extract_kv_value(source: &str, key: &str) -> Option<String> {
    let begin = source.find(key)? + key.len();
    if begin >= source.len() {
        return None;
    }
    let end = source[begin..]
        .find('|')
        .map(|idx| begin + idx)
        .unwrap_or(source.len());
    if end <= begin {
        return None;
    }
    Some(source[begin..end].to_string())
}

fn normalize_package_text(source: &str) -> String {
    let value = source.trim();
    if value.is_empty() {
        return String::new();
    }

    if !value
        .chars()
        .all(|ch| ch.is_ascii_alphanumeric() || ch == '.' || ch == '_' || ch == '-')
    {
        return String::new();
    }

    if !value.contains('.') {
        return String::new();
    }

    value.to_string()
}

// 时间窗口内抑制相同 event_key 重复事件
fn should_skip_duplicate_event_locked(state: &mut AuditState, event_key: &str) -> bool {
    if event_key.is_empty() {
        return false;
    }

    let now_ms = paths::monotonic_ms();
    if let Some(entry) = state.recent_event_ms.get_mut(event_key) {
        if now_ms - *entry < DUPLICATE_EVENT_WINDOW_MS {
            *entry = now_ms;
            return true;
        }
        *entry = now_ms;
        return false;
    }

    state.recent_event_ms.insert(event_key.to_string(), now_ms);
    state.recent_event_order.push_back(event_key.to_string());
    while state.recent_event_order.len() > MAX_RECENT_EVENTS {
        if let Some(oldest) = state.recent_event_order.pop_front() {
            state.recent_event_ms.remove(&oldest);
        }
    }
    false
}

fn op_type_to_text(kind: OpKind) -> &'static str {
    match kind {
        OpKind::Create => "CREATE",
    }
}
