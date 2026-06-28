// Default Ignorable Code Point 检测：与 Linux casefold/NFDICF 行为对齐
// MediaProvider FUSE daemon 不会过滤这些字符，从而被用作包名探测绕过

pub fn has_default_ignorable(value: &[u8]) -> bool {
    // 所有 DI 码点 ≥ U+00ad，UTF-8 起始字节必 ≥ 0xc2；纯 ASCII 路径直接短路
    if !value.iter().any(|&b| b >= 0xc2) {
        return false;
    }
    let mut idx = 0;
    while let Some((ch, len)) = next_char(value, idx) {
        if is_default_ignorable(ch) {
            return true;
        }
        idx += len;
    }
    false
}

pub fn remove_default_ignorable(value: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(value.len());
    let mut idx = 0;
    while idx < value.len() {
        match next_char(value, idx) {
            Some((ch, len)) if is_default_ignorable(ch) => idx += len,
            Some((_, len)) => {
                out.extend_from_slice(&value[idx..idx + len]);
                idx += len;
            }
            None => {
                out.push(value[idx]);
                idx += 1;
            }
        }
    }
    out
}

fn next_char(bytes: &[u8], idx: usize) -> Option<(u32, usize)> {
    let first = *bytes.get(idx)?;
    if first < 0x80 {
        return Some((first as u32, 1));
    }
    let (needed, mut value) = if first & 0xe0 == 0xc0 {
        (2usize, (first & 0x1f) as u32)
    } else if first & 0xf0 == 0xe0 {
        (3, (first & 0x0f) as u32)
    } else if first & 0xf8 == 0xf0 {
        (4, (first & 0x07) as u32)
    } else {
        return None;
    };
    if idx + needed > bytes.len() {
        return None;
    }
    for offset in 1..needed {
        let b = bytes[idx + offset];
        if b & 0xc0 != 0x80 {
            return None;
        }
        value = (value << 6) | (b & 0x3f) as u32;
    }
    Some((value, needed))
}

// Unicode DerivedCoreProperties.txt 中标记 Default_Ignorable_Code_Point=Yes 的码点
fn is_default_ignorable(ch: u32) -> bool {
    matches!(ch,
        0x00ad | 0x034f | 0x061c
        | 0x115f..=0x1160 | 0x17b4..=0x17b5
        | 0x180b..=0x180e | 0x200b..=0x200f
        | 0x202a..=0x202e | 0x2060..=0x206f
        | 0x3164 | 0xfe00..=0xfe0f | 0xfeff
        | 0xffa0 | 0xfff0..=0xfff8
        | 0x1bca0..=0x1bca3 | 0x1d173..=0x1d17a
        | 0xe0000..=0xe0fff)
}
