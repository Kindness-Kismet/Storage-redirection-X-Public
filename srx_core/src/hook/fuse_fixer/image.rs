// 从 /proc/self/maps 与 APK ZIP entry 抽取 libfuse_jni.so 的完整 ELF 镜像
// MediaProvider 把 libfuse_jni 内嵌在 APK 里（uncompressed entry），dlopen 仅映射代码段

use crate::platform::gnu_debugdata;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::mem::size_of;

const ZIP_LOCAL_HEADER_MAGIC: u32 = 0x04034b50;
const SHT_PROGBITS: u32 = 1;
const SHT_SYMTAB: u32 = 2;
const SHT_DYNSYM: u32 = 11;
const SHN_UNDEF: u16 = 0;
const SHN_LORESERVE: u16 = 0xff00;
const SHN_HIRESERVE: u16 = 0xffff;
const ELF_MAGIC: [u8; 4] = [0x7f, b'E', b'L', b'F'];

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
struct Elf64Phdr {
    p_type: u32,
    p_flags: u32,
    p_offset: u64,
    p_vaddr: u64,
    p_paddr: u64,
    p_filesz: u64,
    p_memsz: u64,
    p_align: u64,
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

const PT_LOAD: u32 = 1;

pub struct FuseJniImage {
    pub load_bias: usize,
    image: Vec<u8>,
}

impl FuseJniImage {
    pub fn locate() -> Option<Self> {
        let mappings = read_mappings()?;
        for mapping in &mappings {
            if mapping.path.ends_with("/libfuse_jni.so")
                && let Ok(image) = std::fs::read(&mapping.path)
                && image.starts_with(&ELF_MAGIC)
            {
                let bias = compute_bias_from_file(&mapping.path, &mappings)?;
                return Some(Self {
                    load_bias: bias,
                    image,
                });
            }
        }
        for mapping in &mappings {
            if !mapping.path.contains("MediaProvider") || !mapping.path.ends_with(".apk") {
                continue;
            }
            if let Some(img) = try_load_apk_entry(mapping, &mappings) {
                return Some(img);
            }
        }
        None
    }

    pub fn find_symbol(&self, name: &[u8]) -> Option<usize> {
        let ehdr = parse_ehdr(&self.image)?;
        if let Some(addr) = lookup_in_image(&self.image, &ehdr, self.load_bias, name, true) {
            return Some(addr);
        }
        if let Some(addr) = lookup_in_image(&self.image, &ehdr, self.load_bias, name, false) {
            return Some(addr);
        }
        let shstr = section_bytes(&self.image, &ehdr, ehdr.e_shstrndx as usize)?;
        for i in 0..ehdr.e_shnum as usize {
            let shdr = section_header(&self.image, &ehdr, i)?;
            if shdr.sh_type != SHT_PROGBITS
                || !section_name_is(shstr, shdr.sh_name, b".gnu_debugdata")
            {
                continue;
            }
            let zipped = section_bytes_by_header(&self.image, &shdr)?;
            let unpacked = gnu_debugdata::decompress(zipped)?;
            let debug_ehdr = parse_ehdr(&unpacked)?;
            if let Some(addr) = lookup_in_image(&unpacked, &debug_ehdr, self.load_bias, name, false)
            {
                return Some(addr);
            }
        }
        None
    }
}

struct Mapping {
    start: usize,
    offset: usize,
    path: String,
}

fn read_mappings() -> Option<Vec<Mapping>> {
    let content = std::fs::read_to_string("/proc/self/maps").ok()?;
    let mut out = Vec::new();
    for line in content.lines() {
        let mut parts = line.split_whitespace();
        let range = parts.next()?;
        let _perms = parts.next()?;
        let offset_str = parts.next()?;
        let _dev = parts.next()?;
        let _inode = parts.next()?;
        let Some(path) = parts.next() else {
            continue;
        };
        let dash = range.find('-')?;
        let start = usize::from_str_radix(&range[..dash], 16).ok()?;
        let _end = usize::from_str_radix(&range[dash + 1..], 16).ok()?;
        let offset = usize::from_str_radix(offset_str, 16).ok()?;
        out.push(Mapping {
            start,
            offset,
            path: path.to_string(),
        });
    }
    Some(out)
}

fn compute_bias_from_file(path: &str, mappings: &[Mapping]) -> Option<usize> {
    let first = mappings.iter().find(|m| m.path == path)?;
    first.start.checked_sub(first.offset)
}

fn try_load_apk_entry(mapping: &Mapping, mappings: &[Mapping]) -> Option<FuseJniImage> {
    let (entry_name, entry_size, entry_offset) = read_zip_entry_at(&mapping.path, mapping.offset)?;
    if !entry_name.ends_with("/libfuse_jni.so") {
        return None;
    }
    let mut file = File::open(&mapping.path).ok()?;
    file.seek(SeekFrom::Start(entry_offset as u64)).ok()?;
    let mut image = vec![0u8; entry_size];
    file.read_exact(&mut image).ok()?;
    if !image.starts_with(&ELF_MAGIC) {
        return None;
    }
    let ehdr = parse_ehdr(&image)?;
    let first_load = first_pt_load(&image, &ehdr)?;
    let bias = mappings
        .iter()
        .find(|m| {
            m.path == mapping.path
                && m.offset >= entry_offset
                && m.offset < entry_offset + entry_size
        })
        .and_then(|m| {
            let in_entry = m.offset.checked_sub(entry_offset)?;
            let bias = m.start.checked_sub(in_entry)?;
            bias.checked_sub(first_load.p_vaddr as usize)
        })?;
    Some(FuseJniImage {
        load_bias: bias,
        image,
    })
}

fn read_zip_entry_at(path: &str, map_offset: usize) -> Option<(String, usize, usize)> {
    let mut file = File::open(path).ok()?;
    let probe_start = map_offset.saturating_sub(65536);
    for probe in probe_start..map_offset.saturating_sub(29) {
        file.seek(SeekFrom::Start(probe as u64)).ok()?;
        let mut header = [0u8; 30];
        if file.read_exact(&mut header).is_err() {
            return None;
        }
        if u32_le(&header, 0) != ZIP_LOCAL_HEADER_MAGIC {
            continue;
        }
        let method = u16_le(&header, 8);
        let compressed = u32_le(&header, 18) as usize;
        let uncompressed = u32_le(&header, 22) as usize;
        let name_len = u16_le(&header, 26) as usize;
        let extra_len = u16_le(&header, 28) as usize;
        let data_offset = probe + 30 + name_len + extra_len;
        if data_offset != map_offset || name_len == 0 || name_len > 512 {
            continue;
        }
        let mut name = vec![0u8; name_len];
        file.read_exact(&mut name).ok()?;
        let entry_size = if method == 0 {
            uncompressed
        } else {
            compressed
        };
        if entry_size == 0 {
            return None;
        }
        return Some((String::from_utf8(name).ok()?, entry_size, data_offset));
    }
    None
}

fn lookup_in_image(
    image: &[u8],
    ehdr: &Elf64Ehdr,
    load_bias: usize,
    name: &[u8],
    dyn_only: bool,
) -> Option<usize> {
    let target_type = if dyn_only { SHT_DYNSYM } else { SHT_SYMTAB };
    for i in 0..ehdr.e_shnum as usize {
        let shdr = section_header(image, ehdr, i)?;
        if shdr.sh_type != target_type || shdr.sh_entsize as usize != size_of::<Elf64Sym>() {
            continue;
        }
        if shdr.sh_link >= ehdr.e_shnum as u32 {
            continue;
        }
        let strtab = section_bytes(image, ehdr, shdr.sh_link as usize)?;
        let count = (shdr.sh_size / shdr.sh_entsize) as usize;
        for idx in 0..count {
            let off = shdr.sh_offset as usize + idx * size_of::<Elf64Sym>();
            let sym = read_struct::<Elf64Sym>(image, off)?;
            if sym.st_name == 0 || sym.st_value == 0 || !is_export_symbol(sym.st_shndx) {
                continue;
            }
            let Some(sym_name) = read_c_bytes(strtab, sym.st_name as usize) else {
                continue;
            };
            if sym_name == name {
                return Some(load_bias + sym.st_value as usize);
            }
        }
    }
    None
}

fn parse_ehdr(image: &[u8]) -> Option<Elf64Ehdr> {
    let ehdr = read_struct::<Elf64Ehdr>(image, 0)?;
    if ehdr.e_ident[..4] != ELF_MAGIC {
        return None;
    }
    Some(ehdr)
}

fn first_pt_load(image: &[u8], ehdr: &Elf64Ehdr) -> Option<Elf64Phdr> {
    if ehdr.e_phentsize as usize != size_of::<Elf64Phdr>() {
        return None;
    }
    for i in 0..ehdr.e_phnum as usize {
        let off = ehdr.e_phoff as usize + i * size_of::<Elf64Phdr>();
        let phdr = read_struct::<Elf64Phdr>(image, off)?;
        if phdr.p_type == PT_LOAD {
            return Some(phdr);
        }
    }
    None
}

fn section_header(image: &[u8], ehdr: &Elf64Ehdr, idx: usize) -> Option<Elf64Shdr> {
    if idx >= ehdr.e_shnum as usize {
        return None;
    }
    let off = ehdr.e_shoff as usize + idx * ehdr.e_shentsize as usize;
    read_struct::<Elf64Shdr>(image, off)
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

fn is_export_symbol(shndx: u16) -> bool {
    shndx != SHN_UNDEF && !(SHN_LORESERVE..=SHN_HIRESERVE).contains(&shndx)
}

fn u16_le(b: &[u8], o: usize) -> u16 {
    u16::from_le_bytes([b[o], b[o + 1]])
}

fn u32_le(b: &[u8], o: usize) -> u32 {
    u32::from_le_bytes([b[o], b[o + 1], b[o + 2], b[o + 3]])
}
