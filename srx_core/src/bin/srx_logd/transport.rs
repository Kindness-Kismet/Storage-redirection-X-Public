// 抽象命名空间 Unix DGRAM socket：守护端绑定与客户端发送
use libc::{
    AF_UNIX, SO_RCVBUF, SOCK_CLOEXEC, SOCK_DGRAM, SOCK_NONBLOCK, SOL_SOCKET, bind, c_void, close,
    sendto, setsockopt, sockaddr, sockaddr_un, socket,
};
use std::fs;
use std::io;
use std::mem;

// 与模块侧 module_paths::LOGD_SOCKET_NAME_FILE 一致，由 post-fs-data 每次开机随机生成
const SOCKET_NAME_FILE: &str = "/dev/srx_config/.logd_socket";
const SOCKET_RECV_BUFFER_BYTES: libc::c_int = 512 * 1024;

pub(crate) fn bind_log_socket() -> io::Result<i32> {
    let (addr, len) = socket_addr()?;
    let fd = unsafe { socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0) };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    configure_socket_recv_buffer(fd);

    let ret = unsafe { bind(fd, &addr as *const _ as *const sockaddr, len) };
    if ret != 0 {
        let error = io::Error::last_os_error();
        unsafe {
            close(fd);
        }
        return Err(error);
    }
    Ok(fd)
}

fn configure_socket_recv_buffer(fd: i32) {
    let recv_buffer = SOCKET_RECV_BUFFER_BYTES;
    unsafe {
        let _ = setsockopt(
            fd,
            SOL_SOCKET,
            SO_RCVBUF,
            &recv_buffer as *const _ as *const _,
            mem::size_of_val(&recv_buffer) as libc::socklen_t,
        );
    }
}

// 读取本次开机的抽象名并构造地址；名字缺失时报错，不回落到可被探测的固定名字
fn socket_addr() -> io::Result<(sockaddr_un, libc::socklen_t)> {
    let text = fs::read_to_string(SOCKET_NAME_FILE)?;
    let name = text.trim().as_bytes();
    let mut addr: sockaddr_un = unsafe { mem::zeroed() };
    addr.sun_family = AF_UNIX as _;
    if name.is_empty() || name.len() + 1 > addr.sun_path.len() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "invalid log socket name",
        ));
    }
    for (index, byte) in name.iter().enumerate() {
        addr.sun_path[index + 1] = *byte as _;
    }
    Ok((addr, sockaddr_un_len(name.len() + 1)))
}

fn sockaddr_un_len(path_len: usize) -> libc::socklen_t {
    (mem::size_of::<libc::sa_family_t>() + path_len) as libc::socklen_t
}

pub(crate) struct ClientSocket {
    fd: i32,
    addr: sockaddr_un,
    addr_len: libc::socklen_t,
}

impl ClientSocket {
    pub(crate) fn new() -> Option<Self> {
        let (addr, addr_len) = socket_addr().ok()?;
        let fd = unsafe { socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0) };
        if fd < 0 {
            return None;
        }
        Some(Self { fd, addr, addr_len })
    }

    pub(crate) fn send(&self, level: char, tag: &str, message: &str) -> io::Result<()> {
        let message = sanitize_transport_field(message);
        if tag.is_empty() || message.is_empty() {
            return Ok(());
        }

        let packet = format!("{level}\t{tag}\t{message}");
        let ret = unsafe {
            sendto(
                self.fd,
                packet.as_ptr() as *const c_void,
                packet.len(),
                0,
                &self.addr as *const _ as *const sockaddr,
                self.addr_len,
            )
        };
        if ret < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }
}

impl Drop for ClientSocket {
    fn drop(&mut self) {
        unsafe {
            close(self.fd);
        }
    }
}

// 制表符是包分隔符，换行是行分隔符，正文出现时替换为空格
fn sanitize_transport_field(message: &str) -> String {
    if !message.contains(['\n', '\r', '\t']) {
        return message.to_string();
    }

    message
        .chars()
        .map(|ch| match ch {
            '\n' | '\r' | '\t' => ' ',
            _ => ch,
        })
        .collect()
}
