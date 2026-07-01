use super::super::runtime;
use super::super::stats::InterceptHub;
use crate::redirect::policy;
use libc::{c_char, c_int, c_uint, c_void};
use std::sync::atomic::{AtomicU64, Ordering};

static SYSTEM_WRITER_QUERY_BYPASS_COUNT: AtomicU64 = AtomicU64::new(0);

fn should_bypass_system_writer_query(hub: &InterceptHub, op_name: &str) -> bool {
    if !policy::is_system_writer_package(&hub.get_package_name()) {
        return false;
    }

    let count = SYSTEM_WRITER_QUERY_BYPASS_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
    if count == 1 || count.is_multiple_of(512) {
        log::debug!(
            "query bypass system_writer pkg={} op={} n={}",
            hub.get_package_name(),
            op_name,
            count
        );
    }
    true
}

pub unsafe extern "C" fn hooked_stat(pathname: *const c_char, statbuf: *mut libc::stat) -> c_int {
    let self_ptr = hooked_stat as *mut c_void;
    runtime::with_hook_guard(
        || {
            runtime::call_prev(
                self_ptr,
                || libc::stat(pathname, statbuf),
                |prev| {
                    let f: unsafe extern "C" fn(*const c_char, *mut libc::stat) -> c_int =
                        std::mem::transmute(prev);
                    f(pathname, statbuf)
                },
            )
        },
        |hub| {
            hub.increment_stat_calls();
            if should_bypass_system_writer_query(hub, "stat") {
                return runtime::call_prev(
                    self_ptr,
                    || libc::stat(pathname, statbuf),
                    |prev| {
                        let f: unsafe extern "C" fn(*const c_char, *mut libc::stat) -> c_int =
                            std::mem::transmute(prev);
                        f(pathname, statbuf)
                    },
                );
            }
            runtime::with_redirected_path(hub, "stat", pathname, |final_path| {
                runtime::call_prev(
                    self_ptr,
                    || libc::stat(final_path, statbuf),
                    |prev| {
                        let f: unsafe extern "C" fn(*const c_char, *mut libc::stat) -> c_int =
                            std::mem::transmute(prev);
                        f(final_path, statbuf)
                    },
                )
            })
        },
    )
}

pub unsafe extern "C" fn hooked_lstat(pathname: *const c_char, statbuf: *mut libc::stat) -> c_int {
    let self_ptr = hooked_lstat as *mut c_void;
    runtime::with_hook_guard(
        || {
            runtime::call_prev(
                self_ptr,
                || libc::lstat(pathname, statbuf),
                |prev| {
                    let f: unsafe extern "C" fn(*const c_char, *mut libc::stat) -> c_int =
                        std::mem::transmute(prev);
                    f(pathname, statbuf)
                },
            )
        },
        |hub| {
            hub.increment_stat_calls();
            if should_bypass_system_writer_query(hub, "lstat") {
                return runtime::call_prev(
                    self_ptr,
                    || libc::lstat(pathname, statbuf),
                    |prev| {
                        let f: unsafe extern "C" fn(*const c_char, *mut libc::stat) -> c_int =
                            std::mem::transmute(prev);
                        f(pathname, statbuf)
                    },
                );
            }
            runtime::with_redirected_path(hub, "lstat", pathname, |final_path| {
                runtime::call_prev(
                    self_ptr,
                    || libc::lstat(final_path, statbuf),
                    |prev| {
                        let f: unsafe extern "C" fn(*const c_char, *mut libc::stat) -> c_int =
                            std::mem::transmute(prev);
                        f(final_path, statbuf)
                    },
                )
            })
        },
    )
}

pub unsafe extern "C" fn hooked_fstatat(
    dirfd: c_int,
    pathname: *const c_char,
    statbuf: *mut libc::stat,
    flags: c_int,
) -> c_int {
    let self_ptr = hooked_fstatat as *mut c_void;
    runtime::with_hook_guard(
        || {
            runtime::call_prev(
                self_ptr,
                || libc::fstatat(dirfd, pathname, statbuf, flags),
                |prev| {
                    let f: unsafe extern "C" fn(
                        c_int,
                        *const c_char,
                        *mut libc::stat,
                        c_int,
                    ) -> c_int = std::mem::transmute(prev);
                    f(dirfd, pathname, statbuf, flags)
                },
            )
        },
        |hub| {
            hub.increment_stat_calls();
            if should_bypass_system_writer_query(hub, "fstatat") {
                return runtime::call_prev(
                    self_ptr,
                    || libc::fstatat(dirfd, pathname, statbuf, flags),
                    |prev| {
                        let f: unsafe extern "C" fn(
                            c_int,
                            *const c_char,
                            *mut libc::stat,
                            c_int,
                        ) -> c_int = std::mem::transmute(prev);
                        f(dirfd, pathname, statbuf, flags)
                    },
                );
            }
            runtime::with_redirected_path(hub, "fstatat", pathname, |final_path| {
                runtime::call_prev(
                    self_ptr,
                    || libc::fstatat(dirfd, final_path, statbuf, flags),
                    |prev| {
                        let f: unsafe extern "C" fn(
                            c_int,
                            *const c_char,
                            *mut libc::stat,
                            c_int,
                        ) -> c_int = std::mem::transmute(prev);
                        f(dirfd, final_path, statbuf, flags)
                    },
                )
            })
        },
    )
}

pub unsafe extern "C" fn hooked_access(pathname: *const c_char, mode: c_int) -> c_int {
    let self_ptr = hooked_access as *mut c_void;
    runtime::with_hook_guard(
        || {
            runtime::call_prev(
                self_ptr,
                || libc::access(pathname, mode),
                |prev| {
                    let f: unsafe extern "C" fn(*const c_char, c_int) -> c_int =
                        std::mem::transmute(prev);
                    f(pathname, mode)
                },
            )
        },
        |hub| {
            hub.increment_access_calls();
            if should_bypass_system_writer_query(hub, "access") {
                return runtime::call_prev(
                    self_ptr,
                    || libc::access(pathname, mode),
                    |prev| {
                        let f: unsafe extern "C" fn(*const c_char, c_int) -> c_int =
                            std::mem::transmute(prev);
                        f(pathname, mode)
                    },
                );
            }
            runtime::with_redirected_path(hub, "access", pathname, |final_path| {
                runtime::call_prev(
                    self_ptr,
                    || libc::access(final_path, mode),
                    |prev| {
                        let f: unsafe extern "C" fn(*const c_char, c_int) -> c_int =
                            std::mem::transmute(prev);
                        f(final_path, mode)
                    },
                )
            })
        },
    )
}

pub unsafe extern "C" fn hooked_faccessat(
    dirfd: c_int,
    pathname: *const c_char,
    mode: c_int,
    flags: c_int,
) -> c_int {
    let self_ptr = hooked_faccessat as *mut c_void;
    runtime::with_hook_guard(
        || {
            runtime::call_prev(
                self_ptr,
                || libc::faccessat(dirfd, pathname, mode, flags),
                |prev| {
                    let f: unsafe extern "C" fn(c_int, *const c_char, c_int, c_int) -> c_int =
                        std::mem::transmute(prev);
                    f(dirfd, pathname, mode, flags)
                },
            )
        },
        |hub| {
            hub.increment_access_calls();
            if should_bypass_system_writer_query(hub, "faccessat") {
                return runtime::call_prev(
                    self_ptr,
                    || libc::faccessat(dirfd, pathname, mode, flags),
                    |prev| {
                        let f: unsafe extern "C" fn(c_int, *const c_char, c_int, c_int) -> c_int =
                            std::mem::transmute(prev);
                        f(dirfd, pathname, mode, flags)
                    },
                );
            }
            runtime::with_redirected_path(hub, "faccessat", pathname, |final_path| {
                runtime::call_prev(
                    self_ptr,
                    || libc::faccessat(dirfd, final_path, mode, flags),
                    |prev| {
                        let f: unsafe extern "C" fn(c_int, *const c_char, c_int, c_int) -> c_int =
                            std::mem::transmute(prev);
                        f(dirfd, final_path, mode, flags)
                    },
                )
            })
        },
    )
}

pub unsafe extern "C" fn hooked_statx(
    dirfd: c_int,
    pathname: *const c_char,
    flags: c_int,
    mask: c_uint,
    statxbuf: *mut libc::statx,
) -> c_int {
    let self_ptr = hooked_statx as *mut c_void;
    runtime::with_hook_guard(
        || {
            runtime::call_prev(
                self_ptr,
                || libc::statx(dirfd, pathname, flags, mask, statxbuf),
                |prev| {
                    let f: unsafe extern "C" fn(
                        c_int,
                        *const c_char,
                        c_int,
                        c_uint,
                        *mut libc::statx,
                    ) -> c_int = std::mem::transmute(prev);
                    f(dirfd, pathname, flags, mask, statxbuf)
                },
            )
        },
        |hub| {
            hub.increment_stat_calls();
            if should_bypass_system_writer_query(hub, "statx") {
                return runtime::call_prev(
                    self_ptr,
                    || libc::statx(dirfd, pathname, flags, mask, statxbuf),
                    |prev| {
                        let f: unsafe extern "C" fn(
                            c_int,
                            *const c_char,
                            c_int,
                            c_uint,
                            *mut libc::statx,
                        ) -> c_int = std::mem::transmute(prev);
                        f(dirfd, pathname, flags, mask, statxbuf)
                    },
                );
            }
            runtime::with_redirected_path(hub, "statx", pathname, |final_path| {
                runtime::call_prev(
                    self_ptr,
                    || libc::statx(dirfd, final_path, flags, mask, statxbuf),
                    |prev| {
                        let f: unsafe extern "C" fn(
                            c_int,
                            *const c_char,
                            c_int,
                            c_uint,
                            *mut libc::statx,
                        ) -> c_int = std::mem::transmute(prev);
                        f(dirfd, final_path, flags, mask, statxbuf)
                    },
                )
            })
        },
    )
}

pub unsafe extern "C" fn hooked_opendir(name: *const c_char) -> *mut libc::DIR {
    let self_ptr = hooked_opendir as *mut c_void;
    runtime::with_hook_guard(
        || {
            runtime::call_prev(
                self_ptr,
                || libc::opendir(name),
                |prev| {
                    let f: unsafe extern "C" fn(*const c_char) -> *mut libc::DIR =
                        std::mem::transmute(prev);
                    f(name)
                },
            )
        },
        |hub| {
            hub.increment_opendir_calls();
            if should_bypass_system_writer_query(hub, "opendir") {
                return runtime::call_prev(
                    self_ptr,
                    || libc::opendir(name),
                    |prev| {
                        let f: unsafe extern "C" fn(*const c_char) -> *mut libc::DIR =
                            std::mem::transmute(prev);
                        f(name)
                    },
                );
            }
            runtime::with_redirected_path(hub, "opendir", name, |final_path| {
                runtime::call_prev(
                    self_ptr,
                    || libc::opendir(final_path),
                    |prev| {
                        let f: unsafe extern "C" fn(*const c_char) -> *mut libc::DIR =
                            std::mem::transmute(prev);
                        f(final_path)
                    },
                )
            })
        },
    )
}

pub unsafe extern "C" fn hooked_readlink(
    pathname: *const c_char,
    buf: *mut c_char,
    bufsiz: usize,
) -> isize {
    let self_ptr = hooked_readlink as *mut c_void;
    runtime::with_hook_guard(
        || {
            runtime::call_prev(
                self_ptr,
                || libc::readlink(pathname, buf, bufsiz),
                |prev| {
                    let f: unsafe extern "C" fn(*const c_char, *mut c_char, usize) -> isize =
                        std::mem::transmute(prev);
                    f(pathname, buf, bufsiz)
                },
            )
        },
        |hub| {
            hub.increment_readlink_calls();
            if should_bypass_system_writer_query(hub, "readlink") {
                return runtime::call_prev(
                    self_ptr,
                    || libc::readlink(pathname, buf, bufsiz),
                    |prev| {
                        let f: unsafe extern "C" fn(*const c_char, *mut c_char, usize) -> isize =
                            std::mem::transmute(prev);
                        f(pathname, buf, bufsiz)
                    },
                );
            }
            runtime::with_redirected_path(hub, "readlink", pathname, |final_path| {
                runtime::call_prev(
                    self_ptr,
                    || libc::readlink(final_path, buf, bufsiz),
                    |prev| {
                        let f: unsafe extern "C" fn(*const c_char, *mut c_char, usize) -> isize =
                            std::mem::transmute(prev);
                        f(final_path, buf, bufsiz)
                    },
                )
            })
        },
    )
}
