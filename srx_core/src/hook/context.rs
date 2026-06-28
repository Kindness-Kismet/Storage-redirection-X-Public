use crate::platform::paths::monotonic_ms;
use std::cell::{Cell, RefCell};

thread_local! {
    static CALLER_PACKAGE: RefCell<String> = const { RefCell::new(String::new()) };
    static CALLER_UID: Cell<i32> = const { Cell::new(-1) };
    static FUSE_CALLER_UID: Cell<i32> = const { Cell::new(-1) };
    static FUSE_CALLER_UID_TS_MS: Cell<i64> = const { Cell::new(-1) };
    static FUSE_CALLER_PID: Cell<i32> = const { Cell::new(-1) };
    static BINDER_SAVED_CALLER_UID: Cell<i32> = const { Cell::new(-1) };
    static BINDER_SAVED_CALLER_UID_TS_MS: Cell<i64> = const { Cell::new(-1) };
    static BINDER_IDENTITY_CLEARED: Cell<bool> = const { Cell::new(false) };
    static REENTRY_DEPTH: Cell<u32> = const { Cell::new(0) };
}

pub struct ReentryGuard;

impl ReentryGuard {
    pub fn enter() -> Self {
        REENTRY_DEPTH.with(|depth| depth.set(depth.get() + 1));
        ReentryGuard
    }

    pub fn is_reentrant() -> bool {
        REENTRY_DEPTH.with(|depth| depth.get() > 0)
    }
}

impl Drop for ReentryGuard {
    fn drop(&mut self) {
        REENTRY_DEPTH.with(|depth| {
            let current = depth.get();
            if current > 0 {
                depth.set(current - 1);
            }
        });
    }
}

pub fn set_current_caller_package(name: &str) {
    CALLER_PACKAGE.with(|pkg| *pkg.borrow_mut() = name.to_string());
}

pub fn get_current_caller_package() -> String {
    CALLER_PACKAGE.with(|pkg| pkg.borrow().clone())
}

pub fn set_current_caller_uid(uid: i32) {
    CALLER_UID.with(|cell| cell.set(uid));
}

pub fn get_current_caller_uid() -> i32 {
    CALLER_UID.with(|cell| cell.get())
}

pub fn clear_current_caller() {
    CALLER_PACKAGE.with(|pkg| pkg.borrow_mut().clear());
    CALLER_UID.with(|cell| cell.set(-1));
}

pub fn set_fuse_caller_uid(uid: i32) {
    FUSE_CALLER_UID.with(|cell| cell.set(uid));
    FUSE_CALLER_UID_TS_MS.with(|cell| cell.set(monotonic_ms()));
}

pub fn set_fuse_caller_pid(pid: i32) {
    FUSE_CALLER_PID.with(|cell| cell.set(pid));
}

pub fn get_fuse_caller_uid() -> i32 {
    FUSE_CALLER_UID.with(|cell| cell.get())
}

pub fn get_fuse_caller_pid() -> i32 {
    FUSE_CALLER_PID.with(|cell| cell.get())
}

// 未缓存返回 -1
pub fn get_fuse_caller_uid_age_ms() -> i64 {
    let now = monotonic_ms();
    FUSE_CALLER_UID_TS_MS.with(|cell| {
        let ts = cell.get();
        if ts < 0 || now < ts {
            return -1;
        }
        now - ts
    })
}

// 线程复用前必清，避免残留 UID/PID 串到后续请求
pub fn clear_fuse_caller_uid() {
    FUSE_CALLER_UID.with(|cell| cell.set(-1));
    FUSE_CALLER_UID_TS_MS.with(|cell| cell.set(-1));
    FUSE_CALLER_PID.with(|cell| cell.set(-1));
}

// 在 clearCallingIdentity 之前调用，保存真实调用方 UID
pub fn set_binder_saved_caller_uid(uid: i32) {
    BINDER_SAVED_CALLER_UID.with(|cell| cell.set(uid));
    BINDER_SAVED_CALLER_UID_TS_MS.with(|cell| cell.set(monotonic_ms()));
}

pub fn get_binder_saved_caller_uid() -> i32 {
    BINDER_SAVED_CALLER_UID.with(|cell| cell.get())
}

// 未缓存返回 -1
pub fn get_binder_saved_caller_uid_age_ms() -> i64 {
    let now = monotonic_ms();
    BINDER_SAVED_CALLER_UID_TS_MS.with(|cell| {
        let ts = cell.get();
        if ts < 0 || now < ts {
            return -1;
        }
        now - ts
    })
}

// 跨请求前必清，避免误用历史 UID
pub fn clear_binder_saved_caller_uid() {
    BINDER_SAVED_CALLER_UID.with(|cell| cell.set(-1));
    BINDER_SAVED_CALLER_UID_TS_MS.with(|cell| cell.set(-1));
}

pub fn set_binder_identity_cleared(cleared: bool) {
    BINDER_IDENTITY_CLEARED.with(|cell| cell.set(cleared));
}

// 处于 clearCallingIdentity 后、restoreCallingIdentity 前的区间
pub fn is_binder_identity_cleared() -> bool {
    BINDER_IDENTITY_CLEARED.with(|cell| cell.get())
}
