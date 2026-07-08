use crate::config::{SettingsHub, watcher};
use crate::platform::paths;
use std::sync::atomic::{AtomicI64, Ordering};

const RUNTIME_CONFIG_RELOAD_INTERVAL_MS: i64 = 1000;
const RUNTIME_CONFIG_EVENT_RELOAD_INTERVAL_MS: i64 = 100;
const RUNTIME_CONFIG_EVENT_RELOAD_WINDOW_MS: i64 = 1500;

static LAST_RUNTIME_CONFIG_RELOAD_CHECK_MS: AtomicI64 = AtomicI64::new(i64::MIN / 2);
static FORCE_RUNTIME_CONFIG_RELOAD_UNTIL_MS: AtomicI64 = AtomicI64::new(i64::MIN / 2);

pub(super) enum RuntimeReloadCheck {
    Skipped,
    Checked { did_reload: bool },
}

impl RuntimeReloadCheck {
    pub(super) fn did_reload(&self) -> bool {
        matches!(self, Self::Checked { did_reload: true })
    }
}

pub(super) fn force_after_disk_change() -> bool {
    let now_ms = paths::monotonic_ms();
    FORCE_RUNTIME_CONFIG_RELOAD_UNTIL_MS.store(
        now_ms.saturating_add(RUNTIME_CONFIG_EVENT_RELOAD_WINDOW_MS),
        Ordering::Relaxed,
    );
    LAST_RUNTIME_CONFIG_RELOAD_CHECK_MS.store(now_ms, Ordering::Relaxed);
    SettingsHub::instance().reload_force()
}

pub(super) fn poll_or_check_throttled() -> RuntimeReloadCheck {
    if watcher::poll_changed() {
        return RuntimeReloadCheck::Checked {
            did_reload: force_after_disk_change(),
        };
    }

    let now_ms = paths::monotonic_ms();
    let force_until_ms = FORCE_RUNTIME_CONFIG_RELOAD_UNTIL_MS.load(Ordering::Relaxed);
    let is_event_window = now_ms <= force_until_ms;
    let is_fallback_due = watcher::should_fallback_poll();
    let reload_interval_ms = if is_event_window {
        RUNTIME_CONFIG_EVENT_RELOAD_INTERVAL_MS
    } else if is_fallback_due {
        0
    } else {
        RUNTIME_CONFIG_RELOAD_INTERVAL_MS
    };
    let last_ms = LAST_RUNTIME_CONFIG_RELOAD_CHECK_MS.load(Ordering::Relaxed);
    if now_ms.saturating_sub(last_ms) < reload_interval_ms {
        return RuntimeReloadCheck::Skipped;
    }
    if LAST_RUNTIME_CONFIG_RELOAD_CHECK_MS
        .compare_exchange(last_ms, now_ms, Ordering::Relaxed, Ordering::Relaxed)
        .is_err()
    {
        return RuntimeReloadCheck::Skipped;
    }

    let did_reload = if is_event_window {
        SettingsHub::instance().reload_force()
    } else {
        SettingsHub::instance().reload_if_changed()
    };
    RuntimeReloadCheck::Checked { did_reload }
}
