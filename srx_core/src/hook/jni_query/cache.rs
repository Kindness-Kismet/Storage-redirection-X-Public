use super::types::MAX_TRACKED_WINDOWS;
use jni_sys::{jint, jlong};
use once_cell::sync::Lazy;
use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};

// nativeGetLong 热路径快速判断：无过滤行时跳过 Mutex
static HAS_ANY_FILTERED_ROWS: AtomicBool = AtomicBool::new(false);
static FILTERED_ROWS: Lazy<Mutex<FilteredRowCache>> =
    Lazy::new(|| Mutex::new(FilteredRowCache::new()));

struct FilteredRowCache {
    rows_by_window: HashMap<i64, HashSet<i32>>,
    window_order: VecDeque<i64>,
}

impl FilteredRowCache {
    fn new() -> Self {
        Self {
            rows_by_window: HashMap::new(),
            window_order: VecDeque::new(),
        }
    }

    // 记录被过滤的行，容量超限时淘汰最旧窗口
    fn mark_filtered(&mut self, window_ptr: i64, row: i32) {
        if !self.rows_by_window.contains_key(&window_ptr) {
            self.window_order.push_back(window_ptr);
            if self.window_order.len() > MAX_TRACKED_WINDOWS
                && let Some(expired) = self.window_order.pop_front()
            {
                self.rows_by_window.remove(&expired);
            }
        }
        self.rows_by_window
            .entry(window_ptr)
            .or_default()
            .insert(row);
    }

    fn is_filtered(&self, window_ptr: i64, row: i32) -> bool {
        self.rows_by_window
            .get(&window_ptr)
            .is_some_and(|rows| rows.contains(&row))
    }

    // 窗口被复用前调用，移除该窗口的全部行标记
    fn clear_window(&mut self, window_ptr: i64) -> bool {
        if self.rows_by_window.remove(&window_ptr).is_none() {
            return false;
        }
        self.window_order.retain(|ptr| *ptr != window_ptr);
        true
    }

    fn is_empty(&self) -> bool {
        self.rows_by_window.is_empty()
    }
}

pub(super) fn mark_filtered_row(window_ptr: jlong, row: jint) {
    if window_ptr == 0 || row < 0 {
        return;
    }
    let Ok(mut cache) = FILTERED_ROWS.lock() else {
        return;
    };
    cache.mark_filtered(window_ptr, row);
    HAS_ANY_FILTERED_ROWS.store(true, Ordering::Release);
}

pub(super) fn is_filtered_row(window_ptr: jlong, row: jint) -> bool {
    if !HAS_ANY_FILTERED_ROWS.load(Ordering::Acquire) {
        return false;
    }
    if window_ptr == 0 || row < 0 {
        return false;
    }
    let Ok(cache) = FILTERED_ROWS.lock() else {
        return false;
    };
    cache.is_filtered(window_ptr, row)
}

// 清空指定窗口的全部过滤标记，供 nativeClear hook 在窗口复用前调用
pub(super) fn clear_filtered_window(window_ptr: jlong) {
    if !HAS_ANY_FILTERED_ROWS.load(Ordering::Acquire) {
        return;
    }
    if window_ptr == 0 {
        return;
    }
    let Ok(mut cache) = FILTERED_ROWS.lock() else {
        return;
    };
    if cache.clear_window(window_ptr) && cache.is_empty() {
        HAS_ANY_FILTERED_ROWS.store(false, Ordering::Release);
    }
}
