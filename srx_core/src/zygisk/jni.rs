// JNI 工具函数：jstring 转 UTF-8、线程附着、类/方法查找、对象调用等通用操作
use jni_sys::{
    JNI_FALSE, JNI_TRUE, JNIEnv, JNINativeMethod, JavaVM, jboolean, jbyteArray, jclass, jint,
    jmethodID, jobject, jsize, jstring, jvalue,
};
use std::ffi::CString;
use std::sync::atomic::{AtomicPtr, Ordering};

static JAVA_VM: AtomicPtr<JavaVM> = AtomicPtr::new(std::ptr::null_mut());

// 从当前 env 提取 JavaVM，供后续线程附着使用
pub fn init_java_vm(env: *mut JNIEnv) {
    if env.is_null() {
        return;
    }

    unsafe {
        let table = *env;
        if table.is_null() {
            return;
        }

        let mut vm = std::ptr::null_mut();
        if ((*table).v1_1.GetJavaVM)(env, &mut vm) == jni_sys::JNI_OK && !vm.is_null() {
            JAVA_VM.store(vm, Ordering::Release);
        }
    }
}
// 在需要时附着当前线程，返回可用的 JNIEnv
pub fn with_env<R, F>(f: F) -> Option<R>
where
    F: FnOnce(*mut JNIEnv) -> R,
{
    let vm = JAVA_VM.load(Ordering::Acquire);
    if vm.is_null() {
        return None;
    }

    unsafe {
        let table = *vm;
        if table.is_null() {
            return None;
        }

        let mut env_ptr: *mut std::ffi::c_void = std::ptr::null_mut();
        let status = ((*table).v1_2.GetEnv)(vm, &mut env_ptr, jni_sys::JNI_VERSION_1_6 as jint);
        let mut should_detach = false;

        if status == jni_sys::JNI_EDETACHED {
            if ((*table).v1_1.AttachCurrentThread)(vm, &mut env_ptr, std::ptr::null_mut())
                != jni_sys::JNI_OK
            {
                return None;
            }
            should_detach = true;
        } else if status != jni_sys::JNI_OK {
            return None;
        }

        let env = env_ptr as *mut JNIEnv;
        if env.is_null() {
            if should_detach {
                let _ = ((*table).v1_1.DetachCurrentThread)(vm);
            }
            return None;
        }

        let result = f(env);

        if should_detach {
            let _ = ((*table).v1_1.DetachCurrentThread)(vm);
        }

        Some(result)
    }
}

pub fn get_jstring_utf8(env: *mut jni_sys::JNIEnv, value: jstring) -> String {
    if env.is_null() || value.is_null() {
        return String::new();
    }

    unsafe {
        let table = *env;
        if table.is_null() {
            return String::new();
        }

        let chars = ((*table).v1_1.GetStringUTFChars)(env, value, std::ptr::null_mut());
        if chars.is_null() {
            // GetStringUTFChars 失败时可能留下 OutOfMemoryError，必须清理
            clear_pending_exception(env);
            return String::new();
        }
        let text = std::ffi::CStr::from_ptr(chars)
            .to_string_lossy()
            .to_string();
        ((*table).v1_1.ReleaseStringUTFChars)(env, value, chars);
        text
    }
}

// 将 UTF-8 字符串构造为 jstring，失败时返回 null
pub fn new_jstring_utf8(env: *mut jni_sys::JNIEnv, value: &str) -> jstring {
    if env.is_null() {
        return std::ptr::null_mut();
    }

    let Ok(c_value) = std::ffi::CString::new(value) else {
        return std::ptr::null_mut();
    };

    unsafe {
        let table = *env;
        if table.is_null() {
            return std::ptr::null_mut();
        }

        let result = ((*table).v1_1.NewStringUTF)(env, c_value.as_ptr());
        if result.is_null() {
            // NewStringUTF 失败留下 OutOfMemoryError，残留会让下一次 JNI 调用 abort
            clear_pending_exception(env);
        }
        result
    }
}

// 清理当前线程 JNI pending exception；残留会让后续 JNI 调用直接 abort
pub fn clear_pending_exception(env: *mut jni_sys::JNIEnv) -> bool {
    if env.is_null() {
        return false;
    }

    unsafe {
        let table = *env;
        if table.is_null() {
            return false;
        }

        if !((*table).v1_2.ExceptionCheck)(env) {
            return false;
        }
        ((*table).v1_1.ExceptionClear)(env);
        true
    }
}

// 释放 JNI 本地引用，避免局部引用表增长
pub fn delete_local_ref(env: *mut jni_sys::JNIEnv, value: jobject) {
    if env.is_null() || value.is_null() {
        return;
    }

    unsafe {
        let table = *env;
        if table.is_null() {
            return;
        }
        ((*table).v1_1.DeleteLocalRef)(env, value);
    }
}

// 下面是 lsplant Hook 链路必需的 JNI 调用封装
// 约定：参数 null / env 非法 → 返回 null / false；调用失败清 pending exception

// FindClass：按全限定名（斜杠形式如 "java/lang/String"）查找类
#[allow(dead_code)]
pub fn find_class(env: *mut JNIEnv, name: &str) -> jclass {
    if env.is_null() {
        return std::ptr::null_mut();
    }
    let Ok(c_name) = CString::new(name) else {
        return std::ptr::null_mut();
    };

    unsafe {
        let table = *env;
        if table.is_null() {
            return std::ptr::null_mut();
        }
        let clazz = ((*table).v1_1.FindClass)(env, c_name.as_ptr());
        if clazz.is_null() {
            clear_pending_exception(env);
        }
        clazz
    }
}

// GetMethodID：按方法名 + 签名查实例方法
#[allow(dead_code)]
pub fn get_method_id(env: *mut JNIEnv, clazz: jclass, name: &str, sig: &str) -> jmethodID {
    if env.is_null() || clazz.is_null() {
        return std::ptr::null_mut();
    }
    let Ok(c_name) = CString::new(name) else {
        return std::ptr::null_mut();
    };
    let Ok(c_sig) = CString::new(sig) else {
        return std::ptr::null_mut();
    };

    unsafe {
        let table = *env;
        if table.is_null() {
            return std::ptr::null_mut();
        }
        let mid = ((*table).v1_1.GetMethodID)(env, clazz, c_name.as_ptr(), c_sig.as_ptr());
        if mid.is_null() {
            clear_pending_exception(env);
        }
        mid
    }
}

// GetStaticMethodID：按方法名 + 签名查静态方法
#[allow(dead_code)]
pub fn get_static_method_id(env: *mut JNIEnv, clazz: jclass, name: &str, sig: &str) -> jmethodID {
    if env.is_null() || clazz.is_null() {
        return std::ptr::null_mut();
    }
    let Ok(c_name) = CString::new(name) else {
        return std::ptr::null_mut();
    };
    let Ok(c_sig) = CString::new(sig) else {
        return std::ptr::null_mut();
    };

    unsafe {
        let table = *env;
        if table.is_null() {
            return std::ptr::null_mut();
        }
        let mid = ((*table).v1_1.GetStaticMethodID)(env, clazz, c_name.as_ptr(), c_sig.as_ptr());
        if mid.is_null() {
            clear_pending_exception(env);
        }
        mid
    }
}

// ToReflectedMethod：jmethodID → java.lang.reflect.Method jobject（lsplant Hook 的 target 要这个）
#[allow(dead_code)]
pub fn to_reflected_method(
    env: *mut JNIEnv,
    clazz: jclass,
    method_id: jmethodID,
    is_static: bool,
) -> jobject {
    if env.is_null() || clazz.is_null() || method_id.is_null() {
        return std::ptr::null_mut();
    }

    unsafe {
        let table = *env;
        if table.is_null() {
            return std::ptr::null_mut();
        }
        let flag: jboolean = if is_static { JNI_TRUE } else { JNI_FALSE };
        let obj = ((*table).v1_2.ToReflectedMethod)(env, clazz, method_id, flag);
        if obj.is_null() {
            clear_pending_exception(env);
        }
        obj
    }
}

// NewObjectA：构造对象，args 是参数数组（无参传空切片）
#[allow(dead_code)]
pub fn new_object_a(env: *mut JNIEnv, clazz: jclass, ctor: jmethodID, args: &[jvalue]) -> jobject {
    if env.is_null() || clazz.is_null() || ctor.is_null() {
        return std::ptr::null_mut();
    }

    unsafe {
        let table = *env;
        if table.is_null() {
            return std::ptr::null_mut();
        }
        let args_ptr = if args.is_empty() {
            std::ptr::null()
        } else {
            args.as_ptr()
        };
        let obj = ((*table).v1_1.NewObjectA)(env, clazz, ctor, args_ptr);
        if obj.is_null() {
            clear_pending_exception(env);
        }
        obj
    }
}

// CallObjectMethodA：实例方法调用返回 jobject，args 无参传空切片
#[allow(dead_code)]
pub fn call_object_method_a(
    env: *mut JNIEnv,
    obj: jobject,
    method_id: jmethodID,
    args: &[jvalue],
) -> jobject {
    if env.is_null() || obj.is_null() || method_id.is_null() {
        return std::ptr::null_mut();
    }

    unsafe {
        let table = *env;
        if table.is_null() {
            return std::ptr::null_mut();
        }
        let args_ptr = if args.is_empty() {
            std::ptr::null()
        } else {
            args.as_ptr()
        };
        let result = ((*table).v1_1.CallObjectMethodA)(env, obj, method_id, args_ptr);
        if clear_pending_exception(env) {
            return std::ptr::null_mut();
        }
        result
    }
}

// CallStaticObjectMethodA：静态方法调用返回 jobject
#[allow(dead_code)]
pub fn call_static_object_method_a(
    env: *mut JNIEnv,
    clazz: jclass,
    method_id: jmethodID,
    args: &[jvalue],
) -> jobject {
    if env.is_null() || clazz.is_null() || method_id.is_null() {
        return std::ptr::null_mut();
    }

    unsafe {
        let table = *env;
        if table.is_null() {
            return std::ptr::null_mut();
        }
        let args_ptr = if args.is_empty() {
            std::ptr::null()
        } else {
            args.as_ptr()
        };
        let result = ((*table).v1_1.CallStaticObjectMethodA)(env, clazz, method_id, args_ptr);
        if clear_pending_exception(env) {
            return std::ptr::null_mut();
        }
        result
    }
}

// CallStaticBooleanMethodA：静态方法调用返回 boolean
#[allow(dead_code)]
pub fn call_static_boolean_method_a(
    env: *mut JNIEnv,
    clazz: jclass,
    method_id: jmethodID,
    args: &[jvalue],
) -> bool {
    if env.is_null() || clazz.is_null() || method_id.is_null() {
        return false;
    }

    unsafe {
        let table = *env;
        if table.is_null() {
            return false;
        }
        let args_ptr = if args.is_empty() {
            std::ptr::null()
        } else {
            args.as_ptr()
        };
        let result = ((*table).v1_1.CallStaticBooleanMethodA)(env, clazz, method_id, args_ptr);
        if clear_pending_exception(env) {
            return false;
        }
        result == JNI_TRUE
    }
}

// NewGlobalRef：提升引用为全局，跨线程 / 跨调用保留 jobject
#[allow(dead_code)]
pub fn new_global_ref(env: *mut JNIEnv, obj: jobject) -> jobject {
    if env.is_null() || obj.is_null() {
        return std::ptr::null_mut();
    }
    unsafe {
        let table = *env;
        if table.is_null() {
            return std::ptr::null_mut();
        }
        let gref = ((*table).v1_1.NewGlobalRef)(env, obj);
        if gref.is_null() {
            clear_pending_exception(env);
        }
        gref
    }
}

// DeleteGlobalRef：释放全局引用
#[allow(dead_code)]
pub fn delete_global_ref(env: *mut JNIEnv, gref: jobject) {
    if env.is_null() || gref.is_null() {
        return;
    }
    unsafe {
        let table = *env;
        if table.is_null() {
            return;
        }
        ((*table).v1_1.DeleteGlobalRef)(env, gref);
    }
}

// IsInstanceOf：判断 obj 是否为 clazz 的实例
#[allow(dead_code)]
pub fn is_instance_of(env: *mut JNIEnv, obj: jobject, clazz: jclass) -> bool {
    if env.is_null() || obj.is_null() || clazz.is_null() {
        return false;
    }
    unsafe {
        let table = *env;
        if table.is_null() {
            return false;
        }
        ((*table).v1_1.IsInstanceOf)(env, obj, clazz) == JNI_TRUE
    }
}

// GetObjectClass：取 obj 的运行时 class
#[allow(dead_code)]
pub fn get_object_class(env: *mut JNIEnv, obj: jobject) -> jclass {
    if env.is_null() || obj.is_null() {
        return std::ptr::null_mut();
    }
    unsafe {
        let table = *env;
        if table.is_null() {
            return std::ptr::null_mut();
        }
        let clazz = ((*table).v1_1.GetObjectClass)(env, obj);
        if clazz.is_null() {
            clear_pending_exception(env);
        }
        clazz
    }
}

// NewByteArray：分配 Java byte[]
#[allow(dead_code)]
pub fn new_byte_array(env: *mut JNIEnv, len: usize) -> jbyteArray {
    if env.is_null() {
        return std::ptr::null_mut();
    }
    unsafe {
        let table = *env;
        if table.is_null() {
            return std::ptr::null_mut();
        }
        let arr = ((*table).v1_1.NewByteArray)(env, len as jsize);
        if arr.is_null() {
            clear_pending_exception(env);
        }
        arr
    }
}

// SetByteArrayRegion：把 Rust 切片拷贝到 Java byte[]
#[allow(dead_code)]
pub fn set_byte_array_region(env: *mut JNIEnv, array: jbyteArray, data: &[u8]) -> bool {
    if env.is_null() || array.is_null() {
        return false;
    }
    unsafe {
        let table = *env;
        if table.is_null() {
            return false;
        }
        ((*table).v1_1.SetByteArrayRegion)(
            env,
            array,
            0,
            data.len() as jsize,
            data.as_ptr() as *const jni_sys::jbyte,
        );
        !clear_pending_exception(env)
    }
}

// RegisterNatives：把 Rust 函数指针绑定到 Java native 方法；切片寿命必须覆盖调用
#[allow(dead_code)]
pub fn register_natives(env: *mut JNIEnv, clazz: jclass, methods: &[JNINativeMethod]) -> bool {
    if env.is_null() || clazz.is_null() || methods.is_empty() {
        return false;
    }
    unsafe {
        let table = *env;
        if table.is_null() {
            return false;
        }
        let rc =
            ((*table).v1_1.RegisterNatives)(env, clazz, methods.as_ptr(), methods.len() as jint);
        if rc != jni_sys::JNI_OK {
            clear_pending_exception(env);
            return false;
        }
        true
    }
}
