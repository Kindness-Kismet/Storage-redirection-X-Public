use crate::platform::paths;
use crate::redirect::policy;
use crate::zygisk::jni;
use jni_sys::{JNIEnv, jclass, jint, jmethodID, jobject, jobjectArray, jstring, jvalue};
use once_cell::sync::Lazy;
use std::collections::{HashMap, VecDeque};
use std::ffi::CString;
use std::sync::Mutex;

const DOWNLOADS_URI: &str = "content://downloads/all_downloads";
const STORAGE_PREFIX: &str = "/storage/emulated/";
const DOWNLOAD_SEGMENT: &str = "/Download/";
const FILE_SCHEME_PREFIX: &str = "file://";
const CACHE_TTL_MS: i64 = 15_000;
const CACHE_CAPACITY: usize = 128;

struct CacheEntry {
    package_name: String,
    expires_at_ms: i64,
}

struct DownloadOwnerCache {
    values: HashMap<String, CacheEntry>,
    order: VecDeque<String>,
}

impl DownloadOwnerCache {
    fn new() -> Self {
        Self {
            values: HashMap::new(),
            order: VecDeque::new(),
        }
    }

    fn get(&mut self, key: &str, now_ms: i64) -> Option<Option<String>> {
        let entry = self.values.get(key)?;

        if entry.expires_at_ms < now_ms {
            self.values.remove(key);
            return None;
        }

        if entry.package_name.is_empty() {
            return Some(None);
        }
        Some(Some(entry.package_name.clone()))
    }

    fn put(&mut self, key: String, package_name: Option<String>, now_ms: i64) {
        if !self.values.contains_key(&key) {
            self.order.push_back(key.clone());
        }

        self.values.insert(
            key,
            CacheEntry {
                package_name: package_name.unwrap_or_default(),
                expires_at_ms: now_ms + CACHE_TTL_MS,
            },
        );

        while self.order.len() > CACHE_CAPACITY {
            if let Some(expired_key) = self.order.pop_front() {
                self.values.remove(&expired_key);
            }
        }
    }
}

static DOWNLOAD_OWNER_CACHE: Lazy<Mutex<DownloadOwnerCache>> =
    Lazy::new(|| Mutex::new(DownloadOwnerCache::new()));

// 按下载目标路径反查发起下载的应用包名
pub fn infer_download_owner_package_by_path(normalized_path: &str) -> Option<String> {
    if !should_lookup_download_owner(normalized_path) {
        return None;
    }

    let now_ms = paths::monotonic_ms();
    let candidates = build_lookup_candidates(normalized_path);
    for candidate in candidates {
        if let Some(cached) = DOWNLOAD_OWNER_CACHE
            .lock()
            .unwrap_or_else(|err| err.into_inner())
            .get(&candidate, now_ms)
        {
            if cached.is_some() {
                return cached;
            }
            continue;
        }

        let package_name = query_download_owner_package(&candidate);
        DOWNLOAD_OWNER_CACHE
            .lock()
            .unwrap_or_else(|err| err.into_inner())
            .put(candidate.clone(), package_name.clone(), now_ms);

        if package_name.is_some() {
            return package_name;
        }
    }

    None
}

fn should_lookup_download_owner(path: &str) -> bool {
    paths::starts_with(path, STORAGE_PREFIX) && path.contains(DOWNLOAD_SEGMENT)
}

fn build_lookup_candidates(path: &str) -> Vec<String> {
    let mut candidates = vec![path.to_string()];
    if let Some(companion_main_path) = normalize_companion_download_path(path)
        && companion_main_path != path
    {
        candidates.push(companion_main_path);
    }
    candidates
}

fn normalize_companion_download_path(path: &str) -> Option<String> {
    let file_name = path.rsplit('/').next()?;
    if !file_name.starts_with('.') || !file_name.ends_with(".js") || file_name.len() <= 4 {
        return None;
    }

    let parent = paths::parent(path);
    if parent.is_empty() {
        return None;
    }

    let normalized_name = &file_name[1..file_name.len() - 3];
    Some(format!("{}/{}", parent, normalized_name))
}

fn query_download_owner_package(path: &str) -> Option<String> {
    jni::with_env(|env| unsafe { query_download_owner_package_impl(env, path) }).flatten()
}

unsafe fn query_download_owner_package_impl(env: *mut JNIEnv, path: &str) -> Option<String> {
    let activity_thread_class = find_class(env, "android/app/ActivityThread")?;
    let application_class = find_class(env, "android/app/Application")?;
    let content_resolver_class = find_class(env, "android/content/ContentResolver")?;
    let uri_class = find_class(env, "android/net/Uri")?;
    let cursor_class = find_class(env, "android/database/Cursor")?;
    let string_class = find_class(env, "java/lang/String")?;

    let current_application = get_static_method_id(
        env,
        activity_thread_class,
        "currentApplication",
        "()Landroid/app/Application;",
    )?;
    let application =
        call_static_object_method(env, activity_thread_class, current_application, &[])?;

    let get_content_resolver = get_method_id(
        env,
        application_class,
        "getContentResolver",
        "()Landroid/content/ContentResolver;",
    )?;
    let resolver = call_object_method(env, application, get_content_resolver, &[])?;

    let uri_parse = get_static_method_id(
        env,
        uri_class,
        "parse",
        "(Ljava/lang/String;)Landroid/net/Uri;",
    )?;
    let uri_text = jni::new_jstring_utf8(env, DOWNLOADS_URI);
    let uri = call_static_object_method(
        env,
        uri_class,
        uri_parse,
        &[jvalue {
            l: uri_text as jobject,
        }],
    )?;

    let projection = new_string_array(env, string_class, &["notificationpackage", "uid"])?;
    let selection = jni::new_jstring_utf8(env, "_data=? OR hint=?");
    let hint_path = format!("{}{}", FILE_SCHEME_PREFIX, path);
    let selection_args = new_string_array(env, string_class, &[path, &hint_path])?;
    let sort_order = jni::new_jstring_utf8(env, "_id DESC");

    let query = get_method_id(
        env,
        content_resolver_class,
        "query",
        "(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;",
    )?;
    let cursor = call_object_method(
        env,
        resolver,
        query,
        &[
            jvalue { l: uri },
            jvalue {
                l: projection as jobject,
            },
            jvalue {
                l: selection as jobject,
            },
            jvalue {
                l: selection_args as jobject,
            },
            jvalue {
                l: sort_order as jobject,
            },
        ],
    )?;

    let move_to_first = get_method_id(env, cursor_class, "moveToFirst", "()Z")?;
    let moved = call_boolean_method(env, cursor, move_to_first, &[])?;
    if !moved {
        close_cursor(env, cursor_class, cursor);
        clear_local_refs(
            env,
            &[
                uri_text as jobject,
                selection as jobject,
                sort_order as jobject,
                application,
                resolver,
                uri,
                projection as jobject,
                selection_args as jobject,
                cursor,
                activity_thread_class as jobject,
                application_class as jobject,
                content_resolver_class as jobject,
                uri_class as jobject,
                cursor_class as jobject,
                string_class as jobject,
            ],
        );
        return None;
    }

    let get_string = get_method_id(env, cursor_class, "getString", "(I)Ljava/lang/String;")?;
    let get_int = get_method_id(env, cursor_class, "getInt", "(I)I")?;

    let package_name = call_object_method(env, cursor, get_string, &[jvalue { i: 0 }])
        .map(|value| {
            let text = jni::get_jstring_utf8(env, value as jstring);
            jni::delete_local_ref(env, value);
            text
        })
        .filter(|value| !value.is_empty())
        .filter(|value| !policy::is_system_writer_package(value));

    let package_name = if package_name.is_some() {
        package_name
    } else {
        let uid = call_int_method(env, cursor, get_int, &[jvalue { i: 1 }])?;
        resolve_package_by_uid(uid)
    };

    close_cursor(env, cursor_class, cursor);
    clear_local_refs(
        env,
        &[
            uri_text as jobject,
            selection as jobject,
            sort_order as jobject,
            application,
            resolver,
            uri,
            projection as jobject,
            selection_args as jobject,
            cursor,
            activity_thread_class as jobject,
            application_class as jobject,
            content_resolver_class as jobject,
            uri_class as jobject,
            cursor_class as jobject,
            string_class as jobject,
        ],
    );

    package_name
}

fn resolve_package_by_uid(uid: jint) -> Option<String> {
    if uid < 0 {
        return None;
    }

    let mut packages = policy::get_packages_for_uid(uid);
    packages.sort();
    packages.dedup();
    packages.into_iter().find(|package_name| {
        !package_name.is_empty() && !policy::is_system_writer_package(package_name)
    })
}

unsafe fn close_cursor(env: *mut JNIEnv, cursor_class: jclass, cursor: jobject) {
    let Some(close) = get_method_id(env, cursor_class, "close", "()V") else {
        return;
    };
    let _ = call_void_method(env, cursor, close, &[]);
}

unsafe fn find_class(env: *mut JNIEnv, name: &str) -> Option<jclass> {
    let table = *env;
    let class_name = CString::new(name).ok()?;
    let class = ((*table).v1_1.FindClass)(env, class_name.as_ptr());
    if class.is_null() || clear_exception(env) {
        return None;
    }
    Some(class)
}

unsafe fn get_method_id(
    env: *mut JNIEnv,
    class: jclass,
    name: &str,
    sig: &str,
) -> Option<jmethodID> {
    let table = *env;
    let method_name = CString::new(name).ok()?;
    let method_sig = CString::new(sig).ok()?;
    let method = ((*table).v1_1.GetMethodID)(env, class, method_name.as_ptr(), method_sig.as_ptr());
    if method.is_null() || clear_exception(env) {
        return None;
    }
    Some(method)
}

unsafe fn get_static_method_id(
    env: *mut JNIEnv,
    class: jclass,
    name: &str,
    sig: &str,
) -> Option<jmethodID> {
    let table = *env;
    let method_name = CString::new(name).ok()?;
    let method_sig = CString::new(sig).ok()?;
    let method = ((*table).v1_1.GetStaticMethodID)(env, class, method_name.as_ptr(), method_sig.as_ptr());
    if method.is_null() || clear_exception(env) {
        return None;
    }
    Some(method)
}

unsafe fn call_static_object_method(
    env: *mut JNIEnv,
    class: jclass,
    method: jmethodID,
    args: &[jvalue],
) -> Option<jobject> {
    let table = *env;
    let value = ((*table).v1_1.CallStaticObjectMethodA)(env, class, method, args.as_ptr());
    if value.is_null() || clear_exception(env) {
        return None;
    }
    Some(value)
}

unsafe fn call_object_method(
    env: *mut JNIEnv,
    obj: jobject,
    method: jmethodID,
    args: &[jvalue],
) -> Option<jobject> {
    let table = *env;
    let value = ((*table).v1_1.CallObjectMethodA)(env, obj, method, args.as_ptr());
    if value.is_null() || clear_exception(env) {
        return None;
    }
    Some(value)
}

unsafe fn call_boolean_method(
    env: *mut JNIEnv,
    obj: jobject,
    method: jmethodID,
    args: &[jvalue],
) -> Option<bool> {
    let table = *env;
    let value = ((*table).v1_1.CallBooleanMethodA)(env, obj, method, args.as_ptr());
    if clear_exception(env) {
        return None;
    }
    Some(value == jni_sys::JNI_TRUE)
}

unsafe fn call_int_method(
    env: *mut JNIEnv,
    obj: jobject,
    method: jmethodID,
    args: &[jvalue],
) -> Option<jint> {
    let table = *env;
    let value = ((*table).v1_1.CallIntMethodA)(env, obj, method, args.as_ptr());
    if clear_exception(env) {
        return None;
    }
    Some(value)
}

unsafe fn call_void_method(
    env: *mut JNIEnv,
    obj: jobject,
    method: jmethodID,
    args: &[jvalue],
) -> bool {
    let table = *env;
    ((*table).v1_1.CallVoidMethodA)(env, obj, method, args.as_ptr());
    !clear_exception(env)
}

unsafe fn new_string_array(
    env: *mut JNIEnv,
    string_class: jclass,
    values: &[&str],
) -> Option<jobjectArray> {
    let table = *env;
    let array = ((*table).v1_1.NewObjectArray)(
        env,
        values.len() as jint,
        string_class,
        std::ptr::null_mut(),
    );
    if array.is_null() || clear_exception(env) {
        return None;
    }

    for (index, value) in values.iter().enumerate() {
        let string_value = jni::new_jstring_utf8(env, value);
        if string_value.is_null() {
            continue;
        }
        ((*table).v1_1.SetObjectArrayElement)(env, array, index as jint, string_value as jobject);
        jni::delete_local_ref(env, string_value as jobject);
        if clear_exception(env) {
            jni::delete_local_ref(env, array as jobject);
            return None;
        }
    }

    Some(array)
}

unsafe fn clear_exception(env: *mut JNIEnv) -> bool {
    let table = *env;
    if !((*table).v1_2.ExceptionCheck)(env) {
        return false;
    }
    ((*table).v1_1.ExceptionClear)(env);
    true
}

unsafe fn clear_local_refs(env: *mut JNIEnv, values: &[jobject]) {
    for value in values {
        jni::delete_local_ref(env, *value);
    }
}
