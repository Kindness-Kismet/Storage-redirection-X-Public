#[repr(u32)]
pub enum HookProfile {
    Full = 1 << 0,
    Monitor = 1 << 1,
    SystemWriter = 1 << 2,
}

pub struct HookEntry {
    pub symbol: &'static str,
    pub new_func: *mut std::ffi::c_void,
    pub is_optional: bool,
    pub profile_mask: u32,
}

pub fn is_hook_enabled(profile_mask: u32, entry_mask: u32) -> bool {
    (entry_mask & profile_mask) != 0
}

pub fn count_hooks_for_profile(profile_mask: u32) -> usize {
    build_hook_entries()
        .into_iter()
        .filter(|entry| is_hook_enabled(profile_mask, entry.profile_mask))
        .count()
}

pub fn build_hook_entries() -> Vec<HookEntry> {
    vec![
        HookEntry {
            symbol: "open",
            new_func: super::ops::open::hooked_open as *mut _,
            is_optional: false,
            profile_mask: HookProfile::Full as u32
                | HookProfile::Monitor as u32
                | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "open64",
            new_func: super::ops::open::hooked_open as *mut _,
            is_optional: true,
            profile_mask: HookProfile::Full as u32
                | HookProfile::Monitor as u32
                | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "__open_2",
            new_func: super::ops::open::hooked_open as *mut _,
            is_optional: true,
            profile_mask: HookProfile::Full as u32
                | HookProfile::Monitor as u32
                | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "openat",
            new_func: super::ops::open::hooked_openat as *mut _,
            is_optional: false,
            profile_mask: HookProfile::Full as u32
                | HookProfile::Monitor as u32
                | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "openat2",
            new_func: super::ops::open::hooked_openat2 as *mut _,
            is_optional: true,
            profile_mask: HookProfile::Full as u32
                | HookProfile::Monitor as u32
                | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "openat64",
            new_func: super::ops::open::hooked_openat as *mut _,
            is_optional: true,
            profile_mask: HookProfile::Full as u32
                | HookProfile::Monitor as u32
                | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "__openat_2",
            new_func: super::ops::open::hooked_openat as *mut _,
            is_optional: true,
            profile_mask: HookProfile::Full as u32
                | HookProfile::Monitor as u32
                | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "creat",
            new_func: super::ops::open::hooked_creat as *mut _,
            is_optional: true,
            profile_mask: HookProfile::Full as u32
                | HookProfile::Monitor as u32
                | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "stat",
            new_func: super::ops::query::hooked_stat as *mut _,
            is_optional: false,
            profile_mask: HookProfile::Full as u32 | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "lstat",
            new_func: super::ops::query::hooked_lstat as *mut _,
            is_optional: false,
            profile_mask: HookProfile::Full as u32 | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "fstatat",
            new_func: super::ops::query::hooked_fstatat as *mut _,
            is_optional: true,
            profile_mask: HookProfile::Full as u32 | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "fstatat64",
            new_func: super::ops::query::hooked_fstatat as *mut _,
            is_optional: true,
            profile_mask: HookProfile::Full as u32 | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "__fstatat64",
            new_func: super::ops::query::hooked_fstatat as *mut _,
            is_optional: true,
            profile_mask: HookProfile::Full as u32 | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "newfstatat",
            new_func: super::ops::query::hooked_fstatat as *mut _,
            is_optional: true,
            profile_mask: HookProfile::Full as u32 | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "access",
            new_func: super::ops::query::hooked_access as *mut _,
            is_optional: false,
            profile_mask: HookProfile::Full as u32 | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "faccessat",
            new_func: super::ops::query::hooked_faccessat as *mut _,
            is_optional: true,
            profile_mask: HookProfile::Full as u32 | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "faccessat2",
            new_func: super::ops::query::hooked_faccessat as *mut _,
            is_optional: true,
            profile_mask: HookProfile::Full as u32 | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "statx",
            new_func: super::ops::query::hooked_statx as *mut _,
            is_optional: true,
            profile_mask: HookProfile::Full as u32 | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "mkdir",
            new_func: super::ops::mutation::hooked_mkdir as *mut _,
            is_optional: false,
            profile_mask: HookProfile::Full as u32
                | HookProfile::Monitor as u32
                | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "mkdirat",
            new_func: super::ops::mutation::hooked_mkdirat as *mut _,
            is_optional: false,
            profile_mask: HookProfile::Full as u32
                | HookProfile::Monitor as u32
                | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "mknod",
            new_func: super::ops::mutation::hooked_mknod as *mut _,
            is_optional: true,
            profile_mask: HookProfile::Full as u32
                | HookProfile::Monitor as u32
                | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "mknodat",
            new_func: super::ops::mutation::hooked_mknodat as *mut _,
            is_optional: true,
            profile_mask: HookProfile::Full as u32
                | HookProfile::Monitor as u32
                | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "unlink",
            new_func: super::ops::mutation::hooked_unlink as *mut _,
            is_optional: false,
            profile_mask: HookProfile::Full as u32 | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "unlinkat",
            new_func: super::ops::mutation::hooked_unlinkat as *mut _,
            is_optional: false,
            profile_mask: HookProfile::Full as u32 | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "ftruncate",
            new_func: super::ops::mutation::hooked_ftruncate as *mut _,
            is_optional: true,
            profile_mask: HookProfile::Full as u32 | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "ftruncate64",
            new_func: super::ops::mutation::hooked_ftruncate64 as *mut _,
            is_optional: true,
            profile_mask: HookProfile::Full as u32 | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "futimens",
            new_func: super::ops::mutation::hooked_futimens as *mut _,
            is_optional: true,
            profile_mask: HookProfile::Full as u32 | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "rename",
            new_func: super::ops::rename::hooked_rename as *mut _,
            is_optional: false,
            profile_mask: HookProfile::Full as u32 | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "renameat2",
            new_func: super::ops::rename::hooked_renameat2 as *mut _,
            is_optional: true,
            profile_mask: HookProfile::Full as u32 | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "opendir",
            new_func: super::ops::query::hooked_opendir as *mut _,
            is_optional: false,
            profile_mask: HookProfile::Full as u32 | HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "readlink",
            new_func: super::ops::query::hooked_readlink as *mut _,
            is_optional: false,
            profile_mask: HookProfile::Full as u32,
        },
        HookEntry {
            symbol: "read",
            new_func: super::ops::read::hooked_read as *mut _,
            is_optional: false,
            profile_mask: HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "_ZN7android14IPCThreadState20clearCallingIdentityEv",
            new_func: super::ops::binder::hooked_clear_calling_identity as *mut _,
            is_optional: true,
            profile_mask: HookProfile::SystemWriter as u32,
        },
        HookEntry {
            symbol: "_ZN7android14IPCThreadState21restoreCallingIdentityEl",
            new_func: super::ops::binder::hooked_restore_calling_identity as *mut _,
            is_optional: true,
            profile_mask: HookProfile::SystemWriter as u32,
        },
    ]
}
