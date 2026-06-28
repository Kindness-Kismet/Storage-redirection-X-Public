// 把内嵌 DEX 字节加载为 Java Class：byte[] → ByteBuffer → InMemoryDexClassLoader → loadClass
// 每步失败都返回 null；本地引用通过 LocalRef RAII 确保清理

#![allow(dead_code)]

use crate::zygisk::jni::{
    call_object_method_a, call_static_object_method_a, delete_local_ref, find_class, get_method_id,
    get_static_method_id, new_byte_array, new_jstring_utf8, new_object_a, set_byte_array_region,
};
use jni_sys::{JNIEnv, jclass, jobject, jvalue};

struct LocalRef {
    env: *mut JNIEnv,
    obj: jobject,
}

impl LocalRef {
    fn wrap(env: *mut JNIEnv, obj: jobject) -> Self {
        Self { env, obj }
    }
    fn is_null(&self) -> bool {
        self.obj.is_null()
    }
    fn raw(&self) -> jobject {
        self.obj
    }
    fn release(mut self) -> jobject {
        let r = self.obj;
        self.obj = std::ptr::null_mut();
        r
    }
}

impl Drop for LocalRef {
    fn drop(&mut self) {
        if !self.obj.is_null() {
            delete_local_ref(self.env, self.obj);
            self.obj = std::ptr::null_mut();
        }
    }
}

// 返回值为 jclass 本地引用，调用方负责 NewGlobalRef 或 delete
pub fn load_dex_class(env: *mut JNIEnv, dex: &[u8], class_name: &str) -> jclass {
    if env.is_null() || dex.is_empty() || class_name.is_empty() {
        return std::ptr::null_mut();
    }

    // 1. Java byte[] 承载 dex 字节
    let buf = LocalRef::wrap(env, new_byte_array(env, dex.len()));
    if buf.is_null() || !set_byte_array_region(env, buf.raw(), dex) {
        return std::ptr::null_mut();
    }

    // 2. ByteBuffer.wrap(buf)
    let bb_class = LocalRef::wrap(env, find_class(env, "java/nio/ByteBuffer"));
    if bb_class.is_null() {
        return std::ptr::null_mut();
    }
    let wrap_mid = get_static_method_id(env, bb_class.raw(), "wrap", "([B)Ljava/nio/ByteBuffer;");
    if wrap_mid.is_null() {
        return std::ptr::null_mut();
    }
    let dex_buf = LocalRef::wrap(
        env,
        call_static_object_method_a(env, bb_class.raw(), wrap_mid, &[jvalue { l: buf.raw() }]),
    );
    if dex_buf.is_null() {
        return std::ptr::null_mut();
    }

    // 3. parent = ClassLoader.getSystemClassLoader()
    let cl_class = LocalRef::wrap(env, find_class(env, "java/lang/ClassLoader"));
    if cl_class.is_null() {
        return std::ptr::null_mut();
    }
    let gscl_mid = get_static_method_id(
        env,
        cl_class.raw(),
        "getSystemClassLoader",
        "()Ljava/lang/ClassLoader;",
    );
    if gscl_mid.is_null() {
        return std::ptr::null_mut();
    }
    let parent = LocalRef::wrap(
        env,
        call_static_object_method_a(env, cl_class.raw(), gscl_mid, &[]),
    );
    if parent.is_null() {
        return std::ptr::null_mut();
    }

    // 4. new InMemoryDexClassLoader(dexBuf, parent)
    let imcl_class = LocalRef::wrap(env, find_class(env, "dalvik/system/InMemoryDexClassLoader"));
    if imcl_class.is_null() {
        return std::ptr::null_mut();
    }
    let ctor_mid = get_method_id(
        env,
        imcl_class.raw(),
        "<init>",
        "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V",
    );
    if ctor_mid.is_null() {
        return std::ptr::null_mut();
    }
    let loader = LocalRef::wrap(
        env,
        new_object_a(
            env,
            imcl_class.raw(),
            ctor_mid,
            &[jvalue { l: dex_buf.raw() }, jvalue { l: parent.raw() }],
        ),
    );
    if loader.is_null() {
        return std::ptr::null_mut();
    }

    // 5. loader.loadClass(class_name)
    let load_mid = get_method_id(
        env,
        cl_class.raw(),
        "loadClass",
        "(Ljava/lang/String;)Ljava/lang/Class;",
    );
    if load_mid.is_null() {
        return std::ptr::null_mut();
    }
    let name_jstr = LocalRef::wrap(env, new_jstring_utf8(env, class_name));
    if name_jstr.is_null() {
        return std::ptr::null_mut();
    }
    let clazz = call_object_method_a(
        env,
        loader.raw(),
        load_mid,
        &[jvalue { l: name_jstr.raw() }],
    );
    if clazz.is_null() {
        return std::ptr::null_mut();
    }
    LocalRef::wrap(env, clazz).release() as jclass
}
