// Java 栈帧回溯：通过 JNI 扫描当前线程的调用栈，识别 shared_uid 进程内的具体包名

use super::SourceIdentity;
use crate::zygisk::jni;
use jni_sys::{JNIEnv, jarray, jclass, jint, jmethodID, jobject, jobjectArray, jstring, jvalue};

const MAX_SCAN_FRAMES: jint = 20;

pub fn infer_package_from_java_stack(shared_uid_packages: &str) -> Option<SourceIdentity> {
    if shared_uid_packages.is_empty() {
        return None;
    }

    let packages: Vec<&str> = shared_uid_packages
        .split(',')
        .map(|s| s.trim())
        .filter(|s| !s.is_empty())
        .collect();
    if packages.is_empty() {
        return None;
    }

    jni::with_env(|env| unsafe { scan_stack_frames(env, &packages) }).flatten()
}

// 重定向引擎专用：按包名列表扫描栈帧，返回匹配的包名
pub(crate) fn infer_caller_package_by_stack(packages: &[String]) -> Option<String> {
    if packages.is_empty() {
        return None;
    }
    let refs: Vec<&str> = packages.iter().map(|s| s.as_str()).collect();
    jni::with_env(|env| unsafe { scan_stack_frames_raw(env, &refs) }).flatten()
}

unsafe fn scan_stack_frames(env: *mut JNIEnv, packages: &[&str]) -> Option<SourceIdentity> {
    scan_stack_frames_raw(env, packages).map(|pkg| SourceIdentity::new(pkg, "java_stack", "medium"))
}

unsafe fn scan_stack_frames_raw(env: *mut JNIEnv, packages: &[&str]) -> Option<String> {
    let thread_class = find_class(env, "java/lang/Thread")?;
    let Some(ste_class) = find_class(env, "java/lang/StackTraceElement") else {
        jni::delete_local_ref(env, thread_class as jobject);
        return None;
    };

    let result = scan_stack_frames_inner(env, packages, thread_class, ste_class);

    jni::delete_local_ref(env, thread_class as jobject);
    jni::delete_local_ref(env, ste_class as jobject);
    result
}

unsafe fn scan_stack_frames_inner(
    env: *mut JNIEnv,
    packages: &[&str],
    thread_class: jclass,
    ste_class: jclass,
) -> Option<String> {
    let current_thread_mid =
        get_static_method_id(env, thread_class, "currentThread", "()Ljava/lang/Thread;")?;
    let get_stack_trace_mid = get_method_id(
        env,
        thread_class,
        "getStackTrace",
        "()[Ljava/lang/StackTraceElement;",
    )?;
    let get_class_name_mid = get_method_id(env, ste_class, "getClassName", "()Ljava/lang/String;")?;

    let thread = call_static_object_method(env, thread_class, current_thread_mid, &[])?;
    let stack_array = call_object_method(env, thread, get_stack_trace_mid, &[]);
    jni::delete_local_ref(env, thread);

    let stack_array = stack_array? as jobjectArray;
    let len = get_array_length(env, stack_array as jarray);
    let scan_limit = len.min(MAX_SCAN_FRAMES);

    let mut result: Option<String> = None;
    for i in 0..scan_limit {
        let element = get_object_array_element(env, stack_array, i);
        if element.is_null() {
            continue;
        }
        let class_name_jstr = call_object_method(env, element, get_class_name_mid, &[]);
        jni::delete_local_ref(env, element);

        let Some(jstr) = class_name_jstr else {
            continue;
        };
        let class_name = jni::get_jstring_utf8(env, jstr as jstring);
        jni::delete_local_ref(env, jstr);

        if class_name.is_empty() {
            continue;
        }

        for &pkg in packages {
            // 匹配包名前缀 + 点边界，确保 com.android.mtp 不会匹配 com.android.mtpx
            if class_name.len() > pkg.len()
                && class_name.starts_with(pkg)
                && class_name.as_bytes()[pkg.len()] == b'.'
            {
                result = Some(pkg.to_string());
                break;
            }
        }
        if result.is_some() {
            break;
        }
    }

    jni::delete_local_ref(env, stack_array as jobject);
    result
}

unsafe fn find_class(env: *mut JNIEnv, name: &str) -> Option<jclass> {
    let table = *env;
    let c_name = std::ffi::CString::new(name).ok()?;
    let class = ((*table).v1_1.FindClass)(env, c_name.as_ptr());
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
    let c_name = std::ffi::CString::new(name).ok()?;
    let c_sig = std::ffi::CString::new(sig).ok()?;
    let mid = ((*table).v1_1.GetMethodID)(env, class, c_name.as_ptr(), c_sig.as_ptr());
    if mid.is_null() || clear_exception(env) {
        return None;
    }
    Some(mid)
}

unsafe fn get_static_method_id(
    env: *mut JNIEnv,
    class: jclass,
    name: &str,
    sig: &str,
) -> Option<jmethodID> {
    let table = *env;
    let c_name = std::ffi::CString::new(name).ok()?;
    let c_sig = std::ffi::CString::new(sig).ok()?;
    let mid = ((*table).v1_1.GetStaticMethodID)(env, class, c_name.as_ptr(), c_sig.as_ptr());
    if mid.is_null() || clear_exception(env) {
        return None;
    }
    Some(mid)
}

unsafe fn call_static_object_method(
    env: *mut JNIEnv,
    class: jclass,
    mid: jmethodID,
    args: &[jvalue],
) -> Option<jobject> {
    let table = *env;
    let value = ((*table).v1_1.CallStaticObjectMethodA)(env, class, mid, args.as_ptr());
    if value.is_null() || clear_exception(env) {
        return None;
    }
    Some(value)
}

unsafe fn call_object_method(
    env: *mut JNIEnv,
    obj: jobject,
    mid: jmethodID,
    args: &[jvalue],
) -> Option<jobject> {
    let table = *env;
    let value = ((*table).v1_1.CallObjectMethodA)(env, obj, mid, args.as_ptr());
    if value.is_null() || clear_exception(env) {
        return None;
    }
    Some(value)
}

unsafe fn get_array_length(env: *mut JNIEnv, array: jarray) -> jint {
    let table = *env;
    ((*table).v1_1.GetArrayLength)(env, array)
}

unsafe fn get_object_array_element(env: *mut JNIEnv, array: jobjectArray, index: jint) -> jobject {
    let table = *env;
    let value = ((*table).v1_1.GetObjectArrayElement)(env, array, index);
    if clear_exception(env) {
        return std::ptr::null_mut();
    }
    value
}

unsafe fn clear_exception(env: *mut JNIEnv) -> bool {
    let table = *env;
    if !((*table).v1_2.ExceptionCheck)(env) {
        return false;
    }
    ((*table).v1_1.ExceptionClear)(env);
    true
}
