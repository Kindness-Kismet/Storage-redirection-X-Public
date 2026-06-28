use super::android_api_level;
use libc::{c_int, c_void};
use once_cell::sync::OnceCell;
use std::ffi::CStr;

const LIBLZMA_PATHS_64: [&CStr; 4] = [
    c"/apex/com.android.art/lib64/liblzma.so",
    c"/apex/com.android.runtime/lib64/liblzma.so",
    c"/system/lib64/liblzma.so",
    c"liblzma.so",
];
const LIBLZMA_PATHS_32: [&CStr; 4] = [
    c"/apex/com.android.art/lib/liblzma.so",
    c"/apex/com.android.runtime/lib/liblzma.so",
    c"/system/lib/liblzma.so",
    c"liblzma.so",
];
const LIBLZMA_SYM_CRCGEN: &CStr = c"CrcGenerateTable";
const LIBLZMA_SYM_CRC64GEN: &CStr = c"Crc64GenerateTable";
const LIBLZMA_SYM_CONSTRUCT: &CStr = c"XzUnpacker_Construct";
const LIBLZMA_SYM_IS_FINISHED: &CStr = c"XzUnpacker_IsStreamWasFinished";
const LIBLZMA_SYM_FREE: &CStr = c"XzUnpacker_Free";
const LIBLZMA_SYM_CODE: &CStr = c"XzUnpacker_Code";

#[repr(C)]
struct ISzAlloc {
    alloc: extern "C" fn(*const ISzAlloc, usize) -> *mut c_void,
    free: extern "C" fn(*const ISzAlloc, *mut c_void),
}

#[repr(C)]
#[derive(Clone, Copy, PartialEq, Eq)]
#[allow(dead_code)]
enum ECoderStatus {
    NotSpecified = 0,
    FinishedWithMark = 1,
    NotFinished = 2,
    NeedsMoreInput = 3,
}

#[repr(C)]
#[derive(Clone, Copy)]
enum ECoderFinishMode {
    Any = 0,
}

type LzmaCrcGenerateTable = extern "C" fn();
type LzmaCrc64GenerateTable = extern "C" fn();
type LzmaConstruct = extern "C" fn(*mut c_void, *const ISzAlloc);
type LzmaIsFinished = extern "C" fn(*const c_void) -> c_int;
type LzmaFree = extern "C" fn(*mut c_void);
type LzmaCode = extern "C" fn(
    *mut c_void,
    *mut u8,
    *mut usize,
    *const u8,
    *mut usize,
    ECoderFinishMode,
    *mut ECoderStatus,
) -> c_int;
type LzmaCodeQ = extern "C" fn(
    *mut c_void,
    *mut u8,
    *mut usize,
    *const u8,
    *mut usize,
    c_int,
    ECoderFinishMode,
    *mut ECoderStatus,
) -> c_int;

struct LzmaApi {
    construct: LzmaConstruct,
    is_finished: LzmaIsFinished,
    free: LzmaFree,
    code: LzmaCode,
    code_q: LzmaCodeQ,
}

pub fn decompress(src: &[u8]) -> Option<Vec<u8>> {
    let api = lzma_api()?;
    let alloc = ISzAlloc {
        alloc: lzma_alloc,
        free: lzma_free_alloc,
    };
    let mut state = [0u64; 512];
    (api.construct)(state.as_mut_ptr() as *mut c_void, &alloc);
    let mut dst = Vec::<u8>::new();
    let mut total_out = 0usize;
    let mut src_offset = 0usize;
    let api_level = android_api_level();
    loop {
        // 至少保留 64KiB 写空间
        if dst.len() < total_out + 0x10000 {
            dst.resize(total_out + 0x10000, 0);
        }
        let mut dst_room = dst.len() - total_out;
        let mut src_room = match src.len().checked_sub(src_offset) {
            Some(r) => r,
            None => {
                (api.free)(state.as_mut_ptr() as *mut c_void);
                return None;
            }
        };
        let mut status = ECoderStatus::NotSpecified;
        // SAFETY: 偏移在 dst/src 范围内；指针由 ptr::add 计算
        let result = unsafe {
            if api_level >= 29 {
                (api.code_q)(
                    state.as_mut_ptr() as *mut c_void,
                    dst.as_mut_ptr().add(total_out),
                    &mut dst_room,
                    src.as_ptr().add(src_offset),
                    &mut src_room,
                    1,
                    ECoderFinishMode::Any,
                    &mut status,
                )
            } else {
                (api.code)(
                    state.as_mut_ptr() as *mut c_void,
                    dst.as_mut_ptr().add(total_out),
                    &mut dst_room,
                    src.as_ptr().add(src_offset),
                    &mut src_room,
                    ECoderFinishMode::Any,
                    &mut status,
                )
            }
        };
        if result != 0 {
            (api.free)(state.as_mut_ptr() as *mut c_void);
            return None;
        }
        total_out += dst_room;
        src_offset += src_room;
        if status != ECoderStatus::NotFinished {
            break;
        }
        if dst_room == 0 && src_room == 0 {
            // 防止死循环：解码器既不消费输入也不输出
            (api.free)(state.as_mut_ptr() as *mut c_void);
            return None;
        }
    }
    let finished = (api.is_finished)(state.as_mut_ptr() as *mut c_void) != 0;
    (api.free)(state.as_mut_ptr() as *mut c_void);
    if !finished {
        return None;
    }
    dst.truncate(total_out);
    Some(dst)
}

extern "C" fn lzma_alloc(_p: *const ISzAlloc, size: usize) -> *mut c_void {
    unsafe { libc::malloc(size) }
}

extern "C" fn lzma_free_alloc(_p: *const ISzAlloc, address: *mut c_void) {
    unsafe { libc::free(address) };
}

fn lzma_api() -> Option<&'static LzmaApi> {
    static API: OnceCell<Option<LzmaApi>> = OnceCell::new();
    API.get_or_init(load_lzma_api).as_ref()
}

fn load_lzma_api() -> Option<LzmaApi> {
    let paths = if cfg!(target_pointer_width = "64") {
        &LIBLZMA_PATHS_64
    } else {
        &LIBLZMA_PATHS_32
    };
    for path in paths {
        if let Some(api) = load_lzma_api_from(path) {
            return Some(api);
        }
    }
    None
}

fn load_lzma_api_from(path: &CStr) -> Option<LzmaApi> {
    let handle = unsafe { libc::dlopen(path.as_ptr(), libc::RTLD_NOW) };
    if handle.is_null() {
        return None;
    }
    let crc = unsafe { libc::dlsym(handle, LIBLZMA_SYM_CRCGEN.as_ptr()) };
    let crc64 = unsafe { libc::dlsym(handle, LIBLZMA_SYM_CRC64GEN.as_ptr()) };
    let construct = unsafe { libc::dlsym(handle, LIBLZMA_SYM_CONSTRUCT.as_ptr()) };
    let is_finished = unsafe { libc::dlsym(handle, LIBLZMA_SYM_IS_FINISHED.as_ptr()) };
    let free = unsafe { libc::dlsym(handle, LIBLZMA_SYM_FREE.as_ptr()) };
    let code = unsafe { libc::dlsym(handle, LIBLZMA_SYM_CODE.as_ptr()) };
    if crc.is_null()
        || construct.is_null()
        || is_finished.is_null()
        || free.is_null()
        || code.is_null()
    {
        unsafe { libc::dlclose(handle) };
        return None;
    }
    let crc: LzmaCrcGenerateTable = unsafe { core::mem::transmute(crc) };
    crc();
    if !crc64.is_null() {
        let crc64: LzmaCrc64GenerateTable = unsafe { core::mem::transmute(crc64) };
        crc64();
    }
    Some(LzmaApi {
        construct: unsafe { core::mem::transmute::<*mut c_void, LzmaConstruct>(construct) },
        is_finished: unsafe { core::mem::transmute::<*mut c_void, LzmaIsFinished>(is_finished) },
        free: unsafe { core::mem::transmute::<*mut c_void, LzmaFree>(free) },
        code: unsafe { core::mem::transmute::<*mut c_void, LzmaCode>(code) },
        code_q: unsafe { core::mem::transmute::<*mut c_void, LzmaCodeQ>(code) },
    })
}
