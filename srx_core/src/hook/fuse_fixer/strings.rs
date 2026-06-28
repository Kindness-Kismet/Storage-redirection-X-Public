// std::string ABI 临时包装：从 libfuse_jni 自身的 dynsym 解析 assign / 析构符号
// libc++ basic_string 全零的 24 字节是合法 SSO 空串，构造无需调用 ctor

use super::image::FuseJniImage;
use std::os::raw::c_void;

const STD_ASSIGN_NDK: &[u8] =
    b"_ZNSt6__ndk112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEE6assignEPKcm";
const STD_ASSIGN_STD: &[u8] =
    b"_ZNSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEE6assignEPKcm";
const STD_DTOR_NDK: &[u8] =
    b"_ZNSt6__ndk112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEED1Ev";
const STD_DTOR_STD: &[u8] = b"_ZNSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEED1Ev";

type AssignFn = unsafe extern "C" fn(*mut c_void, *const u8, usize) -> *mut c_void;
type DtorFn = unsafe extern "C" fn(*mut c_void);

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Layout {
    // 经典布局：long 字段顺序 data/size/cap+is_long_bit；short 末字节 bit7=is_long
    Classic,
    // 替代布局（LLVM 17+ 切换）：long 字段顺序 cap+is_long_bit/size/data；short 首字节 bit0=is_long
    Alternate,
}

impl Layout {
    pub fn name(self) -> &'static str {
        match self {
            Self::Classic => "classic",
            Self::Alternate => "alternate",
        }
    }
}

#[repr(C, align(8))]
pub struct CxxString {
    storage: [u64; 3],
}

pub struct StringAbi {
    assign: AssignFn,
    dtor: DtorFn,
    layout: Layout,
}

impl StringAbi {
    pub fn resolve(image: &FuseJniImage) -> Option<Self> {
        let assign_addr = image
            .find_symbol(STD_ASSIGN_NDK)
            .or_else(|| image.find_symbol(STD_ASSIGN_STD))?;
        let dtor_addr = image
            .find_symbol(STD_DTOR_NDK)
            .or_else(|| image.find_symbol(STD_DTOR_STD))?;
        let assign: AssignFn = unsafe { std::mem::transmute::<usize, AssignFn>(assign_addr) };
        let dtor: DtorFn = unsafe { std::mem::transmute::<usize, DtorFn>(dtor_addr) };
        let layout = detect_layout(assign, dtor)?;
        Some(Self {
            assign,
            dtor,
            layout,
        })
    }

    pub fn layout(&self) -> Layout {
        self.layout
    }

    // 在栈上构造 std::string 镜像：零值 24 字节 = 空 SSO，再 assign 写入字节
    // 调用方必须显式 drop，否则可能泄露 heap
    pub unsafe fn construct(&self, bytes: &[u8]) -> CxxString {
        let mut s = CxxString { storage: [0; 3] };
        unsafe {
            (self.assign)(
                &mut s as *mut CxxString as *mut c_void,
                bytes.as_ptr(),
                bytes.len(),
            );
        }
        s
    }

    pub unsafe fn drop_string(&self, value: &mut CxxString) {
        unsafe {
            (self.dtor)(value as *mut CxxString as *mut c_void);
        }
    }
}

// 用一短一长两个探针 assign 后比对 storage 字节，反推 SSO/long 的存放方式
// 仅在 install 阶段调用一次，构造短串不会触发 heap 分配
fn detect_layout(assign: AssignFn, dtor: DtorFn) -> Option<Layout> {
    let mut short = CxxString { storage: [0; 3] };
    unsafe { assign(&mut short as *mut _ as *mut c_void, b"Z".as_ptr(), 1) };
    let bytes = short.storage.as_ptr() as *const u8;
    let first = unsafe { *bytes };
    let last = unsafe { *bytes.add(23) };
    unsafe { dtor(&mut short as *mut _ as *mut c_void) };

    if first == b'Z' && last == 1 {
        return Some(Layout::Classic);
    }
    if first == 2 && unsafe { *bytes.add(1) } == b'Z' {
        return Some(Layout::Alternate);
    }
    None
}

// 从 const std::string& 读取数据；只处理 long 模式（FUSE 完整路径必然 ≥ 23 字节）
// value 必须是 libc++ basic_string 的有效引用
pub unsafe fn read_cxx_string<'a>(value: *const CxxString, layout: Layout) -> &'a [u8] {
    if value.is_null() {
        return &[];
    }
    let storage = unsafe { &(*value).storage };
    let bytes = storage.as_ptr() as *const u8;
    match layout {
        Layout::Classic => {
            let last = unsafe { *bytes.add(23) };
            if last & 0x80 == 0 {
                let size = (last & 0x7f) as usize;
                if size >= 23 {
                    return &[];
                }
                return unsafe { std::slice::from_raw_parts(bytes, size) };
            }
            let data = storage[0] as *const u8;
            let size = storage[1] as usize;
            if data.is_null() || size > 0x10_0000 {
                return &[];
            }
            unsafe { std::slice::from_raw_parts(data, size) }
        }
        Layout::Alternate => {
            let first = unsafe { *bytes };
            if first & 0x1 == 0 {
                let size = (first >> 1) as usize;
                if size >= 23 {
                    return &[];
                }
                return unsafe { std::slice::from_raw_parts(bytes.add(1), size) };
            }
            let size = storage[1] as usize;
            let data = storage[2] as *const u8;
            if data.is_null() || size > 0x10_0000 {
                return &[];
            }
            unsafe { std::slice::from_raw_parts(data, size) }
        }
    }
}
