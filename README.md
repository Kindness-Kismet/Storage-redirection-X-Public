<div align="center">

# Storage Redirect X

[![English](https://img.shields.io/badge/English-red)](README.md)
[![简体中文](https://img.shields.io/badge/简体中文-blue)](README.zh-CN.md)

![Android](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)
![Root](https://img.shields.io/badge/Root-Required-critical)
![Zygisk](https://img.shields.io/badge/Zygisk-Required-orange)

Release repository for Storage Redirect X packages (APK + Zygisk module). Source code is not included.

</div>

## Quick Navigation

- [Release Contents](#release-contents)
- [Prerequisites](#prerequisites)
- [What It Does](#what-it-does)
- [Common Use Cases](#common-use-cases)
- [Quick Start](#quick-start)
- [In-App Configuration Flow](#in-app-configuration-flow)
- [How Mapping Works](#how-mapping-works)
- [Manual Configuration](#manual-configuration)
- [Configuration Starter Samples](#configuration-starter-samples)
- [Notes](#notes)

## Release Contents

- `storage.redirect.x_<version>-arm64-v8a.apk`
- `storage.redirect.x_<version>-x86_64.apk`
- `storage-redirect-x_v<version>-zygisk.zip`

## Prerequisites

> **IMPORTANT**
> Before using this app:
> - Your device is rooted.
> - Zygisk is enabled.

## What It Does

- Redirects app storage operations through Zygisk hooks.
- Supports separate rules for each device user (`users.{userId}`) on multi-user devices.
- Lets you set real-path whitelist rules (`allowed_real_paths`) and path rewrite rules (`path_mappings`) per app.
- Provides runtime logs, file monitor logs, and in-app config management.

## Common Use Cases

- Keep shared storage cleaner by reducing direct app writes in common folders.
- Isolate heavy-writing apps to avoid cross-app path pollution.
- Apply different redirect behavior for owner and work profile users.
- Fine-tune virtual-to-real path mapping for compatibility with specific apps.

## Quick Start

1. Ensure your device is rooted and Zygisk is enabled.
2. Install `storage-redirect-x_v<version>-zygisk.zip` in your root manager.
3. Reboot the device.
4. Install and open the manager APK.
5. Grant root permission to the manager app.
6. Go to `Apps` -> select an app -> enable redirect.
7. Add allowed paths or path mappings, then save.

## In-App Configuration Flow

1. Open `Apps` and choose target user/app.
2. Turn on `Enable Redirect`.
3. Optional: add entries in `Allowed Paths`.
4. Optional: add entries in `Path Mappings` (`virtual_path` -> `real_path`).
5. Save config.

> **NOTE**
> After saving, the target app process will be stopped automatically to apply changes.

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
| 1 | `path_mappings` | Longest matching `virtual_path` prefix rewrites to a target real path. |
| 2 | `allowed_real_paths` | Matched path is restored to the same real path instead of default redirect path. |
| 3 | Fallback redirect | Other storage paths are moved under default redirect base. |

How user-input relative paths are resolved:

- If current user is `0`:
  - `path_mappings.virtual_path = "DCIM/MyApp"` -> `/storage/emulated/0/DCIM/MyApp`
  - `path_mappings.real_path = "Pictures/MyApp"` -> `/storage/emulated/0/Pictures/MyApp`
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

### When `allowed_real_paths` and `path_mappings` Are Both Set

They can be used together. Practical behavior:

1. No overlap: each rule does its own job.  
`allowed_real_paths` restores listed directories to real paths, while `path_mappings` rewrites listed virtual directories to different real paths.
2. Parent + child overlap: child mapping still applies.  
Example: allow `DCIM`, and map `DCIM/MyApp -> Pictures/MyApp`. Other files under `DCIM` stay in real `DCIM`, but `DCIM/MyApp` goes to mapped target.
3. Exact same path in both: mapping wins.  
Example: allow `Download/Public`, and map `Download/Public -> Documents/Public`. Final path follows mapping target `Documents/Public`.

This matches implementation order: mapping has higher decision priority than allowed paths, and mount flow applies mapping after allowed-path restore.

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
  "file_monitor_enabled": true
}
```

### `apps/<package>.json` Template

```json
{
  "users": {
    "0": {
      "enabled": true,
      "allowed_real_paths": [
        "Download/MyApp",
        "Documents/MyApp"
      ],
      "path_mappings": {
        "DCIM/MyApp": "Pictures/MyApp"
      }
    }
  }
}
```

### Path Rules

- Use relative paths only.
- Do not start with `/`.
- Do not include `..`.
- Do not start with `Android`.
- `virtual_path` and `real_path` cannot be identical.

### Apply Manual Changes

- Recommended: trigger module action `重载重定向`.
- Alternative: restart affected apps or reboot device.

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

## Notes

- Keep APK and Zygisk module versions aligned.
- Reboot after upgrade to avoid stale process cache.
- Release assets with the same filename may be replaced, always install the latest version.
