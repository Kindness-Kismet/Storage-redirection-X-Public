mod caller;
mod context;
mod diagnostic;
mod entries;
mod fuse_fixer;
mod jni_query;
mod monitor;
mod ops;
mod path;
mod runtime;
pub mod stats;
mod util;

pub use jni_query::install_cursor_window_native_hook;
pub(crate) use jni_query::rewrite_cursor_storage_path_for_caller;
pub use stats::InterceptHub;

pub fn install_fuse_fixer_hook() {
    fuse_fixer::install();
}
