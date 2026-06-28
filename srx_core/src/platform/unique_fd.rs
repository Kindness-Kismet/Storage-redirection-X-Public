use libc::close;

#[derive(Debug)]
pub struct UniqueFd {
    fd: i32,
}

impl UniqueFd {
    pub fn new(fd: i32) -> Self {
        Self { fd }
    }

    pub fn get(&self) -> i32 {
        self.fd
    }

    pub fn reset(&mut self) {
        if self.fd >= 0 {
            unsafe {
                close(self.fd);
            }
        }
        self.fd = -1;
    }
}

impl Drop for UniqueFd {
    fn drop(&mut self) {
        self.reset();
    }
}
