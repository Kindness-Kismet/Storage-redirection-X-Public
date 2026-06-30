#![cfg(target_os = "android")]
#![allow(clippy::missing_safety_doc)]
#![allow(unsafe_op_in_unsafe_fn)]
#![allow(clippy::missing_const_for_thread_local)]

mod config;
#[allow(dead_code)]
mod daemon_mount;
mod domain;
mod hook;
mod java_hook;
mod lifecycle;
mod logging;
mod monitor;
mod mount;
mod platform;
mod redirect;
mod zygisk;
