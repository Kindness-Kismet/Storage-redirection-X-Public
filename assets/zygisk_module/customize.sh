#!/system/bin/sh

SKIPUNZIP=1

MODULE_VERSION="unknown"
MODULE_ID="storage.redirect.x"
ACTIVE_MODULE_DIR="/data/adb/modules/$MODULE_ID"
BACKUP_CONFIG_DIR="$MODPATH/.backup_config"
LEGACY_MODULE_IDS="storage_redirect_x"

# 从 ZIP 中提取版本号（解压前）
unzip -o "$ZIPFILE" module_version.txt -d "$MODPATH" >&2 2>/dev/null
VERSION_FILE="$MODPATH/module_version.txt"
if [ -f "$VERSION_FILE" ]; then
  module_version_candidate=$(cat "$VERSION_FILE" 2>/dev/null)
  if [ -n "$module_version_candidate" ]; then
    MODULE_VERSION="$module_version_candidate"
  fi
fi

ui_print "-- Storage Redirect X"
ui_print "-- version=$MODULE_VERSION"

print_progress() {
  ui_print "-- [$1%] $2"
}

safe_cleanup_modpath() {
  case "$MODPATH" in
    /data/adb/modules/*)
      rm -rf "$MODPATH"
      ;;
    *)
      ui_print "-- warn: skip unsafe cleanup path=$MODPATH"
      ;;
  esac
}

backup_existing_config() {
  if [ "$ACTIVE_MODULE_DIR" = "$MODPATH" ]; then
    return 0
  fi

  if [ ! -d "$ACTIVE_MODULE_DIR/config" ]; then
    return 0
  fi

  ui_print "-- backup existing config"
  rm -rf "$BACKUP_CONFIG_DIR"
  mkdir -p "$BACKUP_CONFIG_DIR"

  if ! cp -rf "$ACTIVE_MODULE_DIR/config/." "$BACKUP_CONFIG_DIR/" 2>/dev/null; then
    ui_print "-- warn: config backup failed, continue"
    rm -rf "$BACKUP_CONFIG_DIR"
    return 0
  fi
}

restore_existing_config() {
  if [ ! -d "$BACKUP_CONFIG_DIR" ]; then
    return 0
  fi

  ui_print "-- restore config"
  mkdir -p "$MODPATH/config"

  if ! cp -rf "$BACKUP_CONFIG_DIR/." "$MODPATH/config/" 2>/dev/null; then
    ui_print "-- warn: config restore failed"
  fi

  rm -rf "$BACKUP_CONFIG_DIR"
}

# 清理旧模块 ID 残留，防止 ZygiskNext 同时加载多份 so
for legacy_id in $LEGACY_MODULE_IDS; do
  legacy_dir="/data/adb/modules/$legacy_id"
  if [ -d "$legacy_dir" ]; then
    ui_print "-- remove legacy module id=$legacy_id"
    rm -rf "$legacy_dir"
  fi
done

print_progress 10 "prepare module"
backup_existing_config

# 解压文件
print_progress 20 "extract module files"
unzip -o "$ZIPFILE" -d "$MODPATH" >&2

# 获取设备架构
ARCH=$(getprop ro.product.cpu.abi 2>/dev/null)
if [ -z "$ARCH" ]; then
  ABILIST64=$(getprop ro.product.cpu.abilist64 2>/dev/null)
  if [ -n "$ABILIST64" ]; then
    ARCH=$(echo "$ABILIST64" | awk -F',' '{print $1}')
  else
    ABILIST=$(getprop ro.product.cpu.abilist 2>/dev/null)
    if [ -n "$ABILIST" ]; then
      ARCH=$(echo "$ABILIST" | awk -F',' '{print $1}')
    else
      ARCH=$(uname -m)
    fi
  fi
fi

print_progress 30 "detect architecture"

case "$ARCH" in
    arm64-v8a|aarch64)
        PRIMARY_ARCH="arm64-v8a"
        KEEP_ARCHES="arm64-v8a"
        ;;
    x86_64|x86-64)
        PRIMARY_ARCH="x86_64"
        KEEP_ARCHES="x86_64"
        ;;
    *)
        ui_print "-- unsupported arch=$ARCH"
        exit 1
        ;;
esac

contains_abi() {
  target="$1"
  for abi in $KEEP_ARCHES; do
    if [ "$abi" = "$target" ]; then
      return 0
    fi
  done
  return 1
}

verify_abi() {
  abi="$1"
  so_file="$MODPATH/zygisk/$abi.so"
  sha256_file="$MODPATH/zygisk/$abi.so.sha256"

  if [ ! -f "$so_file" ]; then
    if [ "$abi" = "$PRIMARY_ARCH" ]; then
      ui_print "error: missing $abi lib path=$so_file"
      safe_cleanup_modpath
      exit 1
    fi
    return 0
  fi

  if [ -f "$sha256_file" ]; then
    ui_print "-- verify $abi lib"
    expected_sha256=$(cat "$sha256_file")
    actual_sha256=$(sha256sum "$so_file" 2>/dev/null | awk '{print $1}')
    if [ -z "$actual_sha256" ] && command -v toybox >/dev/null 2>&1; then
      actual_sha256=$(toybox sha256sum "$so_file" 2>/dev/null | awk '{print $1}')
    fi

    if [ -z "$actual_sha256" ]; then
      ui_print "error: cannot compute $abi sha256"
      safe_cleanup_modpath
      exit 1
    fi

    if [ "$expected_sha256" != "$actual_sha256" ]; then
      ui_print "error: $abi sha256 mismatch"
      ui_print "expect=$expected_sha256"
      ui_print "actual=$actual_sha256"
      safe_cleanup_modpath
      exit 1
    fi

    ui_print "-- ok: $abi verified"
    rm -f "$sha256_file"
  else
    ui_print "-- warn: missing $abi sha256, skip verify"
  fi
}

print_progress 40 "verify native libraries"
for abi in $KEEP_ARCHES; do
  verify_abi "$abi"
done

print_progress 50 "prune unused libraries"
for so_file in "$MODPATH"/zygisk/*.so; do
  if [ -f "$so_file" ]; then
    abi=$(basename "$so_file" .so)
    if ! contains_abi "$abi"; then
      rm -f "$so_file" "$so_file.sha256"
    fi
  fi
done

print_progress 60 "restore config"
restore_existing_config

rm -f "$VERSION_FILE"

# 设置权限
print_progress 70 "set permissions"
set_perm_recursive $MODPATH 0 0 0755 0644
if [ -d "$MODPATH/zygisk" ]; then
  set_perm_recursive $MODPATH/zygisk 0 0 0755 0644
fi

# Magisk/KernelSU 启动脚本需要可执行权限
if [ -f "$MODPATH/post-fs-data.sh" ]; then
  chmod 755 "$MODPATH/post-fs-data.sh"
fi
if [ -f "$MODPATH/service.sh" ]; then
  chmod 755 "$MODPATH/service.sh"
fi

# 创建配置目录结构
print_progress 80 "create config dir"
mkdir -p "$MODPATH/config/apps"
chmod 755 "$MODPATH/config"
chmod 755 "$MODPATH/config/apps"

# 如果全局配置不存在，创建默认全局配置
if [ ! -f "$MODPATH/config/global.json" ]; then
    ui_print "-- create default global config"
    cat > "$MODPATH/config/global.json" << 'EOF'
{
  "file_monitor_enabled": true
}
EOF
    chmod 644 "$MODPATH/config/global.json"
fi

# 创建日志目录和文件（供日志采集器落盘）
print_progress 90 "create logs dir"
mkdir -p "$MODPATH/logs"
chmod 777 "$MODPATH/logs"
touch "$MODPATH/logs/running.log"
touch "$MODPATH/logs/file_monitor.log"
touch "$MODPATH/logs/media_provider_state.log"
touch "$MODPATH/logs/app_status.log"
chmod 666 "$MODPATH/logs/running.log"
chmod 666 "$MODPATH/logs/file_monitor.log"
chmod 666 "$MODPATH/logs/media_provider_state.log"
chmod 666 "$MODPATH/logs/app_status.log"

print_progress 100 "install done"
ui_print "-- config=$MODPATH/config"
ui_print "-- logs=$MODPATH/logs"
ui_print "-- reboot required"
