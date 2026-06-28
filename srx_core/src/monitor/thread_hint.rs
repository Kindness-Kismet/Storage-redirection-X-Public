use super::SourceIdentity;

// 通过当前线程名推断 shared_uid 进程内的具体组件
pub fn infer_component_from_thread_name() -> Option<SourceIdentity> {
    let mut name_buf = [0u8; 17];
    let ret = unsafe {
        libc::prctl(
            libc::PR_GET_NAME,
            name_buf.as_mut_ptr() as *mut libc::c_void,
            0,
            0,
            0,
        )
    };
    if ret != 0 {
        return None;
    }

    let end = name_buf
        .iter()
        .position(|&b| b == 0)
        .unwrap_or(name_buf.len());
    let thread_name = std::str::from_utf8(&name_buf[..end]).ok()?;

    if thread_name.starts_with("MtpServer") {
        return Some(SourceIdentity::new(
            "com.android.mtp".to_string(),
            "thread_name",
            "high",
        ));
    }

    None
}
