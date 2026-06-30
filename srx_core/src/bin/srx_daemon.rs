#![cfg(target_os = "android")]
#![allow(clippy::missing_safety_doc)]
#![allow(unsafe_op_in_unsafe_fn)]
#![allow(dead_code)]

#[path = "../config.rs"]
mod config;
#[path = "../daemon.rs"]
mod daemon;
#[path = "../daemon_mount.rs"]
mod daemon_mount;
#[path = "../domain.rs"]
mod domain;
#[path = "../logging.rs"]
mod logging;
#[path = "../mount.rs"]
mod mount;
#[path = "../platform.rs"]
mod platform;
#[path = "../redirect/policy.rs"]
mod redirect_policy;
mod redirect {
    pub(crate) use crate::redirect_policy as policy;
}

fn main() {
    std::process::exit(daemon::main_entry());
}
