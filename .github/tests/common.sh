#!/usr/bin/env bash
# 测试共享库：root 调用、配置写入、结果读取、存储等待
# 约束：仅依赖 adb 与 Magisk/KSU su；被各 scenario 脚本 source

APP_ID="${APP_ID:-me.fakerqu.test.storageredirect}"
MODULE_ID="${MODULE_ID:-storage.redirect.x}"
MODULE_DIR="/data/adb/modules/${MODULE_ID}"
CONFIG="${MODULE_DIR}/config/apps/${APP_ID}.json"
LOG_PATH="${MODULE_DIR}/logs/running.log"
ACTION="me.fakerqu.test.storageredirection.TEST_CASE"
RESULT_DIR="/sdcard/Android/data/${APP_ID}/files/test_case_result"
INTERNAL_RESULT_DIR="/data/data/${APP_ID}/files/test_case_result"
REAL_ROOT="/storage/emulated/0"
PRIVATE_ROOT="${REAL_ROOT}/Android/data/${APP_ID}/sdcard"
TEST_FILE="${TEST_FILE:-srt_ci_probe.txt}"
PAYLOAD="${PAYLOAD:-storage-redirect-test:file:ci}"

# 以 root 执行命令，兼容 Magisk/KSU 的 su 语法差异
adb_root() {
  local command="PATH=/debug_ramdisk:/sbin:/data/adb/magisk:\$PATH; $1"
  local quoted
  quoted="$(printf '%s' "$command" | sed "s/'/'\\\\''/g")"
  adb shell "su 0 sh -c '$quoted'" || adb shell "su -c '$quoted'"
}

# 同 adb_root，但清理 CRLF，用于取值比较
adb_su() {
  local command="PATH=/debug_ramdisk:/sbin:/data/adb/magisk:\$PATH; $1"
  (adb_root "$1" || adb shell magisk su -c "$command" || adb shell /system/bin/magisk su -c "$command" || adb shell /debug_ramdisk/magisk su -c "$command") | tr -d '\r'
}

wait_boot_completed() {
  adb wait-for-device
  adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'
}

# 写入应用配置 JSON
write_config() {
  local content="$1"
  adb_su "mkdir -p ${MODULE_DIR}/config/apps" >/dev/null
  printf '%s' "$content" | adb_root "cat > '$CONFIG'" >/dev/null
}

# 等待共享存储挂载就绪
wait_storage_ready() {
  local label="$1"
  local timeout_seconds="${2:-90}"
  local deadline=$((SECONDS + timeout_seconds))

  while [ "$SECONDS" -lt "$deadline" ]; do
    if adb shell "sm list-volumes all 2>/dev/null | grep -q 'emulated;0 mounted' && test -d '$REAL_ROOT'" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done

  echo "Timed out waiting for emulated storage: ${label}"
  print_storage_state "${label}-storage-timeout"
  return 1
}

print_storage_state() {
  local label="$1"
  echo "=== storage state: ${label} ==="
  adb shell "date; getprop ro.build.version.sdk; getprop sys.boot_completed; sm list-volumes all 2>/dev/null || true; ls -ld /storage/emulated/0 /sdcard 2>&1 || true; mount | grep -E ' /storage|/mnt/runtime|/mnt/user|sdcard|fuse|srx' || true" || true
  adb_su "id; cat /proc/mounts | grep -E ' /storage|/mnt/runtime|/mnt/user|sdcard|fuse|srx' || true" || true
}

clean_results() {
  adb_su "rm -rf '$RESULT_DIR' '$INTERNAL_RESULT_DIR'" >/dev/null
}

latest_result() {
  adb_su "ls -t '$RESULT_DIR'/result_*.txt '$INTERNAL_RESULT_DIR'/result_*.txt 2>/dev/null | head -1" | tail -1
}

# 清理并重建测试目标目录
clean_targets() {
  adb_su "for dir in '${REAL_ROOT}/Download/SrtProbe' '${REAL_ROOT}/Download/Test' '${PRIVATE_ROOT}/Download/SrtProbe' '${PRIVATE_ROOT}/Download/Test' '${PRIVATE_ROOT}/Download'; do find \"\$dir\" -maxdepth 1 -name '$TEST_FILE' -delete 2>/dev/null || true; done" >/dev/null
  clean_results
  adb_su "mkdir -p '${REAL_ROOT}/Download/SrtProbe' '${REAL_ROOT}/Download/Test' '${PRIVATE_ROOT}/Download/SrtProbe' '${PRIVATE_ROOT}/Download/Test' '${PRIVATE_ROOT}/Download'; chmod 777 '${REAL_ROOT}/Download/SrtProbe' '${REAL_ROOT}/Download/Test' '${PRIVATE_ROOT}/Download/SrtProbe' '${PRIVATE_ROOT}/Download/Test' '${PRIVATE_ROOT}/Download' 2>/dev/null || true" >/dev/null
}

# 通过前台服务触发一个测试用例，等待结果文件并按 pass_pattern 校验
run_service_case() {
  local scenario="$1"
  local label="$2"
  local test_case="$3"
  local pass_pattern="$4"
  shift 4
  local output_file="scenario-${scenario}-${label}-result.txt"

  clean_results
  adb shell am start-foreground-service -n "${APP_ID}/.TestService" -a "$ACTION" --es test_case "$test_case" "$@" >/dev/null

  local deadline=$((SECONDS + 45)) result_file=""
  while [ "$SECONDS" -lt "$deadline" ]; do
    result_file="$(latest_result)"
    if [ -n "$result_file" ]; then
      adb_su "cat '$result_file'" | tee "$output_file"
      cat "$output_file" >>"scenario-${scenario}-result.txt"
      if [ -z "$pass_pattern" ]; then
        return 0
      fi
      if grep -q "$pass_pattern" "$output_file"; then
        return 0
      fi
      return 1
    fi
    sleep 1
  done

  echo "result_timeout scenario=${scenario} test_case=${test_case}"
  return 1
}

# 从应用进程写入探针文件，失败重试一次
run_write_test() {
  local scenario="$1"
  local target_path="${REAL_ROOT}/Download/SrtProbe/${TEST_FILE}"
  for attempt in 1 2; do
    if run_service_case "$scenario" "write" "file_write" '^PASS \[file_write\]' --es file_path "$target_path" --es payload "$PAYLOAD" --es expected_payload "$PAYLOAD"; then
      return 0
    fi
    if [ "$attempt" -eq 2 ]; then
      return 1
    fi
    echo "write_retry scenario=${scenario} attempt=${attempt}"
    adb shell am force-stop "$APP_ID" >/dev/null || true
    adb shell am start -W -n "${APP_ID}/.MainActivity" >/dev/null
    wait_storage_ready "scenario-${scenario}-write-retry"
    clean_targets
  done
}

# 校验应用视角能列出探针文件
expect_app_entry() {
  local scenario="$1"
  local label="$2"
  local dir="$3"

  for attempt in 1 2 3 4 5; do
    if run_service_case "$scenario" "$label" "file_list_dir" '^PASS \[file_list_dir\]' --es file_dir "$dir" &&
      grep -q "entries=.*${TEST_FILE}" "scenario-${scenario}-${label}-result.txt"; then
      echo "app_view scenario=${scenario} logical_dir=${dir} expected_entry=${TEST_FILE}"
      return 0
    fi
    echo "app_view_retry scenario=${scenario} logical_dir=${dir} attempt=${attempt}"
    sleep 1
  done

  return 1
}

# 校验应用视角看不到探针文件
expect_no_app_entry() {
  local scenario="$1"
  local label="$2"
  local dir="$3"

  for attempt in 1 2 3; do
    run_service_case "$scenario" "$label" "file_list_dir" "" --es file_dir "$dir"
    if grep -q "entries=.*${TEST_FILE}" "scenario-${scenario}-${label}-result.txt"; then
      echo "app_view scenario=${scenario} logical_dir=${dir} forbidden_entry_visible=${TEST_FILE}"
      return 1
    fi
    sleep 1
  done

  echo "app_view scenario=${scenario} logical_dir=${dir} forbidden_entry=${TEST_FILE}"
}

# root 视角查找探针文件的物理落点
find_written_file() {
  adb_su "for dir in '${REAL_ROOT}/Download/SrtProbe' '${REAL_ROOT}/Download/Test' '${PRIVATE_ROOT}/Download/SrtProbe' '${PRIVATE_ROOT}/Download/Test' '${PRIVATE_ROOT}/Download'; do find \"\$dir\" -maxdepth 1 -name '$TEST_FILE' -print 2>/dev/null || true; done | sort" | tail -1
}

# 校验物理落点前缀符合预期
check_file_location() {
  local scenario="$1" expected="$2" actual
  actual="$(find_written_file)"
  echo "scenario=${scenario} expected_prefix=${expected} actual=${actual}"
  if [ -z "$actual" ] || [ "${actual#"$expected"}" = "$actual" ]; then
    return 1
  fi
}

# 校验 media provider 进程未因崩溃循环而暴涨（关联 issue #40/#43）
check_health() {
  adb shell "count=\$(ps -A | grep -c 'com.google.android.providers.media.module' || true); echo media_count=\$count; ps -A | grep 'com.google.android.providers.media.module' || true" | tee media-health.txt
  local count
  count="$(sed -n 's/^media_count=//p' media-health.txt | tail -1)"
  [ -z "$count" ] || [ "$count" -le 10 ]
}

# 场景失败时输出诊断
print_diagnostics() {
  local scenario="$1"
  echo "=== scenario ${scenario} diagnostics ==="
  print_storage_state "scenario-${scenario}-failure"
  adb_su "echo ===config===; cat '$CONFIG' 2>/dev/null || true; echo; echo ===module_state===; ls -la ${MODULE_DIR} 2>/dev/null || true; echo; mount | grep -E 'srx|storage.redirect' || true; echo; echo ===logs===; for log in running.log monitor-service.log; do echo ---\$log---; tail -80 ${MODULE_DIR}/logs/\$log 2>/dev/null || true; done" || true
  adb logcat -d -t 1200 | grep -Ei 'StorageRedirectTest|srx|StorageRedirect|Magisk|zygisk|FATAL EXCEPTION|AndroidRuntime|MediaProvider|vold|sdcard|fuse' | tail -260 || true
}

# 授予测试应用存储权限
grant_test_permissions() {
  adb shell pm grant "$APP_ID" android.permission.READ_EXTERNAL_STORAGE >/dev/null 2>&1 || true
  adb shell pm grant "$APP_ID" android.permission.WRITE_EXTERNAL_STORAGE >/dev/null 2>&1 || true
  adb shell pm grant "$APP_ID" android.permission.READ_MEDIA_IMAGES >/dev/null 2>&1 || true
  adb shell pm grant "$APP_ID" android.permission.READ_MEDIA_VIDEO >/dev/null 2>&1 || true
  adb shell pm grant "$APP_ID" android.permission.READ_MEDIA_AUDIO >/dev/null 2>&1 || true
  adb shell appops set "$APP_ID" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1 || true
}

# 场景通用前置：应用配置、重启应用、等存储、清目标
prepare_scenario() {
  local scenario="$1"
  : >"scenario-${scenario}-result.txt"
  echo "step: 重启测试应用"
  adb shell am force-stop "$APP_ID" >/dev/null || true
  adb shell am start -W -n "${APP_ID}/.MainActivity" >/dev/null
  sleep 1
  echo "step: 等待共享存储可用"
  wait_storage_ready "scenario-${scenario}"
  echo "step: 清理测试目标"
  clean_targets
}
