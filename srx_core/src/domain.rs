#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PathMapping {
    pub request_path: String,
    pub final_path: String,
}

impl PathMapping {
    pub fn new(request_path: String, final_path: String) -> Self {
        Self {
            request_path,
            final_path,
        }
    }
}
