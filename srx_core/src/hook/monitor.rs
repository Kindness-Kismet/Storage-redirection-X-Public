use super::stats::InterceptHub;
use crate::monitor::{AuditTrail, OpKind};
use libc::{O_APPEND, O_CREAT, O_RDWR, O_TMPFILE, O_TRUNC, O_WRONLY};

const SHOULD_MONITOR_LOG_DIR_CREATE: bool = true;

pub fn has_write_intent_flags(flags: i32) -> bool {
    if flags < 0 {
        return false;
    }

    let write_mask = O_WRONLY | O_RDWR | O_CREAT | O_TRUNC | O_APPEND;
    (flags & write_mask) != 0 || (flags & O_TMPFILE) == O_TMPFILE
}

fn has_create_intent_flags(flags: i32) -> bool {
    if flags < 0 {
        return false;
    }

    (flags & O_CREAT) != 0 || (flags & O_TMPFILE) == O_TMPFILE
}

// 仅记录带创建意图的 open
pub fn record_open_result(
    hub: &InterceptHub,
    op_name: &str,
    flags: i32,
    pathname: &str,
    result: i32,
    error_no: i32,
) {
    if !hub.is_monitor_enabled() {
        return;
    }

    if !has_create_intent_flags(flags) {
        return;
    }

    let extra = format!("op={}|flags=0x{:x}", op_name, flags);
    let caller_package = hub.get_current_caller_package();
    AuditTrail::instance().record_operation_result(
        OpKind::Create,
        &caller_package,
        pathname,
        result,
        error_no,
        &extra,
    );
}

pub fn record_mkdir_result(
    hub: &InterceptHub,
    op_name: &str,
    pathname: &str,
    result: i32,
    error_no: i32,
) {
    if !hub.is_monitor_enabled() {
        return;
    }

    if !SHOULD_MONITOR_LOG_DIR_CREATE {
        return;
    }
    let extra = format!("op={}", op_name);
    let caller_package = hub.get_current_caller_package();
    AuditTrail::instance().record_operation_result(
        OpKind::Create,
        &caller_package,
        pathname,
        result,
        error_no,
        &extra,
    );
}
