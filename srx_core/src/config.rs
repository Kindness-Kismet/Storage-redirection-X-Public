use crate::domain::PathMapping;
use crate::platform::module_paths;
use once_cell::sync::Lazy;
use std::collections::{HashMap, HashSet};
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

mod consensus;
mod fingerprint;
mod ingest;
mod inspect;
mod merge;
mod raw_scan;
mod source;
pub mod watcher;

#[derive(Clone)]
pub struct UserProfile {
    pub is_enabled: bool,
    pub allowed_real_paths: Vec<String>,
    pub excluded_real_paths: Vec<String>,
    pub path_mappings: Vec<PathMapping>,
}

#[derive(Clone)]
pub struct AppProfile {
    pub user_profiles: HashMap<i32, UserProfile>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RawUserEnabledState {
    Enabled,
    Disabled,
    Unavailable,
}

struct SettingsState {
    config_dir: String,
    is_file_monitor_enabled: bool,
    is_fuse_fixer_enabled: bool,
    apps: HashMap<String, AppProfile>,
    is_loaded: bool,
    should_log_summary: bool,
    last_fingerprint: u64,
    invalid_packages: HashSet<String>,
}

impl SettingsState {
    fn new() -> Self {
        Self {
            config_dir: module_paths::CONFIG_DIR.to_string(),
            is_file_monitor_enabled: false,
            is_fuse_fixer_enabled: false,
            apps: HashMap::new(),
            is_loaded: false,
            should_log_summary: true,
            last_fingerprint: 0,
            invalid_packages: HashSet::new(),
        }
    }
}

pub struct SettingsHub {
    state: Mutex<SettingsState>,
    config_version: AtomicU64,
    is_fuse_fixer_enabled: AtomicBool,
}

impl SettingsHub {
    fn new() -> Self {
        Self {
            state: Mutex::new(SettingsState::new()),
            config_version: AtomicU64::new(0),
            is_fuse_fixer_enabled: AtomicBool::new(false),
        }
    }

    pub fn instance() -> &'static SettingsHub {
        &SETTINGS_HUB
    }

    pub fn config_version(&self) -> u64 {
        self.config_version.load(Ordering::Relaxed)
    }

    fn bump_config_version(&self) {
        self.config_version.fetch_add(1, Ordering::Relaxed);
    }
}

static SETTINGS_HUB: Lazy<SettingsHub> = Lazy::new(SettingsHub::new);
