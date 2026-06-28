use std::env;
use std::path::{Path, PathBuf};
use std::process::Command;

// 执行构建配置
fn main() {
    build_hooker_dex();

    let target_os = env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    if target_os != "android" {
        return;
    }

    println!("cargo:rustc-link-lib=log");
    println!("cargo:rustc-link-lib=android");

    let target_arch = env::var("CARGO_CFG_TARGET_ARCH").unwrap_or_default();
    let is_debug_build = env::var("SRX_BUILD_DEBUG").is_ok();
    build_lsplant_bridge(&target_arch, is_debug_build);

    let mut link_args = vec![
        "-Wl,--gc-sections".to_string(),
        "-Wl,--exclude-libs,ALL".to_string(),
        "-Wl,--pack-dyn-relocs=none".to_string(),
        "-Wl,-soname,libsrx_core.so".to_string(),
    ];

    // 调试构建保留 .symtab/.debug，便于 addr2line 定位崩溃栈
    if !is_debug_build {
        link_args.push("-Wl,-s".to_string());
    }

    // 16KB page 仅 arm64 真机需要；x86_64 模拟器 4KB page 下 zygisksu loader 会漏 mmap RW segment
    if target_arch == "aarch64" {
        link_args.push("-Wl,-z,max-page-size=16384".to_string());
    }

    for arg in &link_args {
        println!("cargo:rustc-link-arg={}", arg);
    }

    println!("cargo:rerun-if-env-changed=SRX_BUILD_DEBUG");
}

fn build_lsplant_bridge(target_arch: &str, is_debug_build: bool) {
    println!("cargo:rerun-if-changed=native/CMakeLists.txt");
    println!("cargo:rerun-if-changed=native/srx_lsplant_bridge.cpp");
    println!("cargo:rerun-if-changed=vendor/lsplant/CMakeLists.txt");
    println!("cargo:rerun-if-changed=vendor/lsplant/external/dex_builder/CMakeLists.txt");
    println!("cargo:rerun-if-env-changed=ANDROID_NDK_HOME");
    println!("cargo:rerun-if-env-changed=NDK_ROOT");
    println!("cargo:rerun-if-env-changed=ANDROID_HOME");
    println!("cargo:rerun-if-env-changed=ANDROID_SDK_ROOT");
    println!("cargo:rerun-if-env-changed=SRX_BUILD_DEBUG");

    let Some(ndk) = locate_ndk() else {
        if env::var("CARGO_CFG_CLIPPY").is_ok()
            || env::var("PROFILE").unwrap_or_default() == "debug"
        {
            println!("cargo:warning=srx_core: LSPlant build skipped: Android NDK not found");
            return;
        }
        panic!("Android NDK not found for LSPlant build");
    };
    let Some(abi) = cmake_android_abi(target_arch) else {
        panic!("unsupported Android arch for LSPlant: {target_arch}");
    };

    let out_dir = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR"));
    let build_dir = out_dir.join("lsplant_cmake").join(abi);
    let install_dir = out_dir.join("lsplant_install").join(abi);
    let profile = if is_debug_build { "Debug" } else { "Release" };
    let inline_hook_include = PathBuf::from(
        env::var_os("DEP_SRX_INLINE_HOOK_INCLUDE").expect("DEP_SRX_INLINE_HOOK_INCLUDE"),
    );

    let mut configure = Command::new("cmake");
    configure
        .arg("-S")
        .arg("native")
        .arg("-B")
        .arg(&build_dir)
        .arg("-G")
        .arg("Ninja")
        .arg(format!(
            "-DCMAKE_TOOLCHAIN_FILE={}",
            ndk.join("build/cmake/android.toolchain.cmake").display()
        ))
        .arg(format!("-DANDROID_ABI={abi}"))
        .arg("-DANDROID_PLATFORM=android-29")
        .arg("-DANDROID_STL=c++_static")
        .arg(format!("-DCMAKE_BUILD_TYPE={profile}"))
        .arg(format!("-DCMAKE_INSTALL_PREFIX={}", install_dir.display()))
        .arg(format!(
            "-DSRX_INLINE_HOOK_INCLUDE_DIR={}",
            inline_hook_include.display()
        ))
        .arg("-DLSPLANT_BUILD_SHARED=OFF")
        .arg("-DDEX_BUILDER_BUILD_SHARED=OFF")
        .arg("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON");
    run_command(&mut configure, "configure LSPlant");

    let mut build = Command::new("cmake");
    build
        .arg("--build")
        .arg(&build_dir)
        .arg("--target")
        .arg("srx_lsplant_bridge");
    run_command(&mut build, "build LSPlant");

    println!("cargo:rustc-link-search=native={}", build_dir.display());
    println!(
        "cargo:rustc-link-search=native={}",
        build_dir.join("lsplant").display()
    );
    println!(
        "cargo:rustc-link-search=native={}",
        build_dir.join("lsplant/external/dex_builder").display()
    );
    println!("cargo:rustc-link-lib=static=srx_lsplant_bridge");
    println!("cargo:rustc-link-lib=static=lsplant_static");
    println!("cargo:rustc-link-lib=static=dex_builder_static");
    println!("cargo:rustc-link-lib=static=c++_static");
    println!("cargo:rustc-link-lib=static=c++abi");
    println!("cargo:rustc-link-lib=z");
}

fn cmake_android_abi(target_arch: &str) -> Option<&'static str> {
    match target_arch {
        "aarch64" => Some("arm64-v8a"),
        "x86_64" => Some("x86_64"),
        _ => None,
    }
}

fn locate_ndk() -> Option<PathBuf> {
    if let Some(path) = env::var_os("ANDROID_NDK_HOME") {
        let ndk = PathBuf::from(path);
        if ndk.exists() {
            return Some(ndk);
        }
    }
    if let Some(path) = env::var_os("NDK_ROOT") {
        let ndk = PathBuf::from(path);
        if ndk.exists() {
            return Some(ndk);
        }
    }
    let sdk = env::var_os("ANDROID_HOME").or_else(|| env::var_os("ANDROID_SDK_ROOT"))?;
    let ndk_dir = PathBuf::from(sdk).join("ndk");
    let mut versions = std::fs::read_dir(ndk_dir)
        .ok()?
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.is_dir())
        .collect::<Vec<_>>();
    versions.sort_by(|a, b| b.cmp(a));
    versions.into_iter().next()
}

fn run_command(command: &mut Command, label: &str) {
    let status = command
        .status()
        .unwrap_or_else(|err| panic!("{label} failed to start: {err}"));
    if !status.success() {
        panic!("{label} failed: {status}");
    }
}

// 从 java_src 生成 Hooker.dex 到 OUT_DIR；工具链缺失时写空文件并告警
fn build_hooker_dex() {
    println!("cargo:rerun-if-changed=java_src");
    println!("cargo:rerun-if-env-changed=ANDROID_HOME");
    println!("cargo:rerun-if-env-changed=ANDROID_SDK_ROOT");
    println!("cargo:rerun-if-env-changed=JAVA_HOME");

    let out_dir = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR"));
    let dex_out = out_dir.join("Hooker.dex");
    let java_src_dir = PathBuf::from("java_src");

    match compile_dex(&java_src_dir, &out_dir, &dex_out) {
        Ok(()) => {}
        Err(err) => {
            // 工具链缺失不阻断构建；运行时若 dex 为空则跳过 Java hook 初始化
            println!("cargo:warning=srx_core: Hooker.dex build skipped: {err}");
            let _ = std::fs::write(&dex_out, b"");
        }
    }
}

fn compile_dex(java_src_dir: &Path, out_dir: &Path, dex_out: &Path) -> Result<(), String> {
    let javac = locate_javac().ok_or_else(|| "javac not found".to_string())?;
    let d8 = locate_d8().ok_or_else(|| "d8 not found".to_string())?;
    let android_jar = locate_android_jar().ok_or_else(|| "android.jar not found".to_string())?;
    let java_files = collect_java_files(java_src_dir)?;

    let classes_dir = out_dir.join("java_classes");
    if classes_dir.exists() {
        std::fs::remove_dir_all(&classes_dir).map_err(|e| format!("clean classes: {e}"))?;
    }
    std::fs::create_dir_all(&classes_dir).map_err(|e| format!("mkdir classes: {e}"))?;

    let mut javac_cmd = Command::new(&javac);
    javac_cmd
        .args(["-source", "8", "-target", "8"])
        .arg("-classpath")
        .arg(android_jar)
        .arg("-d")
        .arg(&classes_dir);
    for java_file in java_files {
        javac_cmd.arg(java_file);
    }
    let javac_status = javac_cmd
        .status()
        .map_err(|e| format!("run javac {javac:?}: {e}"))?;
    if !javac_status.success() {
        return Err(format!("javac exit {javac_status}"));
    }

    let class_file = classes_dir.join("org/srx/hook/Hooker.class");
    if !class_file.exists() {
        return Err(format!("expected {class_file:?} not produced"));
    }

    let class_files = collect_class_files(&classes_dir)?;
    let mut d8_cmd = Command::new(&d8);
    for class_file in class_files {
        d8_cmd.arg(class_file);
    }
    let d8_status = d8_cmd
        .args(["--min-api", "31"])
        .arg("--output")
        .arg(out_dir)
        .status()
        .map_err(|e| format!("run d8 {d8:?}: {e}"))?;
    if !d8_status.success() {
        return Err(format!("d8 exit {d8_status}"));
    }

    let produced = out_dir.join("classes.dex");
    std::fs::rename(&produced, dex_out)
        .map_err(|e| format!("rename {produced:?} -> {dex_out:?}: {e}"))?;
    Ok(())
}

fn collect_java_files(root: &Path) -> Result<Vec<PathBuf>, String> {
    collect_files_by_extension(root, "java", "Java source", "found")
}

fn collect_class_files(root: &Path) -> Result<Vec<PathBuf>, String> {
    collect_files_by_extension(root, "class", "class", "produced")
}

fn collect_files_by_extension(
    root: &Path,
    extension: &str,
    label: &str,
    empty_verb: &str,
) -> Result<Vec<PathBuf>, String> {
    fn visit(dir: &Path, extension: &str, out: &mut Vec<PathBuf>) -> Result<(), String> {
        for entry in std::fs::read_dir(dir).map_err(|e| format!("read source dir {dir:?}: {e}"))? {
            let entry = entry.map_err(|e| format!("read source entry: {e}"))?;
            let path = entry.path();
            if path.is_dir() {
                visit(&path, extension, out)?;
            } else if path.extension().is_some_and(|ext| ext == extension) {
                out.push(path);
            }
        }
        Ok(())
    }

    let mut files = Vec::new();
    visit(root, extension, &mut files)?;
    files.sort();
    if files.is_empty() {
        return Err(format!("no {label} files {empty_verb}"));
    }
    Ok(files)
}

fn locate_javac() -> Option<PathBuf> {
    let exe = if cfg!(windows) { "javac.exe" } else { "javac" };
    if let Ok(home) = env::var("JAVA_HOME") {
        let p = PathBuf::from(home).join("bin").join(exe);
        if p.exists() {
            return Some(p);
        }
    }
    Some(PathBuf::from("javac"))
}

fn locate_android_jar() -> Option<PathBuf> {
    let sdk = env::var("ANDROID_HOME")
        .or_else(|_| env::var("ANDROID_SDK_ROOT"))
        .ok()?;
    let platforms = PathBuf::from(sdk).join("platforms");
    let mut best: Option<(u32, PathBuf)> = None;
    for entry in std::fs::read_dir(&platforms).ok()?.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        let Some(api) = name.strip_prefix("android-").and_then(|s| s.parse().ok()) else {
            continue;
        };
        let jar = entry.path().join("android.jar");
        if !jar.exists() {
            continue;
        }
        if best.as_ref().is_none_or(|(v, _)| api > *v) {
            best = Some((api, jar));
        }
    }
    best.map(|(_, p)| p)
}

fn locate_d8() -> Option<PathBuf> {
    let sdk = env::var("ANDROID_HOME")
        .or_else(|_| env::var("ANDROID_SDK_ROOT"))
        .ok()?;
    let build_tools = PathBuf::from(sdk).join("build-tools");
    let exe = if cfg!(windows) { "d8.bat" } else { "d8" };

    let mut best: Option<(Vec<u32>, PathBuf)> = None;
    for entry in std::fs::read_dir(&build_tools).ok()?.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        let ver: Vec<u32> = name.split('.').filter_map(|s| s.parse().ok()).collect();
        if ver.is_empty() {
            continue;
        }
        let candidate = entry.path().join(exe);
        if !candidate.exists() {
            continue;
        }
        if best.as_ref().is_none_or(|(v, _)| &ver > v) {
            best = Some((ver, candidate));
        }
    }
    best.map(|(_, p)| p)
}
