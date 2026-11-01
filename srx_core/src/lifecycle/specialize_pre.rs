// 应用 specialize 前流程：配置加载、决策重定向和监控、发送挂载请求
mod companion;
mod diagnostics;
mod perf;
mod writer_context;

use super::RuntimeFlow;
use crate::config::{SettingsHub, watcher};
use crate::logging::Logger;
use crate::platform::module_paths;
use crate::platform::paths::monotonic_ms;
use crate::platform::{self, fs};
use crate::redirect::{PathRouter, policy};
use crate::zygisk::{abi, jni};

use companion::{build_companion_request_payload, send_companion_request_payload};
use diagnostics::{
    install_java_hook, log_allowed_real_paths, log_excluded_real_paths, log_path_mappings,
    should_bypass_file_monitor_ui_process,
};
use perf::{SpecializeMetrics, SpecializePerf, log_specialize_perf};
use writer_context::{SystemWriterContext, resolve_system_writer_context};

impl RuntimeFlow {
    // 请求 Zygisk 在 postAppSpecialize 后卸载模块 .so
    pub(super) fn request_dlclose(&self) {
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
        self.companion_socket = None;
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

        let mut metrics = SpecializeMetrics::default();
        let config = SettingsHub::instance();
        let config_init_started_ms = monotonic_ms();
        if !config.init(None) {
            log::warn!("config init failed");
            return;
        }
        metrics.config_init_ms = monotonic_ms().saturating_sub(config_init_started_ms);

        log::debug!(
            "proc ctx nice={} pkg={} uid={} pid={}",
            nice_name,
            self.package_name,
            self.app_uid,
            self.app_pid
        );

        let config_reload_started_ms = monotonic_ms();
        config.reload_if_changed();
        metrics.config_reload_ms = monotonic_ms().saturating_sub(config_reload_started_ms);
        let shared_uid_started_ms = monotonic_ms();
        policy::refresh_shared_uid_cache();
        metrics.shared_uid_ms = monotonic_ms().saturating_sub(shared_uid_started_ms);

        let decision_started_ms = monotonic_ms();
        self.should_redirect = config.should_redirect(&self.package_name, self.app_uid);
        self.should_monitor = config.should_monitor(&self.package_name, self.app_uid);
        metrics.decision_ms = monotonic_ms().saturating_sub(decision_started_ms);

        let is_system_writer = policy::is_system_writer_package(&self.package_name);
        if should_bypass_file_monitor_ui_process(&self.package_name, &nice_name) {
            self.should_redirect = false;
            self.should_monitor = false;
            self.is_system_writer_hook_redirect = false;
            self.should_skip_post_work = true;
            log::info!(
                "file monitor UI bypass pkg={} nice={} uid={} pid={}",
                self.package_name,
                nice_name,
                self.app_uid,
                self.app_pid
            );
            self.request_dlclose();
            self.log_specialize_exit("file_ui", is_system_writer, perf_started_ms, &metrics);
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
            self.log_specialize_exit("isolated", is_system_writer, perf_started_ms, &metrics);
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
        metrics.writer_context_ms = monotonic_ms().saturating_sub(writer_context_started_ms);

        self.should_install_fuse_fixer = policy::is_media_provider_package(&self.package_name);
        if self.should_install_fuse_fixer {
            self.should_keep_module_loaded = true;
        }

        let enabled_scan_started_ms = monotonic_ms();
        let has_enabled_apps = config.has_enabled_redirect_apps_for_user(self.app_uid);
        metrics.enabled_scan_ms = monotonic_ms().saturating_sub(enabled_scan_started_ms);
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

        // DownloadProvider 在部分系统运行于 android.process.media，启动早期 UID 映射可能尚未生成。
        let is_monitor_provider = policy::is_media_provider_package(&self.package_name)
            || self.package_name == "com.android.providers.downloads"
            || nice_name == "android.process.media"
            || policy::is_shared_uid_process(self.app_uid);
        let should_install_monitor_bridge = self.app_uid >= 0
            && self.app_uid < 100000
            && self.app_uid != 1000
            && !matches!(
                self.package_name.as_str(),
                "android" | "system_server" | "com.android.systemui" | "com.storage.redirect.x"
            )
            && is_monitor_provider;
        if should_install_monitor_bridge {
            self.should_keep_module_loaded = true;
        }
        if !self.should_redirect
            && !self.should_monitor
            && !should_install_monitor_bridge
            && !self.should_install_fuse_fixer
        {
            if !self.should_keep_module_loaded {
                // 模块即将 dlclose，post 阶段不再做任何事，避免在转译进程里触碰匿名段
                self.should_skip_post_work = true;
                self.request_dlclose();
            }
            self.log_specialize_exit("bypass", is_system_writer, perf_started_ms, &metrics);
            return;
        }

        if is_monitor_provider
            && (self.is_system_writer_hook_redirect
                || self.should_monitor
                || should_install_monitor_bridge)
        {
            log::info!(
                "java hook requested pkg={} nice={} uid={} pid={} hook_redirect={}",
                self.package_name,
                nice_name,
                self.app_uid,
                self.app_pid,
                self.is_system_writer_hook_redirect
            );
            install_java_hook(
                self.env,
                self.is_system_writer_hook_redirect,
                should_install_monitor_bridge,
                self.should_monitor,
            );
        }

        Logger::init(Some(&self.package_name));
        if writer_context.is_system_writer {
            self.install_writer_config_watch(args);
        }

        log::info!(
            "app start pkg={} redirect={} monitor={}",
            self.package_name,
            self.should_redirect,
            self.should_monitor
        );

        if !self.should_redirect {
            self.log_specialize_exit("monitor_only", is_system_writer, perf_started_ms, &metrics);
            return;
        }

        self.configure_and_request_mount(
            args,
            writer_context,
            is_system_writer,
            perf_started_ms,
            &mut metrics,
        );
    }

    // 系统代写进程降权后仍可热更新：监听命名空间内可见的配置目录
    fn install_writer_config_watch(&self, args: &abi::AppSpecializeArgs) {
        log::info!("writer: file log off, logcat only");
        let config = SettingsHub::instance();
        let shared_apps_dir = format!("{}/apps", module_paths::SHARED_CONFIG_DIR);
        let watch_config_dir = if fs::is_directory(&shared_apps_dir) {
            config.init(Some(module_paths::SHARED_CONFIG_DIR));
            module_paths::SHARED_CONFIG_DIR
        } else {
            log::warn!("shared config unavailable, use module config dir");
            module_paths::CONFIG_DIR
        };
        let inotify_fd = watcher::init(watch_config_dir);
        if let Some(api) = self.api.as_ref()
            && inotify_fd >= 0
        {
            if api.exempt_app_fd(inotify_fd, args) {
                log::info!("config watch fd exempt fd={}", inotify_fd);
            } else {
                log::warn!("config watch fd exempt failed fd={}", inotify_fd);
                watcher::enable_fallback_poll();
            }
        }
    }

    // 完成路由配置并向伴生进程发送挂载请求；系统代写 per-caller hook 模式跳过挂载
    fn configure_and_request_mount(
        &mut self,
        args: &abi::AppSpecializeArgs,
        writer_context: SystemWriterContext,
        is_system_writer: bool,
        perf_started_ms: i64,
        metrics: &mut SpecializeMetrics,
    ) {
        let config = SettingsHub::instance();
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
        metrics.route_ms = monotonic_ms().saturating_sub(route_config_started_ms);
        metrics.allow_count = allowed_real_paths.len();
        metrics.excluded_count = excluded_real_paths.len();
        metrics.mapping_count = path_mappings.len();

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
            self.log_specialize_exit("writer_hook", is_system_writer, perf_started_ms, metrics);
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
            &excluded_real_paths,
            &path_mappings,
            is_mapping_mode_only,
        );
        metrics.payload_ms = monotonic_ms().saturating_sub(payload_started_ms);
        metrics.payload_bytes = payload.len();
        if payload.is_empty() {
            self.log_specialize_exit("empty_payload", is_system_writer, perf_started_ms, metrics);
            return;
        }

        let send_started_ms = monotonic_ms();
        self.companion_socket = send_companion_request_payload(self.api.as_ref(), args, &payload);
        self.is_mount_request_sent = self.companion_socket.is_some();
        metrics.send_ms = monotonic_ms().saturating_sub(send_started_ms);
        self.log_specialize_exit("mount_request", is_system_writer, perf_started_ms, metrics);
    }

    // 统一构造并输出 specialize 性能诊断；perf 各字段来自累加器
    fn log_specialize_exit(
        &self,
        exit_reason: &str,
        is_system_writer: bool,
        perf_started_ms: i64,
        metrics: &SpecializeMetrics,
    ) {
        log_specialize_perf(&SpecializePerf {
            package_name: &self.package_name,
            exit_reason,
            pid: self.app_pid,
            uid: self.app_uid,
            app_count: SettingsHub::instance().get_app_count(),
            should_redirect: self.should_redirect,
            should_monitor: self.should_monitor,
            is_system_writer,
            is_hook_redirect: self.is_system_writer_hook_redirect,
            allow_count: metrics.allow_count,
            excluded_count: metrics.excluded_count,
            mapping_count: metrics.mapping_count,
            payload_bytes: metrics.payload_bytes,
            config_init_ms: metrics.config_init_ms,
            config_reload_ms: metrics.config_reload_ms,
            shared_uid_ms: metrics.shared_uid_ms,
            decision_ms: metrics.decision_ms,
            writer_context_ms: metrics.writer_context_ms,
            enabled_scan_ms: metrics.enabled_scan_ms,
            route_ms: metrics.route_ms,
            payload_ms: metrics.payload_ms,
            send_ms: metrics.send_ms,
            total_ms: monotonic_ms().saturating_sub(perf_started_ms),
        });
    }
}
