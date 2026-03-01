<div align="center">

# Storage Redirect X

[![English](https://img.shields.io/badge/English-red)](README.md)
[![简体中文](https://img.shields.io/badge/简体中文-blue)](README.zh-CN.md)

![Android](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)
![Root](https://img.shields.io/badge/Root-必需-critical)
![Zygisk](https://img.shields.io/badge/Zygisk-必需-orange)

Storage Redirect X 发布仓库，仅提供安装包与模块包，不包含源码。

</div>

## 快速导航

- [发布内容](#发布内容)
- [前置条件](#前置条件)
- [本应用的作用](#本应用的作用)
- [应用场景](#应用场景)
- [开始](#开始)
- [具体使用方法](#具体使用方法)
- [映射是如何工作的](#映射是如何工作的)
- [手动配置](#手动配置)
- [配置样板](#配置样板)
- [注意事项](#注意事项)

## 发布内容

- `storage.redirect.x_<版本>-arm64-v8a.apk`
- `storage.redirect.x_<版本>-x86_64.apk`
- `storage-redirect-x_v<版本>-zygisk.zip`

## 前置条件

> **重要**
> 使用前请先确认:
> - 设备已 Root
> - 已启用 Zygisk

## 本应用的作用

- 通过 Zygisk Hook 拦截应用文件访问并执行重定向。
- 支持多用户分别配置，每个用户都有独立规则，对应 `users.{userId}`。
- 支持每个应用配置真实路径白名单 `allowed_real_paths` 和路径改写规则 `path_mappings`。
- 提供运行日志和文件监控日志，便于排查生效情况。

## 应用场景

- 你希望减少应用在公共目录中的杂乱写入。
- 你希望把高频写入应用隔离，降低路径冲突。
- 你在多用户或工作资料场景中，需要不同用户不同策略。
- 你需要为特定应用做精确的虚拟路径到真实路径映射。

## 开始

1. 确认设备已 Root，且 Zygisk 已开启。
2. 在 Root 管理器中安装 `storage-redirect-x_v<版本>-zygisk.zip`。
3. 重启设备。
4. 安装并打开管理应用 APK。
5. 给管理应用授予 Root。
6. 进入 `应用` 页面，选择目标应用。
7. 开启 `开启重定向`，按需配置路径后保存。

## 具体使用方法

1. 在 `应用` 页面切换到正确的用户与目标应用。
2. 打开 `开启重定向`。
3. 在 `允许访问路径` 中添加允许直接访问的相对路径。
4. 在 `路径映射` 中配置 `虚拟路径 -> 真实路径`。
5. 点击保存。

> **说明**
> 保存后会自动停止目标应用进程，使配置立即生效。

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

会落到:

```text
/storage/emulated/0/Android/data/com.example/sdcard/DCIM/MyApp/a.jpg
```

在这个默认重定向基础上，再按以下优先级做例外规则:

| 优先级 | 规则 | 行为 |
| --- | --- | --- |
| 1 | `path_mappings` | 按 `virtual_path` 最长前缀匹配，把该目录改写到指定真实路径。 |
| 2 | `allowed_real_paths` | 命中后恢复同路径真实目录，不走默认重定向目录。 |
| 3 | 默认重定向 | 其余存储路径搬到默认重定向根目录下。 |

用户输入的相对路径会先拼接到当前用户目录:

- 当前用户是 `0` 时:
  - `path_mappings.virtual_path = "DCIM/MyApp"` -> `/storage/emulated/0/DCIM/MyApp`
  - `path_mappings.real_path = "Pictures/MyApp"` -> `/storage/emulated/0/Pictures/MyApp`
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

### `allowed_real_paths` 和 `path_mappings` 同时配置时

它们可以同时存在，实际效果按下面理解:

1. 不冲突时，各管各的  
`allowed_real_paths` 负责把指定目录恢复到真实路径，`path_mappings` 负责把指定虚拟目录改写到另一真实目录。
2. 父子目录同时出现时，子目录映射生效  
例如允许 `DCIM`，同时映射 `DCIM/MyApp -> Pictures/MyApp`，那么 `DCIM` 其他内容走真实 `DCIM`，但 `DCIM/MyApp` 走映射。
3. 同一路径同时被允许和映射时，映射优先  
例如允许 `Download/Public`，同时映射 `Download/Public -> Documents/Public`，最终会走映射目标 `Documents/Public`。

这是因为实现里路径映射的判定优先级高于允许路径，且挂载阶段也是先恢复允许路径再应用映射。

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
  "file_monitor_enabled": true
}
```

### 应用配置 `apps/<包名>.json` 样板

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

### 路径规则

- 只能使用相对路径。
- 不能以 `/` 开头。
- 不能包含 `..`。
- 不能以 `Android` 开头。
- `virtual_path` 和 `real_path` 不能相同。

### 手动改配置后如何生效

- 推荐: 在模块操作里执行 `重载重定向`。
- 备选: 手动重启相关应用或直接重启设备。

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

## 注意事项

- 建议 APK 与 Zygisk 模块版本保持一致。
- 升级后建议重启设备，避免旧进程缓存配置。
- 发布会覆盖同名资产，请始终使用最新版本文件进行安装。
