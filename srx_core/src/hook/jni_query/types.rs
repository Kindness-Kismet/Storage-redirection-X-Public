use jni_sys::{JNIEnv, jint, jlong, jobject, jstring};

pub(super) const CURSOR_WINDOW_CLASS: &str = "android/database/CursorWindow";
pub(super) const CURSOR_WINDOW_GET_STRING_NAME: &str = "nativeGetString";
pub(super) const CURSOR_WINDOW_GET_STRING_SIG: &str = "(JII)Ljava/lang/String;";

pub(super) const FILE_SCHEME_PREFIX: &str = "file://";
pub(super) const STORAGE_PREFIXES: [&str; 3] = ["/storage/emulated/", "/sdcard/", "/mnt/sdcard/"];
pub(super) const SAMPLE_LOG_INITIAL: u32 = 32;
pub(super) const SAMPLE_LOG_INTERVAL: u32 = 256;

pub(super) type CursorWindowGetStringFn =
    unsafe extern "C" fn(*mut JNIEnv, jobject, jlong, jint, jint) -> jstring;
