<div align="center">

# Storage Redirect X

[![English](https://img.shields.io/badge/English-red)](../../README.md)
[![简体中文](https://img.shields.io/badge/简体中文-blue)](README.zh-CN.md)

![Android](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)
![Root](https://img.shields.io/badge/Root-必需-critical)
![Zygisk](https://img.shields.io/badge/Zygisk-必需-orange)

Android Zygisk 模块 + Kotlin Compose 管理应用，用于存储重定向。

</div>

## 快速导航

- [前置条件](#前置条件)
- [本应用的作用](#本应用的作用)
- [应用场景](#应用场景)
- [开始](#开始)
- [具体使用方法](#具体使用方法)
- [映射是如何工作的](#映射是如何工作的)
- [手动配置](#手动配置)
- [配置样板](#配置样板)
- [构建命令](#构建命令)

## 前置条件

> **重要**
> 使用前请先确认:
> - 设备已 Root
> - 已启用 Zygisk
> - 模块已安装，并且设备已至少重启一次

## 本应用的作用

- 通过 Zygisk Hook 和挂载命名空间规则拦截应用共享存储访问并执行重定向。
- 支持多用户分别配置，每个用户都有独立规则，对应 `users.{userId}`。
- 支持每个应用配置真实路径规则 `allowed_real_paths` 和路径改写规则 `path_mappings`。
- 管理应用支持共享 UID 同步，关联包可以保持一致规则。
- 提供运行日志、文件监控日志、模板、备份还原、更新检查和界面设置。
- 提供可选 FuseFixer，用于提升 MediaProvider FUSE 路径兼容性。

## 应用场景

- 你希望减少应用在公共目录中的杂乱写入。
- 你希望把高频写入应用隔离，降低路径冲突。
- 你在多用户、第二用户或工作资料场景中，需要不同用户不同策略。
- 你需要为特定应用做精确的请求路径到最终路径映射。
- 你需要通过文件监控查看规则是否生效。

## 开始

1. 确认设备已 Root，且 Zygisk 已开启。
2. 在 Root 管理器中安装 Storage Redirect X 模块 zip。
3. 重启设备。
4. 安装并打开管理应用 APK。
5. 给管理应用授予 Root。
6. 进入 `应用` 页面，选择目标用户和目标应用。
7. 开启 `开启重定向`，按需配置路径后保存。

## 具体使用方法

1. 在 `应用` 页面切换到正确的用户与目标应用。
2. 打开 `开启重定向`。
3. 在 `允许访问路径` 中添加允许直接访问的相对路径。
4. 在 `路径映射` 中配置 `request_path -> final_path`。
5. 点击保存。

> **说明**
> 保存后会自动停止目标应用进程，目标应用下次启动时应用新配置。

## 映射是如何工作的

假设应用包名是 `com.example`，用户是 `0`。
开启重定向后，默认重定向根目录是:

```text
/storage/emulated/0/Android/data/com.example/sdcard
```

也就是默认情况下，应用访问:

```text
/storage/emulated/0/DCIM/MyApp/a.jpg
```

会被重定向到:

```text
/storage/emulated/0/Android/data/com.example/sdcard/DCIM/MyApp/a.jpg
```

在这个默认重定向基础上，再按以下优先级处理例外规则:

| 优先级 | 规则 | 行为 |
| --- | --- | --- |
| 1 | `path_mappings` | 按 `request_path` 最长前缀匹配，改写到 `final_path`。 |
| 2 | `allowed_real_paths` 排除规则 | 以 `!` 开头的规则视为不允许，重新走默认重定向。 |
| 3 | `allowed_real_paths` | 命中后恢复同路径真实目录，不走默认重定向目录。 |
| 4 | 默认重定向 | 其余存储路径移动到默认重定向根目录下。 |

用户输入的相对路径会先拼接到当前用户目录:

- 当前用户是 `0` 时:
  - `path_mappings.request_path = "DCIM/MyApp"` -> `/storage/emulated/0/DCIM/MyApp`
  - `path_mappings.final_path = "Pictures/MyApp"` -> `/storage/emulated/0/Pictures/MyApp`
  - `allowed_real_paths = "Download/Public"` -> `/storage/emulated/0/Download/Public`
- 当前用户是 `10` 时，同样配置会变成:
  - `/storage/emulated/10/DCIM/MyApp`
  - `/storage/emulated/10/Pictures/MyApp`
  - `/storage/emulated/10/Download/Public`

`path_mappings` 示例:

- 映射规则: `DCIM/MyApp -> Pictures/MyApp`
- 输入路径: `/storage/emulated/0/DCIM/MyApp/a.jpg`
- 输出路径: `/storage/emulated/0/Pictures/MyApp/a.jpg`

若未命中任何规则:

- 输入路径: `/storage/emulated/0/Download/a.txt`
- 输出路径: `/storage/emulated/0/Android/data/com.example/sdcard/Download/a.txt`

若命中 `allowed_real_paths`:

- 允许路径: `Download/Public`
- 输入路径: `/storage/emulated/0/Download/Public/a.txt`
- 输出路径: 保持不变

若命中排除规则:

- 允许路径: `Download/*`
- 排除路径: `!Download/Private`
- 输入路径: `/storage/emulated/0/Download/Private/a.txt`
- 输出路径: `/storage/emulated/0/Android/data/com.example/sdcard/Download/Private/a.txt`

### `allowed_real_paths` 和 `path_mappings` 同时配置时

它们可以同时存在，实际效果按下面理解:

1. 不冲突时，各管各的  
`allowed_real_paths` 负责把指定目录恢复到真实路径，`path_mappings` 负责把指定请求目录改写到另一个最终目录。
2. 父子目录同时出现时，子目录映射生效  
例如允许 `DCIM`，同时映射 `DCIM/MyApp -> Pictures/MyApp`，那么 `DCIM` 其他内容走真实 `DCIM`，但 `DCIM/MyApp` 走映射。
3. 同一路径同时被允许和映射时，映射优先  
例如允许 `Download/Public`，同时映射 `Download/Public -> Documents/Public`，最终会走映射目标 `Documents/Public`。

这是因为运行时路由会先判断映射再判断允许规则，挂载准备也会先恢复允许路径再应用映射。

## 手动配置

设备上的配置目录:

```text
/data/adb/modules/storage.redirect.x/config/
├─ global.json
└─ apps/
   └─ <包名>.json
```

### 全局配置 `global.json`

```json
{
  "file_monitor_enabled": true,
  "fuse_fixer_enabled": false
}
```

- `file_monitor_enabled`: 开启文件操作监控，覆盖 MediaProvider 和已启用重定向的应用。
- `fuse_fixer_enabled`: 开启可选的 MediaProvider FUSE 路径兼容 Hook。

### 应用配置 `apps/<包名>.json` 样板

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

`path_mappings` 也兼容数组形式:

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

### 路径规则

- 路径都相对于 `/storage/emulated/<userId>`。
- 不能以 `/` 开头。
- 不能包含 `.` 或 `..` 路径段。
- 不要配置 `Android/data`、`Android/media`、`Android/obb` 等 Android 私有存储目录；管理应用会明确拒绝 `Android/data`。
- `allowed_real_paths` 支持 `*` 和 `?` 通配符。
- `allowed_real_paths` 支持 `!` 排除前缀，例如 `!Download/Private`。
- `path_mappings` 只接受普通相对路径，不支持 `!`、`*`、`?`。
- `request_path` 和 `final_path` 不能相同。

### 手动改配置后如何生效

- 管理应用保存时会自动停止受影响应用，目标应用下次启动时应用新配置。
- 模块服务运行时会监听 `config/apps` 下的手动 JSON 变更，生效配置变化后会自动停止受影响包名。
- 如果没有自动生效，可以执行模块操作 `Reload Redirect`，或手动重启相关应用，也可以直接重启设备。

## 配置样板

### 最小可用样板

```json
{
  "users": {
    "0": {
      "enabled": true
    }
  }
}
```

### 多用户样板

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

## 构建命令

支持的 ABI:

- `arm64-v8a`
- `x86_64`

构建 Zygisk 模块:

```bash
PYTHONUTF8=1 python scripts/build.py build-zygisk
PYTHONUTF8=1 python scripts/build.py build-zygisk --debug
PYTHONUTF8=1 python scripts/build.py build-zygisk --abi arm64-v8a
PYTHONUTF8=1 python scripts/build.py build-zygisk --abi x86_64 --debug
```

模块 zip 会生成到 `build/zygisk/`，文件名格式为:

```text
Storage-Redirect-X_v<版本>-<abi 或 zygisk>.zip
```

构建 APK:

```bash
PYTHONUTF8=1 python scripts/build.py build-apk
PYTHONUTF8=1 python scripts/build.py build-apk --abi arm64-v8a
```

发布 APK 会移动到 `build/apk/`，文件名格式为:

```text
Storage-Redirect-X_<版本>_<abi 或 universal>.apk
```

完整构建:

```bash
PYTHONUTF8=1 python scripts/build.py build-all
PYTHONUTF8=1 python scripts/build.py build-all --debug
```

常用参数:

- `--abi arm64-v8a|x86_64`: 只构建指定 ABI。
- `--debug`: 保留符号、关闭 LTO、跳过 strip，便于分析崩溃栈。
- `--ndk <路径>`: 指定 Android NDK 路径，用于模块构建。
- `-v`: 输出详细构建日志。

## 环境要求

- Python 3.10+
- 可用的 JDK 和 `javac`
- Android SDK，包含 build-tools 里的 `d8` 和 `android-37` 平台
- Android NDK，支持 `arm64-v8a` / `x86_64`
- CMake 3.28+
- Ninja
- Rust 工具链版本 `>= 1.93.1`，并安装 Android 目标
