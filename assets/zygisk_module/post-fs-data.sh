#!/system/bin/sh

MODDIR=${0%/*}

LOGS_DIR="$MODDIR/logs"
CONFIG_DIR="$MODDIR/config"
SYSTEM_WRITER_UIDS_FILE="$CONFIG_DIR/system_writer_uids.list"
STATS_FILE="$MODDIR/stats"
BOOT_PENDING_FILE="$MODDIR/.boot_pending"
BOOT_OK_FILE="$MODDIR/.boot_ok"
LOGS_CTX="u:object_r:shell_data_file:s0"

mkdir -p "$LOGS_DIR"
chmod 755 "$LOGS_DIR"
mkdir -p "$CONFIG_DIR"
touch "$STATS_FILE"
chmod 666 "$STATS_FILE"
if [ ! -s "$STATS_FILE" ]; then
  echo "0" > "$STATS_FILE"
fi

boot_id=""
if [ -r /proc/sys/kernel/random/boot_id ]; then
  boot_id=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
fi

last_pending=""
if [ -f "$BOOT_PENDING_FILE" ]; then
  last_pending=$(cat "$BOOT_PENDING_FILE" 2>/dev/null)
fi

last_ok=""
if [ -f "$BOOT_OK_FILE" ]; then
  last_ok=$(cat "$BOOT_OK_FILE" 2>/dev/null)
fi

if [ -n "$last_pending" ] && [ "$last_pending" != "$boot_id" ] && [ "$last_pending" != "$last_ok" ]; then
  touch "$MODDIR/disable"
  exit 0
fi

if [ -n "$boot_id" ]; then
  echo "$boot_id" > "$BOOT_PENDING_FILE"
else
  echo "unknown" > "$BOOT_PENDING_FILE"
fi
rm -f "$BOOT_OK_FILE"

# 清理历史启动 marker，避免 logs 目录长期累积。
if [ -d "$LOGS_DIR" ]; then
  for marker_path in "$LOGS_DIR"/boot_*.marker; do
    [ -e "$marker_path" ] || break
    marker_name="${marker_path##*/}"
    if [ -n "$boot_id" ] && [ "$marker_name" = "boot_${boot_id}.marker" ]; then
      continue
    fi
    rm -f "$marker_path"
  done
fi

if command -v chcon >/dev/null 2>&1; then
  chcon -R "$LOGS_CTX" "$LOGS_DIR" 2>/dev/null
  chcon "$LOGS_CTX" "$STATS_FILE" 2>/dev/null
fi

# 构建全量包名到 UID 映射，供系统代写按调用方识别配置使用。
tmp_uids_file="${SYSTEM_WRITER_UIDS_FILE}.tmp"

{
  echo "# package:uid"
  cmd package list packages -U 2>/dev/null |
    sed -n 's/^package:\([^ ]*\).* uid:\([0-9][0-9]*\).*/\1:\2/p' |
    sort -u
} > "$tmp_uids_file"

entry_count=$(grep -c '^[^#].*:[0-9][0-9]*$' "$tmp_uids_file" 2>/dev/null)
if [ "$entry_count" -gt 0 ]; then
  mv "$tmp_uids_file" "$SYSTEM_WRITER_UIDS_FILE"
  chmod 644 "$SYSTEM_WRITER_UIDS_FILE"
else
  rm -f "$tmp_uids_file"
fi

# 路径映射字段重命名迁移：current_path/target_path → request_path/final_path
MAPPING_MIGRATION_MARKER="$MODDIR/.mapping_fields_v1"
APPS_DIR="$CONFIG_DIR/apps"
if [ ! -f "$MAPPING_MIGRATION_MARKER" ] && [ -d "$APPS_DIR" ]; then
  for cfg in "$APPS_DIR"/*.json; do
    [ -f "$cfg" ] || continue
    if grep -q '"current_path"\|"target_path"' "$cfg" 2>/dev/null; then
      tmp_cfg="${cfg}.tmp"
      sed -e 's/"current_path"/"request_path"/g' \
          -e 's/"target_path"/"final_path"/g' \
          "$cfg" > "$tmp_cfg" && mv "$tmp_cfg" "$cfg"
    fi
  done
  touch "$MAPPING_MIGRATION_MARKER"
fi

# 将配置镜像同步到所有进程可访问的位置。
# 使用独立 tmpfs 承载真实文件镜像，而不是 bind mount 模块配置目录，
# 避免应用最终 mount namespace 里只看到空目录。
SHARED_CONFIG_DIR="/dev/srx_config"
SHARED_APPS_DIR="$SHARED_CONFIG_DIR/apps"
if grep -q " $SHARED_CONFIG_DIR " /proc/mounts 2>/dev/null; then
  umount "$SHARED_CONFIG_DIR" 2>/dev/null
fi
mkdir -p "$SHARED_CONFIG_DIR"
mount -t tmpfs -o mode=0755,nosuid,nodev,noexec srx_config "$SHARED_CONFIG_DIR" 2>/dev/null || \
  mount -o bind "$SHARED_CONFIG_DIR" "$SHARED_CONFIG_DIR" 2>/dev/null
mkdir -p "$SHARED_APPS_DIR"
chmod 755 "$SHARED_CONFIG_DIR" "$SHARED_APPS_DIR"
chcon -R u:object_r:shell_data_file:s0 "$SHARED_CONFIG_DIR" 2>/dev/null

if [ -f "$CONFIG_DIR/global.json" ]; then
  cp "$CONFIG_DIR/global.json" "$SHARED_CONFIG_DIR/global.json" 2>/dev/null
  chmod 644 "$SHARED_CONFIG_DIR/global.json" 2>/dev/null
  chcon u:object_r:shell_data_file:s0 "$SHARED_CONFIG_DIR/global.json" 2>/dev/null
fi
if [ -f "$SYSTEM_WRITER_UIDS_FILE" ]; then
  cp "$SYSTEM_WRITER_UIDS_FILE" "$SHARED_CONFIG_DIR/system_writer_uids.list" 2>/dev/null
  chmod 644 "$SHARED_CONFIG_DIR/system_writer_uids.list" 2>/dev/null
  chcon u:object_r:shell_data_file:s0 "$SHARED_CONFIG_DIR/system_writer_uids.list" 2>/dev/null
fi
if [ -d "$APPS_DIR" ]; then
  for cfg in "$APPS_DIR"/*.json; do
    [ -f "$cfg" ] || continue
    cp "$cfg" "$SHARED_APPS_DIR/$(basename "$cfg")" 2>/dev/null
  done
  chmod 644 "$SHARED_APPS_DIR"/*.json 2>/dev/null
  chcon -R u:object_r:shell_data_file:s0 "$SHARED_CONFIG_DIR" 2>/dev/null
fi
