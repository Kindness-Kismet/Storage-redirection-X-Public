use core::ffi::c_void;

// 兼容内置链接器对 __clear_cache 的依赖
#[unsafe(no_mangle)]
pub unsafe extern "C" fn __clear_cache(begin: *mut c_void, end: *mut c_void) {
    if begin.is_null() || end.is_null() {
        return;
    }
    let begin_addr = begin as usize;
    let end_addr = end as usize;
    if begin_addr >= end_addr {
        return;
    }

    #[cfg(target_arch = "aarch64")]
    {
        use core::arch::asm;
        const CACHE_LINE: usize = 64;
        let mut addr = begin_addr & !(CACHE_LINE - 1);
        while addr < end_addr {
            asm!("dc cvau, {}", in(reg) addr, options(nostack, preserves_flags));
            addr += CACHE_LINE;
        }
        asm!("dsb ish", options(nostack, preserves_flags));
        addr = begin_addr & !(CACHE_LINE - 1);
        while addr < end_addr {
            asm!("ic ivau, {}", in(reg) addr, options(nostack, preserves_flags));
            addr += CACHE_LINE;
        }
        asm!("dsb ish", options(nostack, preserves_flags));
        asm!("isb", options(nostack, preserves_flags));
    }

    #[cfg(target_arch = "arm")]
    {
        let _ = libc::syscall(libc::SYS_cacheflush, begin, end, 0);
    }

    #[cfg(not(any(target_arch = "aarch64", target_arch = "arm")))]
    {
        let _ = (begin, end);
    }
}
