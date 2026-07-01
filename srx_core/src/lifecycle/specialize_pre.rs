// 应用 specialize 前流程：配置加载、决策重定向和监控、发送挂载请求
use super::RuntimeFlow;
use crate::config::{SettingsHub, watcher};
use crate::domain::PathMapping;
use crate::hook;
use crate::java_hook;
use crate::logging::Logger;
use crate::monitor::AuditTrail;
use crate::platform::module_paths;
use crate::platform::paths::monotonic_ms;
use crate::platform::{self, fs};
use crate::redirect::{PathRouter, policy};
use crate::zygisk::{abi, jni};
use std::ffi::CString;
use std::sync::atomic::{AtomicU64, Ordering};

static MONITOR_HOOK_PROCESS_COUNT: AtomicU64 = AtomicU64::new(0);
const SPECIALIZE_SLOW_MS: i64 = 20;

impl RuntimeFlow {
    // 请求 Zygisk 在 postAppSpecialize 后卸载模块 .so
    fn request_dlclose(&self) {
        if self.should_keep_module_loaded {
            log::debug!(
                "dlclose skipped pid={} pkg={}",
                self.app_pid,
                self.package_name
            );
            return;
        }
        if let Some(api) = self.api.as_ref() {
            api.set_option(abi::ZygiskOption::DlcloseModuleLibrary);
            log::debug!(
                "dlclose requested pid={} pkg={}",
                self.app_pid,
                self.package_name
            );
        }
    }

    fn reset_specialize_state(&mut self) {
        self.app_data_dir.clear();
        self.should_redirect = false;
        self.should_monitor = false;
        self.is_mount_request_sent = false;
        self.is_mount_applied = false;
        self.is_system_writer_hook_redirect = false;
        self.should_install_fuse_fixer = false;
        self.should_skip_post_work = false;
        self.should_keep_module_loaded = false;
    }

    pub fn pre_app_specialize(&mut self, args: *mut abi::AppSpecializeArgs) {
        let perf_started_ms = monotonic_ms();
        if args.is_null() {
            return;
        }

        let args = unsafe { &mut *args };
        let nice_name = if args.nice_name.is_null() {
            String::new()
        } else {
            jni::get_jstring_utf8(self.env, unsafe { *args.nice_name })
        };
        if nice_name.is_empty() {
            log::warn!("nice_name empty");
            return;
        }

        let mut package_name = nice_name.clone();
        if let Some(pos) = package_name.find(':') {
            package_name.truncate(pos);
        }
        self.package_name = package_name.clone();
        self.app_uid = unsafe { *args.uid };
        self.app_pid = unsafe { libc::getpid() } as i32;
        self.reset_specialize_state();

        if args.is_child_zygote() {
            self.should_skip_post_work = true;
            log::info!(
                "child zygote bypass nice={} pkg={} uid={} pid={}",
                nice_name,
                self.package_name,
                self.app_uid,
                self.app_pid
            );
            self.request_dlclose();
            return;
        }

        let config = SettingsHub::instance();
        let config_init_started_ms = monotonic_ms();
        if !config.init(None) {
            log::warn!("config init failed");
            return;
        }
        let config_init_ms = monotonic_ms().saturating_sub(config_init_started_ms);

        log::debug!(
            "proc ctx nice={} pkg={} uid={} pid={}",
            nice_name,
            self.package_name,
            self.app_uid,
            self.app_pid
        );

        let config_reload_started_ms = monotonic_ms();
        config.reload_if_changed();
        let config_reload_ms = monotonic_ms().saturating_sub(config_reload_started_ms);
        let shared_uid_started_ms = monotonic_ms();
        policy::refresh_shared_uid_cache();
        let shared_uid_ms = monotonic_ms().saturating_sub(shared_uid_started_ms);

        let decision_started_ms = monotonic_ms();
        self.should_redirect = config.should_redirect(&self.package_name, self.app_uid);
        self.should_monitor = config.should_monitor(&self.package_name, self.app_uid);
        let decision_ms = monotonic_ms().saturating_sub(decision_started_ms);

        let is_system_writer = policy::is_system_writer_package(&self.package_name);
        if nice_name.ends_with(":PhotoPicker") {
            self.should_redirect = false;
            self.should_monitor = false;
            self.is_system_writer_hook_redirect = false;
            self.should_skip_post_work = true;
            log::info!(
                "PhotoPicker bypass nice={} uid={} pid={}",
                nice_name,
                self.app_uid,
                self.app_pid
            );
            self.request_dlclose();
            log_specialize_perf(&SpecializePerf {
                package_name: &self.package_name,
                exit_reason: "photopicker",
                pid: self.app_pid,
                uid: self.app_uid,
                app_count: config.get_app_count(),
                should_redirect: self.should_redirect,
                should_monitor: self.should_monitor,
                is_system_writer,
                is_hook_redirect: self.is_system_writer_hook_redirect,
                allow_count: 0,
                excluded_count: 0,
                mapping_count: 0,
                payload_bytes: 0,
                config_init_ms,
                config_reload_ms,
                shared_uid_ms,
                decision_ms,
                writer_context_ms: 0,
                enabled_scan_ms: 0,
                route_ms: 0,
                payload_ms: 0,
                send_ms: 0,
                total_ms: monotonic_ms().saturating_sub(perf_started_ms),
            });
            return;
        }

        // 隔离进程无 FUSE 挂载和存储权限，跳过重定向
        if platform::is_isolated_uid(self.app_uid) {
            if self.should_redirect {
                log::info!("isolated uid skip redirect uid={}", self.app_uid);
                self.should_redirect = false;
            }
            if !self.should_redirect && !self.should_monitor {
                self.request_dlclose();
            }
            log_specialize_perf(&SpecializePerf {
                package_name: &self.package_name,
                exit_reason: "isolated",
                pid: self.app_pid,
                uid: self.app_uid,
                app_count: config.get_app_count(),
                should_redirect: self.should_redirect,
                should_monitor: self.should_monitor,
                is_system_writer,
                is_hook_redirect: self.is_system_writer_hook_redirect,
                allow_count: 0,
                excluded_count: 0,
                mapping_count: 0,
                payload_bytes: 0,
                config_init_ms,
                config_reload_ms,
                shared_uid_ms,
                decision_ms,
                writer_context_ms: 0,
                enabled_scan_ms: 0,
                route_ms: 0,
                payload_ms: 0,
                send_ms: 0,
                total_ms: monotonic_ms().saturating_sub(perf_started_ms),
            });
            return;
        }

        if is_system_writer {
            log::info!(
                "writer init pkg={} uid={} apps={} redirect={} monitor={}",
                self.package_name,
                self.app_uid,
                config.get_app_count(),
                self.should_redirect,
                self.should_monitor
            );
        }

        let writer_context_started_ms = monotonic_ms();
        let writer_context = resolve_system_writer_context(
            &self.package_name,
            self.app_uid,
            config,
            &mut self.should_redirect,
            &mut self.should_monitor,
            &mut self.is_system_writer_hook_redirect,
        );
        let writer_context_ms = monotonic_ms().saturating_sub(writer_context_started_ms);

        self.should_install_fuse_fixer =
            config.is_fuse_fixer_enabled() && policy::is_media_provider_package(&self.package_name);
        if self.should_install_fuse_fixer {
            self.should_keep_module_loaded = true;
        }

        let enabled_scan_started_ms = monotonic_ms();
        let has_enabled_apps = config.has_enabled_redirect_apps_for_user(self.app_uid);
        let enabled_scan_ms = monotonic_ms().saturating_sub(enabled_scan_started_ms);
        // 零配置 MediaProvider 也保留 hook，等待后续 inotify reload 动态接管
        if is_system_writer
            && writer_context.is_media_provider
            && !writer_context.has_merged_writer_mappings
            && !has_enabled_apps
        {
            self.should_redirect = true;
            self.is_system_writer_hook_redirect = true;
            log::info!("writer zero-config dynamic hook: keep hooks for inotify reload");
        }
        if is_system_writer {
            log::info!(
                "writer final pkg={} uid={} apps={} enabled={} redirect={} monitor={} hook_redirect={}",
                self.package_name,
                self.app_uid,
                config.get_app_count(),
                has_enabled_apps,
                self.should_redirect,
                self.should_monitor,
                self.is_system_writer_hook_redirect
            );
        }

        if !self.should_redirect && !self.should_monitor {
            if !self.should_keep_module_loaded {
                // 模块即将 dlclose，post 阶段不再做任何事，避免在转译进程里触碰匿名段
                self.should_skip_post_work = true;
            }
            self.request_dlclose();
            log_specialize_perf(&SpecializePerf {
                package_name: &self.package_name,
                exit_reason: "bypass",
                pid: self.app_pid,
                uid: self.app_uid,
                app_count: config.get_app_count(),
                should_redirect: self.should_redirect,
                should_monitor: self.should_monitor,
                is_system_writer,
                is_hook_redirect: self.is_system_writer_hook_redirect,
                allow_count: 0,
                excluded_count: 0,
                mapping_count: 0,
                payload_bytes: 0,
                config_init_ms,
                config_reload_ms,
                shared_uid_ms,
                decision_ms,
                writer_context_ms,
                enabled_scan_ms,
                route_ms: 0,
                payload_ms: 0,
                send_ms: 0,
                total_ms: monotonic_ms().saturating_sub(perf_started_ms),
            });
            return;
        }

        if policy::is_media_provider_package(&self.package_name)
            && self.is_system_writer_hook_redirect
        {
            log::info!(
                "java hook requested pkg={} nice={} uid={} pid={} hook_redirect={}",
                self.package_name,
                nice_name,
                self.app_uid,
                self.app_pid,
                self.is_system_writer_hook_redirect
            );
            install_java_hook(self.env);
        }

        if self.should_redirect
            && let Some(api) = self.api.as_ref()
            && !writer_context.is_system_writer
        {
            hook::install_cursor_window_native_hook(api, self.env, &self.package_name);
        }

        Logger::init(Some(&self.package_name));
        if writer_context.is_system_writer {
            log::info!("writer: file log off, logcat only");
            config.init(Some(module_paths::SHARED_CONFIG_DIR));
            // 监听共享配置目录，bind mount 后降权仍可访问
            let inotify_fd = watcher::init(module_paths::SHARED_CONFIG_DIR);
            if let Some(api) = self.api.as_ref()
                && inotify_fd >= 0
            {
                if api.exempt_fd(inotify_fd) {
                    log::info!("config watch fd exempt fd={}", inotify_fd);
                } else {
                    log::warn!("config watch fd exempt failed fd={}", inotify_fd);
                }
            }
        }

        log::info!(
            "app start pkg={} redirect={} monitor={}",
            self.package_name,
            self.should_redirect,
            self.should_monitor
        );

        if self.should_monitor {
            let trail = AuditTrail::instance();
            trail.set_enabled(true);

            let mut monitor_package = self.package_name.clone();
            if policy::is_shared_uid_process(self.app_uid) {
                let shared_uid = policy::get_shared_uid_packages_string(self.app_uid);
                if !shared_uid.is_empty() {
                    monitor_package = shared_uid;
                }
            }
            trail.init(&monitor_package, self.app_uid);

            let monitor_fd = trail.get_log_fd();
            if let Some(api) = self.api.as_ref()
                && monitor_fd >= 0
            {
                if api.exempt_fd(monitor_fd) {
                    log::info!("monitor log fd exempt fd={}", monitor_fd);
                } else {
                    log::warn!("monitor log fd exempt failed fd={}", monitor_fd);
                }
            }

            let hook_count = MONITOR_HOOK_PROCESS_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
            log::info!(
                "monitor hook procs total={} cur={}",
                hook_count,
                self.package_name
            );
        } else {
            AuditTrail::instance().set_enabled(false);
        }

        if !self.should_redirect {
            log_specialize_perf(&SpecializePerf {
                package_name: &self.package_name,
                exit_reason: "monitor_only",
                pid: self.app_pid,
                uid: self.app_uid,
                app_count: config.get_app_count(),
                should_redirect: self.should_redirect,
                should_monitor: self.should_monitor,
                is_system_writer,
                is_hook_redirect: self.is_system_writer_hook_redirect,
                allow_count: 0,
                excluded_count: 0,
                mapping_count: 0,
                payload_bytes: 0,
                config_init_ms,
                config_reload_ms,
                shared_uid_ms,
                decision_ms,
                writer_context_ms,
                enabled_scan_ms,
                route_ms: 0,
                payload_ms: 0,
                send_ms: 0,
                total_ms: monotonic_ms().saturating_sub(perf_started_ms),
            });
            return;
        }

        log::info!("config loaded apps={}", config.get_app_count());
        let route_config_started_ms = monotonic_ms();
        PathRouter::instance().init();

        let allowed_real_paths = config.get_allowed_real_paths(&self.package_name, self.app_uid);
        let excluded_real_paths = config.get_excluded_real_paths(&self.package_name, self.app_uid);
        let mut path_mappings = config.get_path_mappings(&self.package_name, self.app_uid);
        let mut is_mapping_mode_only = false;

        log::info!(
            "config sum pkg={} allow={} excl={} map={}",
            self.package_name,
            allowed_real_paths.len(),
            excluded_real_paths.len(),
            path_mappings.len()
        );

        if !allowed_real_paths.is_empty() {
            log_allowed_real_paths(&allowed_real_paths);
        }
        if !excluded_real_paths.is_empty() {
            log_excluded_real_paths(&excluded_real_paths);
        }
        if !path_mappings.is_empty() {
            log_path_mappings(&path_mappings);
        }

        if writer_context.is_system_writer && writer_context.has_merged_writer_mappings {
            is_mapping_mode_only = true;
            if self.is_system_writer_hook_redirect {
                path_mappings.clear();
                log::info!("writer per-caller hook map, skip global mount");
            } else {
                path_mappings = writer_context.merged_writer_mappings;
                log::info!("writer merged map count={}", path_mappings.len());
            }
        }

        let user_id = platform::user_id_from_uid(self.app_uid);
        let redirect_base = format!(
            "/storage/emulated/{}/Android/data/{}/sdcard",
            user_id, self.package_name
        );
        self.app_data_dir = if args.app_data_dir.is_null() {
            String::new()
        } else {
            jni::get_jstring_utf8(self.env, unsafe { *args.app_data_dir })
        };

        if !self.is_system_writer_hook_redirect {
            clear_mount_status_marker(&self.app_data_dir, self.app_pid);
        }

        if self.is_system_writer_hook_redirect {
            PathRouter::instance().configure(
                &self.package_name,
                self.app_uid,
                &redirect_base,
                &[],
                &[],
                &[],
            );
        } else {
            PathRouter::instance().configure(
                &self.package_name,
                self.app_uid,
                &redirect_base,
                &allowed_real_paths,
                &excluded_real_paths,
                &path_mappings,
            );
        }
        let route_ms = monotonic_ms().saturating_sub(route_config_started_ms);

        if is_mapping_mode_only {
            log::info!(
                "map-only allow={} excl={} map={}",
                allowed_real_paths.len(),
                excluded_real_paths.len(),
                path_mappings.len()
            );
        } else {
            log::info!(
                "redirect allow={} excl={} map={}",
                allowed_real_paths.len(),
                excluded_real_paths.len(),
                path_mappings.len()
            );
        }

        log::info!(
            "monitor pkg={} on={}",
            self.package_name,
            self.should_monitor
        );

        if self.is_system_writer_hook_redirect {
            log::info!("writer skip companion mount (per-caller hook)");
            log_specialize_perf(&SpecializePerf {
                package_name: &self.package_name,
                exit_reason: "writer_hook",
                pid: self.app_pid,
                uid: self.app_uid,
                app_count: config.get_app_count(),
                should_redirect: self.should_redirect,
                should_monitor: self.should_monitor,
                is_system_writer,
                is_hook_redirect: self.is_system_writer_hook_redirect,
                allow_count: allowed_real_paths.len(),
                excluded_count: excluded_real_paths.len(),
                mapping_count: path_mappings.len(),
                payload_bytes: 0,
                config_init_ms,
                config_reload_ms,
                shared_uid_ms,
                decision_ms,
                writer_context_ms,
                enabled_scan_ms,
                route_ms,
                payload_ms: 0,
                send_ms: 0,
                total_ms: monotonic_ms().saturating_sub(perf_started_ms),
            });
            return;
        }

        let payload_started_ms = monotonic_ms();
        let payload = build_companion_request_payload(
            self.app_pid,
            self.app_uid,
            &self.package_name,
            &self.app_data_dir,
            &redirect_base,
            &allowed_real_paths,
            &path_mappings,
            is_mapping_mode_only,
        );
        let payload_ms = monotonic_ms().saturating_sub(payload_started_ms);
        let payload_bytes = payload.len();
        if payload.is_empty() {
            log_specialize_perf(&SpecializePerf {
                package_name: &self.package_name,
                exit_reason: "empty_payload",
                pid: self.app_pid,
                uid: self.app_uid,
                app_count: config.get_app_count(),
                should_redirect: self.should_redirect,
                should_monitor: self.should_monitor,
                is_system_writer,
                is_hook_redirect: self.is_system_writer_hook_redirect,
                allow_count: allowed_real_paths.len(),
                excluded_count: excluded_real_paths.len(),
                mapping_count: path_mappings.len(),
                payload_bytes,
                config_init_ms,
                config_reload_ms,
                shared_uid_ms,
                decision_ms,
                writer_context_ms,
                enabled_scan_ms,
                route_ms,
                payload_ms,
                send_ms: 0,
                total_ms: monotonic_ms().saturating_sub(perf_started_ms),
            });
            return;
        }

        let send_started_ms = monotonic_ms();
        self.is_mount_request_sent = send_companion_request_payload(self.api.as_ref(), &payload);
        let send_ms = monotonic_ms().saturating_sub(send_started_ms);
        log_specialize_perf(&SpecializePerf {
            package_name: &self.package_name,
            exit_reason: "mount_request",
            pid: self.app_pid,
            uid: self.app_uid,
            app_count: config.get_app_count(),
            should_redirect: self.should_redirect,
            should_monitor: self.should_monitor,
            is_system_writer,
            is_hook_redirect: self.is_system_writer_hook_redirect,
            allow_count: allowed_real_paths.len(),
            excluded_count: excluded_real_paths.len(),
            mapping_count: path_mappings.len(),
            payload_bytes,
            config_init_ms,
            config_reload_ms,
            shared_uid_ms,
            decision_ms,
            writer_context_ms,
            enabled_scan_ms,
            route_ms,
            payload_ms,
            send_ms,
            total_ms: monotonic_ms().saturating_sub(perf_started_ms),
        });
    }
}

struct SystemWriterContext {
    is_system_writer: bool,
    is_media_provider: bool,
    has_merged_writer_mappings: bool,
    merged_writer_mappings: Vec<PathMapping>,
}

struct SpecializePerf<'a> {
    package_name: &'a str,
    exit_reason: &'a str,
    pid: i32,
    uid: i32,
    app_count: usize,
    should_redirect: bool,
    should_monitor: bool,
    is_system_writer: bool,
    is_hook_redirect: bool,
    allow_count: usize,
    excluded_count: usize,
    mapping_count: usize,
    payload_bytes: usize,
    config_init_ms: i64,
    config_reload_ms: i64,
    shared_uid_ms: i64,
    decision_ms: i64,
    writer_context_ms: i64,
    enabled_scan_ms: i64,
    route_ms: i64,
    payload_ms: i64,
    send_ms: i64,
    total_ms: i64,
}

fn install_java_hook(env: *mut jni_sys::JNIEnv) {
    if !java_hook::is_available() {
        log::info!("java hook skip: hooker dex unavailable");
        return;
    }
    if java_hook::init_once(env) {
        log::info!("java hook init ok");
    } else {
        log::warn!("java hook init failed");
    }
}

fn log_specialize_perf(perf: &SpecializePerf<'_>) {
    if perf.total_ms < SPECIALIZE_SLOW_MS
        && !perf.should_redirect
        && !perf.should_monitor
        && !perf.is_system_writer
        && perf.app_count < 100
    {
        return;
    }

    log::info!(
        "perf specialize pkg={} pid={} uid={} exit={} apps={} redirect={} monitor={} writer={} hook_redirect={} allow={} excl={} map={} payload={} init_ms={} reload_ms={} uid_ms={} decision_ms={} writer_ms={} enabled_scan_ms={} route_ms={} payload_ms={} send_ms={} total_ms={}",
        perf.package_name,
        perf.pid,
        perf.uid,
        perf.exit_reason,
        perf.app_count,
        perf.should_redirect,
        perf.should_monitor,
        perf.is_system_writer,
        perf.is_hook_redirect,
        perf.allow_count,
        perf.excluded_count,
        perf.mapping_count,
        perf.payload_bytes,
        perf.config_init_ms,
        perf.config_reload_ms,
        perf.shared_uid_ms,
        perf.decision_ms,
        perf.writer_context_ms,
        perf.enabled_scan_ms,
        perf.route_ms,
        perf.payload_ms,
        perf.send_ms,
        perf.total_ms
    );
}

// 解析系统代写进程的重定向上下文，决定挂载或 Hook 模式
fn resolve_system_writer_context(
    package_name: &str,
    app_uid: i32,
    config: &SettingsHub,
    should_redirect: &mut bool,
    should_monitor: &mut bool,
    is_system_writer_hook_redirect: &mut bool,
) -> SystemWriterContext {
    let is_shared_uid_writer = policy::is_shared_uid_process(app_uid);
    let mut context = SystemWriterContext {
        is_system_writer: policy::is_system_writer_package(package_name) || is_shared_uid_writer,
        is_media_provider: policy::is_media_provider_package(package_name) || is_shared_uid_writer,
        has_merged_writer_mappings: false,
        merged_writer_mappings: Vec::new(),
    };

    if !context.is_system_writer {
        return context;
    }

    policy::refresh_shared_uid_cache();
    context.merged_writer_mappings = config.get_merged_path_mappings_for_user(app_uid);
    context.has_merged_writer_mappings = !context.merged_writer_mappings.is_empty();

    let has_enabled_apps = config.has_enabled_redirect_apps_for_user(app_uid);

    let is_file_monitor_enabled = config.is_file_monitor_enabled();
    if context.has_merged_writer_mappings || has_enabled_apps {
        *should_redirect = true;
        *is_system_writer_hook_redirect = true;
    } else {
        *should_redirect = false;
        *is_system_writer_hook_redirect = false;
    }
    if !*should_monitor && context.is_media_provider && is_file_monitor_enabled {
        *should_monitor = true;
    }

    if context.has_merged_writer_mappings {
        log::info!(
            "writer map-mode on merged={} (per-caller hook)",
            context.merged_writer_mappings.len()
        );
    } else if has_enabled_apps {
        log::info!("writer map-mode on: enabled apps exist, caller default redirect");
    } else {
        log::info!("writer map-mode skip: no enabled apps, fallback monitor/bypass");
    }

    context
}

fn log_allowed_real_paths(paths: &[String]) {
    for path in paths {
        log::info!("cfg allow={}", path);
    }
}

fn log_excluded_real_paths(paths: &[String]) {
    for path in paths {
        log::info!("cfg excl={}", path);
    }
}

fn log_path_mappings(mappings: &[PathMapping]) {
    for mapping in mappings {
        log::info!("cfg map {} -> {}", mapping.request_path, mapping.final_path);
    }
}

#[allow(clippy::too_many_arguments)]
fn build_companion_request_payload(
    pid: i32,
    uid: i32,
    package_name: &str,
    app_data_dir: &str,
    redirect_target: &str,
    allowed_real_paths: &[String],
    path_mappings: &[PathMapping],
    is_mapping_mode_only: bool,
) -> String {
    let mut mappings = Vec::new();
    for mapping in path_mappings {
        mappings.push(serde_json::json!({
            "request_path": mapping.request_path,
            "final_path": mapping.final_path,
        }));
    }

    let payload = serde_json::json!({
        "pid": pid,
        "uid": uid,
        "package": package_name,
        "app_data_dir": app_data_dir,
        "redirect_target": redirect_target,
        "allowed_real_paths": allowed_real_paths,
        "mapping_mode_only": is_mapping_mode_only,
        "path_mappings": mappings,
    });

    serde_json::to_string(&payload).unwrap_or_default()
}

// 序列化挂载请求并通过伴生进程 fd 发送
fn send_companion_request_payload(api: Option<&abi::Api>, payload: &str) -> bool {
    let Some(api) = api else {
        return false;
    };
    if payload.is_empty() {
        return false;
    }

    let fd = api.connect_companion();
    if fd < 0 {
        log::warn!("companion connect failed");
        return false;
    }

    let payload_len = payload.len() as u32;
    let sent =
        fs::write_all(fd, &payload_len.to_ne_bytes()) && fs::write_all(fd, payload.as_bytes());
    unsafe { libc::close(fd) };

    if !sent {
        log::warn!("companion send failed");
        return false;
    }

    log::info!("companion req sent (async mount)");
    true
}

// 发送挂载请求前清理当前 PID 标记，避免读取到旧结果。
fn clear_mount_status_marker(app_data_dir: &str, app_pid: i32) {
    if app_data_dir.is_empty() || app_pid <= 0 {
        return;
    }

    let marker_path = format!("{}/.srx_mount_status_{}", app_data_dir, app_pid);
    let Ok(c_path) = CString::new(marker_path.clone()) else {
        return;
    };
    if unsafe { libc::unlink(c_path.as_ptr()) } == 0 {
        log::info!("old marker cleared {}", marker_path);
    }
}
