<div align="center">

# Storage Redirect X

[![English](https://img.shields.io/badge/English-red)](README.md)
[![简体中文](https://img.shields.io/badge/简体中文-blue)](.github/docs/README.zh-CN.md)

![Android](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)
![Root](https://img.shields.io/badge/Root-Required-critical)
![Zygisk](https://img.shields.io/badge/Zygisk-Required-orange)

Android Zygisk module + Kotlin Compose manager app for storage redirection.

</div>

## Quick Navigation

- [Prerequisites](#prerequisites)
- [What It Does](#what-it-does)
- [Common Use Cases](#common-use-cases)
- [Quick Start](#quick-start)
- [In-App Configuration Flow](#in-app-configuration-flow)
- [How Mapping Works](#how-mapping-works)
- [Manual Configuration](#manual-configuration)
- [Configuration Starter Samples](#configuration-starter-samples)
- [Build](#build)

## Prerequisites

> **IMPORTANT**
> Before using this project, make sure:
> - Your device is rooted.
> - Zygisk is enabled.
> - The module is installed and the device has been rebooted once.

## What It Does

- Redirects app shared-storage operations through Zygisk hooks and mount namespace rules.
- Supports separate rules for each Android user (`users.{userId}`) on multi-user devices.
- Supports per-app real-path rules (`allowed_real_paths`) and path rewrite rules (`path_mappings`).
- Supports shared UID synchronization in the manager app so related packages keep consistent rules.
- Provides runtime logs, file monitor logs, templates, backup/restore, update checks, and UI settings.
- Provides optional FuseFixer for MediaProvider FUSE path compatibility.

## Common Use Cases

- Keep shared storage cleaner by reducing direct app writes in common folders.
- Isolate heavy-writing apps to avoid cross-app path pollution.
- Apply different redirect behavior for owner, secondary user, and work profile users.
- Fine-tune request-to-final path mapping for compatibility with specific apps.
- Monitor file operations and inspect whether rules are taking effect.

## Quick Start

1. Ensure your device is rooted and Zygisk is enabled.
2. Install the Storage Redirect X module zip in your root manager.
3. Reboot the device.
4. Install and open the manager APK.
5. Grant root permission to the manager app.
6. Go to `Apps` -> select a user and app -> enable redirect.
7. Add allowed paths or path mappings, then save.

## In-App Configuration Flow

1. Open `Apps` and choose the target user/app.
2. Turn on `Enable Redirect`.
3. Optional: add entries in `Allowed Paths`.
4. Optional: add entries in `Path Mappings` (`request_path` -> `final_path`).
5. Save config.

> **NOTE**
> After saving, the target app process will be stopped automatically to apply changes on next launch.

## How Mapping Works

Assume app `com.example` on user `0`.
When redirect is enabled, the default redirect base is:

```text
/storage/emulated/0/Android/data/com.example/sdcard
```

So by default, app access like:

```text
/storage/emulated/0/DCIM/MyApp/a.jpg
```

is redirected to:

```text
/storage/emulated/0/Android/data/com.example/sdcard/DCIM/MyApp/a.jpg
```

Then exception rules are applied on top of that default behavior:

| Priority | Rule | Behavior |
| --- | --- | --- |
| 1 | `path_mappings` | Longest matching `request_path` prefix rewrites to `final_path`. |
| 2 | `allowed_real_paths` exclusions | Rules prefixed with `!` are treated as not allowed and go back to fallback redirect. |
| 3 | `allowed_real_paths` | Matched path is restored to the same real path instead of the default redirect path. |
| 4 | Fallback redirect | Other storage paths are moved under the default redirect base. |

How user-input relative paths are resolved:

- If current user is `0`:
  - `path_mappings.request_path = "DCIM/MyApp"` -> `/storage/emulated/0/DCIM/MyApp`
  - `path_mappings.final_path = "Pictures/MyApp"` -> `/storage/emulated/0/Pictures/MyApp`
  - `allowed_real_paths = "Download/Public"` -> `/storage/emulated/0/Download/Public`
- If current user is `10`, the same config becomes:
  - `/storage/emulated/10/DCIM/MyApp`
  - `/storage/emulated/10/Pictures/MyApp`
  - `/storage/emulated/10/Download/Public`

`path_mappings` example:

- Rule: `DCIM/MyApp -> Pictures/MyApp`
- Input: `/storage/emulated/0/DCIM/MyApp/a.jpg`
- Output: `/storage/emulated/0/Pictures/MyApp/a.jpg`

If no rule matches:

- Input: `/storage/emulated/0/Download/a.txt`
- Output: `/storage/emulated/0/Android/data/com.example/sdcard/Download/a.txt`

If path matches `allowed_real_paths`:

- Allowed path: `Download/Public`
- Input: `/storage/emulated/0/Download/Public/a.txt`
- Output: unchanged

If an exclusion rule matches:

- Allowed path: `Download/*`
- Exclusion path: `!Download/Private`
- Input: `/storage/emulated/0/Download/Private/a.txt`
- Output: `/storage/emulated/0/Android/data/com.example/sdcard/Download/Private/a.txt`

### When `allowed_real_paths` and `path_mappings` Are Both Set

They can be used together. Practical behavior:

1. No overlap: each rule does its own job  
`allowed_real_paths` restores listed directories to real paths, while `path_mappings` rewrites listed request directories to different final directories.
2. Parent + child overlap: child mapping still applies  
Example: allow `DCIM`, and map `DCIM/MyApp -> Pictures/MyApp`. Other files under `DCIM` stay in real `DCIM`, but `DCIM/MyApp` goes to mapped target.
3. Exact same path in both: mapping wins  
Example: allow `Download/Public`, and map `Download/Public -> Documents/Public`. Final path follows mapping target `Documents/Public`.

This matches implementation order: runtime routing evaluates mappings before allowed rules, and mount preparation restores allowed paths before applying mappings.

## Manual Configuration

Config directory on device:

```text
/data/adb/modules/storage.redirect.x/config/
├─ global.json
└─ apps/
   └─ <package_name>.json
```

### `global.json`

```json
{
  "file_monitor_enabled": true,
  "fuse_fixer_enabled": false
}
```

- `file_monitor_enabled`: enables file operation monitoring for MediaProvider and redirected apps.
- `fuse_fixer_enabled`: enables the optional MediaProvider FUSE path compatibility hook.

### `apps/<package>.json` Template

```json
{
  "users": {
    "0": {
      "enabled": true,
      "allowed_real_paths": [
        "Download/MyApp",
        "Documents/MyApp",
        "Pictures/*",
        "!Pictures/Private"
      ],
      "path_mappings": {
        "DCIM/MyApp": "Pictures/MyApp"
      }
    }
  }
}
```

`path_mappings` also accepts the compatibility array form:

```json
{
  "path_mappings": [
    {
      "request_path": "DCIM/MyApp",
      "final_path": "Pictures/MyApp"
    }
  ]
}
```

### Path Rules

- Paths are relative to `/storage/emulated/<userId>`.
- Do not start with `/`.
- Do not include `.` or `..` path segments.
- Do not configure Android private storage such as `Android/data`, `Android/media`, or `Android/obb`; the manager rejects `Android/data`.
- `allowed_real_paths` supports `*` and `?` wildcards.
- `allowed_real_paths` supports `!` exclusion prefix, for example `!Download/Private`.
- `path_mappings` only accepts plain relative paths; `!`, `*`, and `?` are not supported there.
- `request_path` and `final_path` cannot be identical.

### Apply Manual Changes

- Manager app saves trigger runtime hot reload; running redirected apps are reconciled without being force-stopped.
- When module service is running, manual JSON changes under `config/apps` are watched and effective config changes are hot-reloaded by the daemon.
- If changes are not applied, run module action `Reload Redirect`; restart affected apps or reboot only as a fallback.

## Configuration Starter Samples

### Minimal Per-App Config

```json
{
  "users": {
    "0": {
      "enabled": true
    }
  }
}
```

### Multi-User Config Sample

```json
{
  "users": {
    "0": {
      "enabled": true,
      "allowed_real_paths": ["Download/OwnerOnly"]
    },
    "10": {
      "enabled": true,
      "allowed_real_paths": ["Download/WorkProfile"],
      "path_mappings": {
        "Movies/Input": "Movies/WorkMapped"
      }
    }
  }
}
```

## Build

Supported ABIs:

- `arm64-v8a`
- `x86_64`

Build Zygisk module:

```bash
PYTHONUTF8=1 python scripts/build.py build-zygisk
PYTHONUTF8=1 python scripts/build.py build-zygisk --debug
PYTHONUTF8=1 python scripts/build.py build-zygisk --abi arm64-v8a
PYTHONUTF8=1 python scripts/build.py build-zygisk --abi x86_64 --debug
```

The module zip is written to `build/zygisk/` as:

```text
Storage-Redirect-X_v<version>-<abi-or-zygisk>.zip
```

Build APK:

```bash
PYTHONUTF8=1 python scripts/build.py build-apk
PYTHONUTF8=1 python scripts/build.py build-apk --abi arm64-v8a
```

The release APK is moved to `build/apk/` as:

```text
Storage-Redirect-X_<version>_<abi-or-universal>.apk
```

Build all:

```bash
PYTHONUTF8=1 python scripts/build.py build-all
PYTHONUTF8=1 python scripts/build.py build-all --debug
```

Useful options:

- `--abi arm64-v8a|x86_64`: build only one ABI.
- `--debug`: keep symbols, disable LTO, and skip strip for easier crash stack analysis.
- `--ndk <path>`: specify Android NDK path for module builds.
- `-v`: show verbose build logs.

## Build Requirements

- Python 3.10+
- JDK with `javac` available
- Android SDK with build-tools (`d8`) and platform `android-37`
- Android NDK for `arm64-v8a` / `x86_64`
- CMake 3.28+
- Ninja
- Rust toolchain (`>= 1.93.1`) with Android targets installed
