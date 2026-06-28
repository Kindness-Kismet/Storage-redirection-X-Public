#!/usr/bin/env python3
import os
import sys

os.environ["PYTHONDONTWRITEBYTECODE"] = "1"
sys.dont_write_bytecode = True

import argparse
import hashlib
import platform
import shutil
import subprocess
import time
import zipfile
from dataclasses import dataclass
from pathlib import Path

# 发布文件名需要和 Public 仓库现有资产保持一致。
APP_NAME = "Storage-Redirect-X"
SUPPORTED_ABIS = ("arm64-v8a", "x86_64")
MODULE_ID = "storage.redirect.x"
MODULE_NAME = "Storage Redirect X"
MODULE_AUTHOR = "Storage Redirect Team"
MODULE_DESCRIPTION = "Storage Redirect X module. Config changes made in the manager app apply automatically; external config file edits require restarting related apps."
MODULE_UPDATE_JSON = "https://raw.githubusercontent.com/Kindness-Kismet/Storage-redirection-X-Public/main/update.json"
MODULE_RELEASE_BASE_URL = "https://raw.githubusercontent.com/Kindness-Kismet/Storage-redirection-X-Public/main"
MODULE_CHANGELOG_URL = "https://raw.githubusercontent.com/Kindness-Kismet/Storage-redirection-X-Public/main/CHANGELOG.md"
CORE_SO_NAME = "libsrx_core.so"
ANDROID_LINK_API_LEVEL = "29"
APK_OUTPUT_DIR = Path("build/apk")
MODULE_OUTPUT_DIR = Path("build/zygisk")


@dataclass
class VersionInfo:
    name: str
    code: int


class Console:
    def __init__(self, verbose: bool) -> None:
        self.verbose = verbose

    def line(self, message: str) -> None:
        print(message)

    def info(self, message: str) -> None:
        print(f"[info] {message}")

    def ok(self, message: str) -> None:
        print(f"[ ok ] {message}")

    def step(self, index: int, total: int, message: str) -> None:
        print(f"[{index}/{total}] {message}")


def fail(message: str) -> None:
    raise SystemExit(f"error: {message}")


def shell_quote_pwsh(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def build_command(executable: str | Path, args: list[str]) -> list[str]:
    executable_text = str(executable)
    if os.name == "nt" and executable_text.lower().endswith((".bat", ".cmd")):
        command_line = "& " + shell_quote_pwsh(executable_text)
        for arg in args:
            command_line += " " + shell_quote_pwsh(arg)
        return ["pwsh", "-NoProfile", "-Command", command_line]
    return [executable_text, *args]


def run_process(console: Console, executable: str | Path, args: list[str], cwd: Path | None = None, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    cmd = build_command(executable, args)
    if console.verbose:
        wd = f" (wd: {cwd})" if cwd else ""
        console.info(f"run{wd}: {' '.join(cmd)}")
    result = subprocess.run(
        cmd,
        cwd=cwd,
        env={**os.environ, **(env or {})},
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
    )
    if console.verbose or result.returncode != 0:
        if result.stdout:
            print(result.stdout, end="")
        if result.stderr:
            print(result.stderr, end="", file=sys.stderr)
    return result


def resolve_project_root() -> Path:
    current = Path.cwd().resolve()
    for candidate in (current, *current.parents):
        if (candidate / "android" / "gradle.properties").is_file():
            return candidate
    fail("project root not found; run from project directory")


def read_version_info(project_root: Path) -> VersionInfo:
    props = project_root / "android" / "gradle.properties"
    if not props.is_file():
        fail(f"missing android/gradle.properties: {props}")
    version_name = None
    version_code = None
    for raw_line in props.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if line.startswith("appVersionName="):
            version_name = line.split("=", 1)[1].strip()
        elif line.startswith("appVersionCode="):
            value = line.split("=", 1)[1].strip()
            version_code = int(value) if value.isdigit() else None
    if not version_name:
        fail("appVersionName missing in gradle.properties")
    if version_code is None:
        fail("appVersionCode missing in gradle.properties")
    return VersionInfo(version_name, version_code)


def resolve_abis(abi: str | None) -> list[str]:
    if abi:
        if abi not in SUPPORTED_ABIS:
            fail(f"unsupported ABI: {abi} (supported: {', '.join(SUPPORTED_ABIS)})")
        return [abi]
    return list(SUPPORTED_ABIS)


def normalize_windows_path(value: str) -> Path:
    if os.name == "nt":
        value = value.replace("\\\\", "\\").replace("/", "\\")
    return Path(value)


def read_sdk_dir_from_local_properties(project_root: Path) -> Path | None:
    props = project_root / "android" / "local.properties"
    if not props.is_file():
        return None
    for raw_line in props.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw_line.strip()
        if not line.startswith("sdk.dir="):
            continue
        value = line.split("=", 1)[1].strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
            value = value[1:-1]
        return normalize_windows_path(value)
    return None


def find_android_sdk(project_root: Path) -> Path | None:
    for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(key)
        if value and Path(value).exists():
            return Path(value)
    local_sdk = read_sdk_dir_from_local_properties(project_root)
    if local_sdk and local_sdk.exists():
        return local_sdk
    home = Path.home()
    candidates = [home / "Android" / "Sdk", home / "AppData" / "Local" / "Android" / "Sdk"]
    return next((path for path in candidates if path.exists()), None)


def find_ndk(project_root: Path) -> Path | None:
    for key in ("ANDROID_NDK_HOME", "NDK_ROOT"):
        value = os.environ.get(key)
        if value and Path(value).exists():
            return Path(value)
    sdk = find_android_sdk(project_root)
    if not sdk:
        return None
    ndk_dir = sdk / "ndk"
    if not ndk_dir.exists():
        return None
    versions = sorted((path for path in ndk_dir.iterdir() if path.is_dir()), reverse=True)
    return versions[0] if versions else None


def ndk_prebuilt_dir() -> str:
    system = platform.system().lower()
    machine = platform.machine().lower()
    if system == "windows":
        return "windows-x86_64"
    if system == "darwin":
        return "darwin-arm64" if machine in ("arm64", "aarch64") else "darwin-x86_64"
    return "linux-x86_64"


def resolve_rust_target(abi: str) -> str:
    targets = {"arm64-v8a": "aarch64-linux-android", "x86_64": "x86_64-linux-android"}
    target = targets.get(abi)
    if not target:
        fail(f"unsupported Rust ABI: {abi}")
    return target


def remove_dir(path: Path) -> None:
    if path.exists():
        shutil.rmtree(path)


def ensure_rust_ready(console: Console, rust_root: Path) -> None:
    if not rust_root.exists():
        fail(f"Rust project dir not found: {rust_root}")
    if run_process(console, "cargo", ["--version"], cwd=rust_root).returncode != 0:
        fail("cargo not found; install Rust toolchain first")


def resolve_ndk(console: Console, project_root: Path, ndk_arg: str | None) -> Path:
    ndk = Path(ndk_arg) if ndk_arg else find_ndk(project_root)
    if not ndk:
        fail("Android NDK not found")
    if not ndk.exists():
        fail(f"NDK path not found: {ndk}")
    console.ok(f"ndk={ndk}")
    return ndk


def build_rust_module(console: Console, project_root: Path, rust_root: Path, libs_output_dir: Path, ndk_path: Path, abi: str, debug: bool) -> None:
    console.info(f"build Rust module abi={abi} so={CORE_SO_NAME}")
    target = resolve_rust_target(abi)
    toolchain_dir = ndk_path / "toolchains" / "llvm" / "prebuilt" / ndk_prebuilt_dir() / "bin"
    clang_name = f"{target}{ANDROID_LINK_API_LEVEL}-clang"
    candidates = [toolchain_dir / clang_name]
    if os.name == "nt":
        candidates = [toolchain_dir / f"{clang_name}.exe", toolchain_dir / f"{clang_name}.cmd", *candidates]
    clang = next((path for path in candidates if path.exists()), None)
    if not clang:
        fail("NDK clang not found")

    path_sep = ";" if os.name == "nt" else ":"
    env = {
        "ANDROID_NDK_HOME": str(ndk_path),
        f"CARGO_TARGET_{target.replace('-', '_').upper()}_LINKER": str(clang),
        "PATH": f"{toolchain_dir}{path_sep}{os.environ.get('PATH', '')}",
    }
    if debug:
        env.update({
            "SRX_BUILD_DEBUG": "1",
            "CARGO_PROFILE_RELEASE_LTO": "false",
            "CARGO_PROFILE_RELEASE_STRIP": "none",
            "CARGO_PROFILE_RELEASE_DEBUG": "true",
        })

    result = run_process(console, "cargo", ["build", "-p", "srx_core", "--release", "--target", target], cwd=project_root, env=env)
    if result.returncode != 0:
        fail(f"Rust build failed abi={abi}")

    built_so = project_root / "target" / target / "release" / CORE_SO_NAME
    if not built_so.exists():
        fail(f"Rust output not found: {built_so}")
    abi_output = libs_output_dir / abi
    abi_output.mkdir(parents=True, exist_ok=True)
    shutil.copy2(built_so, abi_output / CORE_SO_NAME)
    console.ok(f"Rust build done abi={abi} so={CORE_SO_NAME}")


def strip_and_hash(console: Console, libs_output_dir: Path, abi: str, ndk_path: Path, debug: bool) -> None:
    so_file = libs_output_dir / abi / CORE_SO_NAME
    if not so_file.exists():
        console.info(f"skip missing abi={abi} so={CORE_SO_NAME}")
        return
    if debug:
        console.info(f"skip strip (debug) abi={abi} so={CORE_SO_NAME}")
    else:
        strip_name = "llvm-strip.exe" if os.name == "nt" else "llvm-strip"
        llvm_strip = ndk_path / "toolchains" / "llvm" / "prebuilt" / ndk_prebuilt_dir() / "bin" / strip_name
        if llvm_strip.exists():
            result = run_process(console, llvm_strip, ["--strip-all", str(so_file)])
            if result.returncode == 0:
                console.ok(f"stripped abi={abi} so={CORE_SO_NAME}")
            else:
                console.info(f"strip failed abi={abi} so={CORE_SO_NAME}")
    digest = hashlib.sha256(so_file.read_bytes()).hexdigest()
    so_file.with_suffix(".so.sha256").write_text(digest, encoding="utf-8")
    console.ok(f"sha256 abi={abi} so={CORE_SO_NAME} hash={digest[:16]}...")


def copy_dir_recursive(source: Path, destination: Path) -> None:
    if not source.exists():
        fail(f"missing dir: {source}")
    if destination.exists():
        shutil.rmtree(destination)
    shutil.copytree(source, destination)


def package_module(console: Console, project_root: Path, module_assets_root: Path, libs_output_dir: Path, version: VersionInfo, abis: list[str]) -> None:
    console.info("package module")
    output_dir = project_root / MODULE_OUTPUT_DIR
    output_dir.mkdir(parents=True, exist_ok=True)
    temp_dir = project_root / "build" / "temp_pack"
    remove_dir(temp_dir)
    temp_dir.mkdir(parents=True, exist_ok=True)

    module_prop = (
        f"id={MODULE_ID}\n"
        f"name={MODULE_NAME}\n"
        f"version={version.name}\n"
        f"versionCode={version.code}\n"
        f"author={MODULE_AUTHOR}\n"
        f"description={MODULE_DESCRIPTION}\n"
        f"updateJson={MODULE_UPDATE_JSON}\n"
        "support=true\n"
    )
    (temp_dir / "module.prop").write_text(module_prop, encoding="utf-8")
    (temp_dir / "module_version.txt").write_text(f"{version.name}\n", encoding="utf-8")

    for name in ("customize.sh", "post-fs-data.sh", "service.sh", "sepolicy.rule", "uninstall.sh"):
        src = module_assets_root / name
        if not src.exists():
            fail(f"missing module template: {name}")
        shutil.copy2(src, temp_dir / name)
    copy_dir_recursive(module_assets_root / "service.d", temp_dir / "service.d")
    copy_dir_recursive(module_assets_root / "META-INF", temp_dir / "META-INF")

    zygisk_dir = temp_dir / "zygisk"
    zygisk_dir.mkdir(parents=True, exist_ok=True)
    for abi in abis:
        core_so = libs_output_dir / abi / CORE_SO_NAME
        core_sha = core_so.with_suffix(".so.sha256")
        if not core_so.exists():
            fail(f"missing {abi} core output: {core_so}")
        shutil.copy2(core_so, zygisk_dir / f"{abi}.so")
        if core_sha.exists():
            shutil.copy2(core_sha, zygisk_dir / f"{abi}.so.sha256")
        console.ok(f"added core lib abi={abi}")

    suffix = abis[0] if len(abis) == 1 else "zygisk"
    zip_name = f"{APP_NAME}_v{version.name}-{suffix}.zip"
    output_file = output_dir / zip_name
    with zipfile.ZipFile(output_file, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(temp_dir.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(temp_dir).as_posix())
    remove_dir(temp_dir)

    size_mb = output_file.stat().st_size / 1024 / 1024
    console.ok(f"package done file={zip_name} size_mb={size_mb:.2f}")
    if len(abis) > 1:
        write_update_json(console, output_dir, version, zip_name)


def write_update_json(console: Console, output_dir: Path, version: VersionInfo, zip_name: str) -> None:
    content = (
        "{\n"
        f"  \"version\": \"{version.name}\",\n"
        f"  \"versionCode\": {version.code},\n"
        f"  \"zipUrl\": \"{MODULE_RELEASE_BASE_URL}/{zip_name}\",\n"
        f"  \"changelog\": \"{MODULE_CHANGELOG_URL}\"\n"
        "}\n"
    )
    output_file = output_dir / "update.json"
    output_file.write_text(content, encoding="utf-8")
    console.ok(f"update json={output_file}")


def build_zygisk(args: argparse.Namespace) -> None:
    start = time.monotonic()
    console = Console(args.verbose)
    project_root = resolve_project_root()
    libs_output_dir = project_root / "build" / "zygisk_libs"
    if args.clean:
        remove_dir(libs_output_dir)
        console.ok("clean done")
        return

    version = read_version_info(project_root)
    console.info(f"module={MODULE_NAME} version={version.name} code={version.code}")
    console.step(1, 3, "Prepare NDK")
    ndk = resolve_ndk(console, project_root, args.ndk)
    ensure_rust_ready(console, project_root / "srx_core")
    abis = resolve_abis(args.abi)
    if args.debug:
        console.info("debug build: keep symbols, disable LTO, skip strip")
    console.step(2, 3, f"Build module abi={', '.join(abis)}")
    for abi in abis:
        build_rust_module(console, project_root, project_root / "srx_core", libs_output_dir, ndk, abi, args.debug)
        strip_and_hash(console, libs_output_dir, abi, ndk, args.debug)
    console.step(3, 3, "Package module")
    package_module(console, project_root, project_root / "assets" / "zygisk_module", libs_output_dir, version, abis)
    console.ok(f"build ok secs={time.monotonic() - start:.2f}")


def gradlew_path(project_root: Path) -> Path:
    return project_root / "android" / ("gradlew.bat" if os.name == "nt" else "gradlew")


def build_apk(args: argparse.Namespace) -> None:
    start = time.monotonic()
    console = Console(args.verbose)
    project_root = resolve_project_root()
    version = read_version_info(project_root)
    abis = resolve_abis(args.abi)
    console.info(f"version={version.name} abi={', '.join(abis)}")
    console.step(1, 2, "Build APK")
    android_dir = project_root / "android"
    result = run_process(console, gradlew_path(project_root), ["assembleRelease"], cwd=android_dir)
    if result.returncode != 0:
        fail("apk build failed")
    console.ok("apk build done")

    console.step(2, 2, "Collect APK")
    source_apk = project_root / "android" / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
    if not source_apk.exists():
        fail(f"release APK not found: {source_apk}")
    output_dir = project_root / APK_OUTPUT_DIR
    output_dir.mkdir(parents=True, exist_ok=True)
    suffix = abis[0] if len(abis) == 1 else "universal"
    target = output_dir / f"{APP_NAME}_{version.name}_{suffix}.apk"
    if target.exists():
        target.unlink()
    shutil.move(str(source_apk), str(target))
    console.ok(f"apk={target.relative_to(project_root)} size_mb={target.stat().st_size / 1024 / 1024:.2f}")
    console.ok(f"done ({time.monotonic() - start:.2f}s)")


def clean_output_directories(console: Console, project_root: Path) -> None:
    cleaned = False
    for output_dir in (project_root / MODULE_OUTPUT_DIR, project_root / APK_OUTPUT_DIR):
        if not output_dir.exists():
            continue
        for path in output_dir.iterdir():
            if path.is_file():
                path.unlink()
                cleaned = True
    if cleaned:
        console.ok("output dirs cleaned")


def list_output_files(console: Console, project_root: Path) -> None:
    console.info("output files")
    for output_dir in (project_root / MODULE_OUTPUT_DIR, project_root / APK_OUTPUT_DIR):
        if not output_dir.exists():
            continue
        for path in sorted(output_dir.iterdir()):
            if path.is_file():
                rel = path.relative_to(project_root)
                console.line(f"  - {rel} ({path.stat().st_size / 1024 / 1024:.2f} MB)")


def build_all(args: argparse.Namespace) -> None:
    start = time.monotonic()
    console = Console(args.verbose)
    project_root = resolve_project_root()
    version = read_version_info(project_root)
    abis = resolve_abis(args.abi)
    console.info(f"version={version.name} abi={', '.join(abis)}")
    console.info("module_output=build/zygisk apk_output=build/apk")
    clean_output_directories(console, project_root)
    console.step(1, 2, "Build Zygisk module")
    zygisk_args = argparse.Namespace(clean=False, verbose=args.verbose, abi=args.abi, ndk=args.ndk, debug=args.debug)
    build_zygisk(zygisk_args)
    console.step(2, 2, "Build release APK")
    apk_args = argparse.Namespace(verbose=args.verbose, abi=args.abi)
    build_apk(apk_args)
    console.ok(f"build all done ({time.monotonic() - start:.2f}s)")
    list_output_files(console, project_root)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="srx-build", description="Storage Redirect X build tool")
    sub = parser.add_subparsers(dest="command", required=True)

    zygisk = sub.add_parser("build-zygisk")
    zygisk.add_argument("-c", "--clean", action="store_true", help="Only clean build output")
    zygisk.add_argument("-v", "--verbose", action="store_true", help="Show verbose logs")
    zygisk.add_argument("--abi", choices=SUPPORTED_ABIS, help="Build only the specified ABI")
    zygisk.add_argument("--ndk", help="NDK path")
    zygisk.add_argument("--debug", action="store_true", help="Keep symbols, disable LTO and skip strip")
    zygisk.set_defaults(func=build_zygisk)

    apk = sub.add_parser("build-apk")
    apk.add_argument("-v", "--verbose", action="store_true", help="Show verbose logs")
    apk.add_argument("--abi", choices=SUPPORTED_ABIS, help="Target ABI")
    apk.set_defaults(func=build_apk)

    all_cmd = sub.add_parser("build-all")
    all_cmd.add_argument("-v", "--verbose", action="store_true", help="Show verbose logs")
    all_cmd.add_argument("--abi", choices=SUPPORTED_ABIS, help="Target ABI")
    all_cmd.add_argument("--ndk", help="NDK path")
    all_cmd.add_argument("--debug", action="store_true", help="Build the zygisk module with debug symbols")
    all_cmd.set_defaults(func=build_all)
    return parser


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    args = build_parser().parse_args()
    args.func(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
