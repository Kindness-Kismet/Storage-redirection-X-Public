// FuseFixer 模块入口：在 MediaProvider 进程内挂钩 FUSE 路径校验函数
// 通过移除 Default Ignorable Code Point 阻断包名探测绕过

mod di;
mod image;
mod install;
mod strings;

use std::sync::atomic::{AtomicBool, Ordering};

static IS_INSTALLED: AtomicBool = AtomicBool::new(false);

pub fn install() {
    if IS_INSTALLED.swap(true, Ordering::AcqRel) {
        return;
    }
    if !install::run() {
        IS_INSTALLED.store(false, Ordering::Release);
    }
}
