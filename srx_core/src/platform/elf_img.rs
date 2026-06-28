// 解析磁盘上的 libart.so，给 LSPlant 提供 ART 符号解析
// 内存解析在 Android 14+ 上不可靠（relro 段会被 mprotect 为 ---p），统一走文件路径

#![allow(dead_code)]
#![allow(non_camel_case_types)]
#![allow(non_snake_case)]

use super::gnu_debugdata;
use std::ffi::c_void;
use std::mem::size_of;
use std::path::PathBuf;

const SHT_PROGBITS: u32 = 1;
const SHT_SYMTAB: u32 = 2;
const SHT_STRTAB: u32 = 3;
const SHT_DYNSYM: u32 = 11;
const SHN_UNDEF: u16 = 0;
const SHN_LORESERVE: u16 = 0xff00;
const SHN_HIRESERVE: u16 = 0xffff;

#[repr(C)]
#[derive(Clone, Copy)]
struct Elf64Ehdr {
    e_ident: [u8; 16],
    e_type: u16,
    e_machine: u16,
    e_version: u32,
    e_entry: u64,
    e_phoff: u64,
    e_shoff: u64,
    e_flags: u32,
    e_ehsize: u16,
    e_phentsize: u16,
    e_phnum: u16,
    e_shentsize: u16,
    e_shnum: u16,
    e_shstrndx: u16,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct Elf64Shdr {
    sh_name: u32,
    sh_type: u32,
    sh_flags: u64,
    sh_addr: u64,
    sh_offset: u64,
    sh_size: u64,
    sh_link: u32,
    sh_info: u32,
    sh_addralign: u64,
    sh_entsize: u64,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct Elf64Sym {
    st_name: u32,
    st_info: u8,
    st_other: u8,
    st_shndx: u16,
    st_value: u64,
    st_size: u64,
}

pub struct ElfImg {
    load_bias: usize,
    image: Vec<u8>,
}

impl ElfImg {
    // 按路径后缀匹配首个已加载模块；未命中或文件不可读返回 None
    pub fn load(path_suffix: &str) -> Option<Self> {
        let (load_bias, file_path) = find_module_load_bias(path_suffix)?;
        let image = match std::fs::read(&file_path) {
            Ok(b) => b,
            Err(e) => {
                log::warn!("elf image read failed path={} err={}", file_path, e);
                return None;
            }
        };
        if image.len() < size_of::<Elf64Ehdr>() {
            return None;
        }
        if image[..4] != [0x7f, b'E', b'L', b'F'] {
            return None;
        }
        Some(ElfImg { load_bias, image })
    }

    pub fn base(&self) -> usize {
        self.load_bias
    }

    // 精确匹配符号名；命中返回运行时函数地址，未命中返回 null
    pub fn find(&self, name: &str) -> *mut c_void {
        let bytes = name.as_bytes();
        self.find_by(|n| n == bytes)
    }

    // 前缀匹配；用于 LSPlant 的 PrefixResolver（mangled 名含参数包变体）
    pub fn find_prefix(&self, prefix: &str) -> *mut c_void {
        let bytes = prefix.as_bytes();
        self.find_by(|n| n.starts_with(bytes))
    }

    fn find_by<F: Fn(&[u8]) -> bool>(&self, matches: F) -> *mut c_void {
        let Some(ehdr) = parse_file_ehdr(&self.image) else {
            return std::ptr::null_mut();
        };
        if let Some(addr) = lookup_dynsym(&self.image, &ehdr, self.load_bias, &matches) {
            return addr as *mut c_void;
        }
        if let Some(addr) = lookup_image_symtab(&self.image, &ehdr, self.load_bias, &matches) {
            return addr as *mut c_void;
        }
        if let Some(addr) = lookup_debugdata_symtab(&self.image, &ehdr, self.load_bias, &matches) {
            return addr as *mut c_void;
        }
        std::ptr::null_mut()
    }
}

// /proc/self/maps：load_bias = mapping_addr - mapping_offset（典型 .so 首段 p_vaddr==p_offset==0）
// 即便 ELF 头那段被 relro 保护掉，其它段也能算出同一个 bias
fn find_module_load_bias(suffix: &str) -> Option<(usize, String)> {
    let content = std::fs::read_to_string("/proc/self/maps").ok()?;
    for line in content.lines() {
        let mut parts = line.split_whitespace();
        let addr_range = parts.next()?;
        let _perms = parts.next()?;
        let offset_str = parts.next()?;
        let _dev = parts.next()?;
        let _inode = parts.next()?;
        let Some(path) = parts.next() else {
            continue;
        };
        if !path.ends_with(suffix) {
            continue;
        }
        let dash = addr_range.find('-')?;
        let base = usize::from_str_radix(&addr_range[..dash], 16).ok()?;
        let offset = usize::from_str_radix(offset_str, 16).ok()?;
        let load_bias = base.checked_sub(offset)?;
        return Some((load_bias, resolve_map_path(path)));
    }
    None
}

fn resolve_map_path(path: &str) -> String {
    if !path.starts_with("/apex/") || path.contains("@") {
        return path.to_string();
    }
    let candidate = PathBuf::from(path);
    if candidate.exists() {
        return path.to_string();
    }
    path.replacen("/apex/", "/system/apex/", 1)
}

fn lookup_dynsym<F: Fn(&[u8]) -> bool>(
    image: &[u8],
    ehdr: &Elf64Ehdr,
    load_bias: usize,
    matches: &F,
) -> Option<usize> {
    for i in 0..ehdr.e_shnum as usize {
        let dynsym = section_header(image, ehdr, i)?;
        if dynsym.sh_type != SHT_DYNSYM || dynsym.sh_entsize as usize != size_of::<Elf64Sym>() {
            continue;
        }
        if dynsym.sh_link >= ehdr.e_shnum as u32 {
            continue;
        }
        let dynstr = section_header(image, ehdr, dynsym.sh_link as usize)?;
        if dynstr.sh_type != SHT_STRTAB {
            continue;
        }
        let strtab = section_bytes_by_header(image, &dynstr)?;
        let count = (dynsym.sh_size / dynsym.sh_entsize) as usize;
        for idx in 0..count {
            let off = dynsym.sh_offset as usize + idx * size_of::<Elf64Sym>();
            let sym = read_struct::<Elf64Sym>(image, off)?;
            if sym.st_name == 0 || sym.st_value == 0 || !is_export_symtab_symbol(sym.st_shndx) {
                continue;
            }
            let Some(name) = read_c_bytes(strtab, sym.st_name as usize) else {
                continue;
            };
            if matches(name) {
                return Some(load_bias + sym.st_value as usize);
            }
        }
    }
    None
}

fn lookup_image_symtab<F: Fn(&[u8]) -> bool>(
    image: &[u8],
    ehdr: &Elf64Ehdr,
    load_bias: usize,
    matches: &F,
) -> Option<usize> {
    let shstr = section_bytes(image, ehdr, ehdr.e_shstrndx as usize)?;
    for i in 0..ehdr.e_shnum as usize {
        let symtab = section_header(image, ehdr, i)?;
        if symtab.sh_type != SHT_SYMTAB || symtab.sh_entsize as usize != size_of::<Elf64Sym>() {
            continue;
        }
        if !section_name_is(shstr, symtab.sh_name, b".symtab") {
            continue;
        }
        if let Some(found) = lookup_symtab_section(image, ehdr, &symtab, load_bias, matches) {
            return Some(found);
        }
    }
    None
}

fn lookup_debugdata_symtab<F: Fn(&[u8]) -> bool>(
    image: &[u8],
    ehdr: &Elf64Ehdr,
    load_bias: usize,
    matches: &F,
) -> Option<usize> {
    let shstr = section_bytes(image, ehdr, ehdr.e_shstrndx as usize)?;
    for i in 0..ehdr.e_shnum as usize {
        let debugdata = section_header(image, ehdr, i)?;
        if debugdata.sh_type != SHT_PROGBITS
            || !section_name_is(shstr, debugdata.sh_name, b".gnu_debugdata")
        {
            continue;
        }
        let zipped = section_bytes_by_header(image, &debugdata)?;
        let unpacked = gnu_debugdata::decompress(zipped)?;
        let debug_ehdr = parse_file_ehdr(&unpacked)?;
        if let Some(found) = lookup_image_symtab(&unpacked, &debug_ehdr, load_bias, matches) {
            return Some(found);
        }
    }
    None
}

fn lookup_symtab_section<F: Fn(&[u8]) -> bool>(
    image: &[u8],
    ehdr: &Elf64Ehdr,
    symtab: &Elf64Shdr,
    load_bias: usize,
    matches: &F,
) -> Option<usize> {
    if symtab.sh_link >= ehdr.e_shnum as u32 {
        return None;
    }
    let strtab = section_bytes(image, ehdr, symtab.sh_link as usize)?;
    let count = (symtab.sh_size / symtab.sh_entsize) as usize;
    for idx in 0..count {
        let off = symtab.sh_offset as usize + idx * size_of::<Elf64Sym>();
        let sym = read_struct::<Elf64Sym>(image, off)?;
        if sym.st_name == 0 || sym.st_value == 0 || !is_export_symtab_symbol(sym.st_shndx) {
            continue;
        }
        let name = read_c_bytes(strtab, sym.st_name as usize)?;
        if matches(name) {
            return Some(load_bias + sym.st_value as usize);
        }
    }
    None
}

fn parse_file_ehdr(image: &[u8]) -> Option<Elf64Ehdr> {
    let ehdr = read_struct::<Elf64Ehdr>(image, 0)?;
    if ehdr.e_ident[..4] != [0x7f, b'E', b'L', b'F'] {
        return None;
    }
    Some(ehdr)
}

fn section_header(image: &[u8], ehdr: &Elf64Ehdr, idx: usize) -> Option<Elf64Shdr> {
    if idx >= ehdr.e_shnum as usize {
        return None;
    }
    let offset = ehdr.e_shoff as usize + idx * ehdr.e_shentsize as usize;
    read_struct::<Elf64Shdr>(image, offset)
}

fn section_bytes<'a>(image: &'a [u8], ehdr: &Elf64Ehdr, idx: usize) -> Option<&'a [u8]> {
    let sh = section_header(image, ehdr, idx)?;
    section_bytes_by_header(image, &sh)
}

fn section_bytes_by_header<'a>(image: &'a [u8], sh: &Elf64Shdr) -> Option<&'a [u8]> {
    let start = sh.sh_offset as usize;
    let end = start.checked_add(sh.sh_size as usize)?;
    image.get(start..end)
}

fn section_name_is(shstr: &[u8], name_off: u32, expected: &[u8]) -> bool {
    matches!(read_c_bytes(shstr, name_off as usize), Some(name) if name == expected)
}

fn read_struct<T: Copy>(image: &[u8], offset: usize) -> Option<T> {
    let end = offset.checked_add(size_of::<T>())?;
    if end > image.len() {
        return None;
    }
    let ptr = image[offset..end].as_ptr() as *const T;
    Some(unsafe { std::ptr::read_unaligned(ptr) })
}

fn read_c_bytes(buf: &[u8], offset: usize) -> Option<&[u8]> {
    if offset >= buf.len() {
        return None;
    }
    let tail = &buf[offset..];
    let end = tail.iter().position(|&b| b == 0)?;
    Some(&tail[..end])
}

fn is_export_symtab_symbol(shndx: u16) -> bool {
    shndx != SHN_UNDEF && !(SHN_LORESERVE..=SHN_HIRESERVE).contains(&shndx)
}
