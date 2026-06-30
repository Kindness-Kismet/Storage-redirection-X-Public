is_skipped_package() {
  package_name="$1"
  # 排除媒体与模块自身，避免和系统代写热更新策略冲突。
  case "$package_name" in
    ""|\
    com.storage.redirect.x|\
    com.android.providers.media|\
    com.android.providers.media.module|\
    com.google.android.providers.media.module)
      return 0
      ;;
  esac
  return 1
}

is_effective_package_config() {
  package_name="$1"
  config_file="$APPS_CONFIG_DIR/$package_name.json"
  if [ ! -f "$config_file" ]; then
    return 1
  fi
  if ! grep -q '"users"[[:space:]]*:' "$config_file" 2>/dev/null; then
    return 1
  fi
  # 仅将存在 enabled=true 的配置视为生效配置。
  if grep -Eq '"enabled"[[:space:]]*:[[:space:]]*true' "$config_file" 2>/dev/null; then
    return 0
  fi
  return 1
}

is_package_query_ready() {
  cmd package list packages 2>/dev/null | grep -q '^package:'
}

get_package_install_state() {
  package_name="$1"
  [ -n "$package_name" ] || {
    echo "unknown"
    return 0
  }
  cmd package path "$package_name" >/dev/null 2>&1 && {
    echo "installed"
    return 0
  }

  user_ids=$(cmd user list 2>/dev/null | sed -n 's/.*{\([0-9][0-9]*\):.*/\1/p' | sort -u)
  if [ -z "$user_ids" ]; then
    echo "unknown"
    return 0
  fi

  for user_id in $user_ids; do
    package_lines=$(cmd package list packages --user "$user_id" "$package_name" 2>/dev/null)
    query_status=$?
    if [ "$query_status" -ne 0 ]; then
      echo "unknown"
      return 0
    fi
    echo "$package_lines" | grep -qx "package:$package_name" && {
      echo "installed"
      return 0
    }
  done

  echo "missing"
}

disable_package_config() {
  package_name="$1"
  config_file="$APPS_CONFIG_DIR/$package_name.json"
  tmp_file="$config_file.tmp"
  [ -f "$config_file" ] || return 1
  grep -Eq '"enabled"[[:space:]]*:[[:space:]]*true' "$config_file" 2>/dev/null || return 1

  sed 's/"enabled"[[:space:]]*:[[:space:]]*true/"enabled": false/g' "$config_file" > "$tmp_file" 2>/dev/null || {
    rm -f "$tmp_file"
    return 1
  }
  mv "$tmp_file" "$config_file" || {
    rm -f "$tmp_file"
    return 1
  }
  chmod 644 "$config_file" 2>/dev/null
  return 0
}

sync_uninstalled_app_configs() {
  [ -d "$APPS_CONFIG_DIR" ] || return 0
  if ! is_package_query_ready; then
    log -p w -t Boot "skip uninstalled config sync: package query unavailable"
    return 0
  fi

  changed_file="$LOGS_DIR/.uninstalled_config_packages.tmp"
  : > "$changed_file"
  refresh_uid_map

  for config_file in "$APPS_CONFIG_DIR"/*.json; do
    [ -f "$config_file" ] || continue
    package_name=$(basename "$config_file" .json)
    [ -n "$package_name" ] || continue
    is_skipped_package "$package_name" && continue
    is_effective_package_config "$package_name" || continue

    install_state=$(get_package_install_state "$package_name")
    if [ "$install_state" = "installed" ] || [ "$install_state" = "unknown" ]; then
      [ "$install_state" = "unknown" ] && log -p w -t Boot "skip uninstalled config sync for unknown package state: pkg=$package_name"
      continue
    fi

    if disable_package_config "$package_name"; then
      echo "$package_name" >> "$changed_file"
      log -p i -t Boot "disable config for uninstalled package: pkg=$package_name"
    fi
  done

  if [ -s "$changed_file" ]; then
    while IFS= read -r package_name; do
      [ -n "$package_name" ] || continue
      log -p i -t Boot "disabled config for uninstalled package, daemon will reconcile: pkg=$package_name"
    done < "$changed_file"
  fi
  rm -f "$changed_file"
}

build_config_state_file() {
  output_file="$1"
  : > "$output_file"
  if [ ! -d "$APPS_CONFIG_DIR" ]; then
    return 0
  fi

  for config_file in "$APPS_CONFIG_DIR"/*.json; do
    [ -f "$config_file" ] || continue
    package_name=$(basename "$config_file" .json)
    [ -n "$package_name" ] || continue
    mtime=$(stat -c '%Y' "$config_file" 2>/dev/null)
    size=$(stat -c '%s' "$config_file" 2>/dev/null)
    mtime=${mtime:-0}
    size=${size:-0}
    if is_effective_package_config "$package_name"; then
      effective=1
    else
      effective=0
    fi
    # 状态行格式：包名|mtime|size|是否生效(0/1)
    echo "$package_name|$mtime|$size|$effective" >> "$output_file"
  done

  sort -o "$output_file" "$output_file" 2>/dev/null
}

get_state_line() {
  state_file="$1"
  package_name="$2"
  awk -F'|' -v target="$package_name" '$1 == target { print; exit }' "$state_file" 2>/dev/null
}

get_state_effective() {
  state_line="$1"
  if [ -z "$state_line" ]; then
    echo 0
    return 0
  fi
  echo "$state_line" | awk -F'|' '{print $4 + 0}'
}

get_state_signature() {
  state_line="$1"
  if [ -z "$state_line" ]; then
    echo ""
    return 0
  fi
  echo "$state_line" | awk -F'|' '{print $2 "|" $3}'
}

should_hot_reload_by_state() {
  old_line="$1"
  new_line="$2"
  old_effective=$(get_state_effective "$old_line")
  new_effective=$(get_state_effective "$new_line")

  # 生效配置仍为生效配置时，仅在内容变化后触发热更新。
  if [ "$old_effective" -eq 1 ] && [ "$new_effective" -eq 1 ]; then
    old_sig=$(get_state_signature "$old_line")
    new_sig=$(get_state_signature "$new_line")
    [ "$old_sig" != "$new_sig" ]
    return $?
  fi

  # 生效状态发生切换（启用/禁用/删除）时触发热更新。
  if [ "$old_effective" -eq 1 ] || [ "$new_effective" -eq 1 ]; then
    return 0
  fi
  # 非生效配置变更不触发热更新，避免无意义 reconcile。
  return 1
}

get_uid_by_package() {
  package_name="$1"
  awk -F':' -v target="$package_name" '$1 == target { print $2; exit }' "$SYSTEM_WRITER_UIDS_FILE" 2>/dev/null
}

get_packages_by_uid() {
  target_uid="$1"
  awk -F':' -v uid="$target_uid" '$2 == uid { print $1 }' "$SYSTEM_WRITER_UIDS_FILE" 2>/dev/null
}

handle_config_changes() {
  changed_packages="$1"
  old_state_file="$2"
  new_state_file="$3"
  hot_reload_file="$LOGS_DIR/.config_hot_reload_packages.tmp"
  : > "$hot_reload_file"

  # 变更处理前刷新一次 UID 映射，避免共享 UID 关系过期。
  refresh_uid_map

  for package_name in $changed_packages; do
    is_skipped_package "$package_name" && continue

    old_line=$(get_state_line "$old_state_file" "$package_name")
    new_line=$(get_state_line "$new_state_file" "$package_name")
    should_kill_source=0
    if should_hot_reload_by_state "$old_line" "$new_line"; then
      should_kill_source=1
      echo "$package_name" >> "$hot_reload_file"
    fi

    uid=$(get_uid_by_package "$package_name")
    if [ -z "$uid" ]; then
      continue
    fi

    # 共享 UID 只扩展到有配置记录的包，避免误伤同 UID 的无关系统组件。
    for shared_package in $(get_packages_by_uid "$uid"); do
      [ "$shared_package" = "$package_name" ] && continue
      is_skipped_package "$shared_package" && continue

      shared_old_line=$(get_state_line "$old_state_file" "$shared_package")
      shared_new_line=$(get_state_line "$new_state_file" "$shared_package")
      if [ -z "$shared_old_line" ] && [ -z "$shared_new_line" ]; then
        continue
      fi

      # 同 UID 配置保持生效时，来源包生效变更也会触发热更新，避免进程状态不一致。
      if should_hot_reload_by_state "$shared_old_line" "$shared_new_line"; then
        echo "$shared_package" >> "$hot_reload_file"
        continue
      fi

      if [ "$should_kill_source" -eq 1 ]; then
        shared_old_effective=$(get_state_effective "$shared_old_line")
        shared_new_effective=$(get_state_effective "$shared_new_line")
        if [ "$shared_old_effective" -eq 1 ] || [ "$shared_new_effective" -eq 1 ]; then
          echo "$shared_package" >> "$hot_reload_file"
        fi
      fi
    done
  done

  if [ ! -s "$hot_reload_file" ]; then
    rm -f "$hot_reload_file"
    return 0
  fi

  sort -u -o "$hot_reload_file" "$hot_reload_file" 2>/dev/null
  while IFS= read -r package_name; do
    [ -n "$package_name" ] || continue
    log -p i -t Boot "config hot reload queued: pkg=$package_name"
  done < "$hot_reload_file"
  ensure_srx_daemon_running
  rm -f "$hot_reload_file"
}

start_package_event_collector() {
  (
    mkdir -p "$APPS_CONFIG_DIR"
    sync_uninstalled_app_configs

    while true; do
      watch_args=""
      [ -f /data/system/packages.xml ] && watch_args="$watch_args /data/system/packages.xml:wcmdn"
      for user_dir in /data/system/users/*; do
        [ -d "$user_dir" ] || continue
        [ -f "$user_dir/package-restrictions.xml" ] && \
          watch_args="$watch_args $user_dir/package-restrictions.xml:wcmdn"
      done

      if [ -z "$watch_args" ]; then
        sleep 30
        sync_uninstalled_app_configs
        continue
      fi

      # shellcheck disable=SC2086
      inotifyd - $watch_args 2>/dev/null |
      while IFS= read -r _; do
        while read -t 2 -r _; do :; done
        sync_uninstalled_app_configs
      done
      sleep 5
    done
  ) &
  echo "$!" > "$PACKAGE_EVENT_COLLECTOR_PID_FILE"
  chmod 644 "$PACKAGE_EVENT_COLLECTOR_PID_FILE"
}

start_config_event_collector() {
  (
    tmp_old_state="$LOGS_DIR/.config_apps_state.old"
    tmp_new_state="$LOGS_DIR/.config_apps_state.new"
    # 启动时建立状态基线，避免把旧配置当作新增事件处理。
    build_config_state_file "$CONFIG_STATE_FILE"
    mkdir -p "$APPS_CONFIG_DIR"

    # inotifyd 监听配置目录，事件通过管道传给处理循环。
    # w=写入关闭 c=内容修改 m=新建 d=删除 n=移入
    while true; do
      inotifyd - "$APPS_CONFIG_DIR":wcmdn 2>/dev/null |
      while IFS= read -r event_line; do
        # 仅处理 .json 文件事件
        case "$event_line" in
          *".json") ;;
          *) continue ;;
        esac

        # 去抖：读取 1 秒内的后续事件一起处理
        while read -t 1 -r _; do :; done

        if [ -f "$CONFIG_STATE_FILE" ]; then
          cp "$CONFIG_STATE_FILE" "$tmp_old_state"
        else
          : > "$tmp_old_state"
        fi

        build_config_state_file "$tmp_new_state"
        changed_packages=$(awk -F'|' '
          NR == FNR { old[$1] = $0; next }
          {
            new[$1] = $0;
            if (!( $1 in old ) || old[$1] != $0) {
              print $1;
            }
          }
          END {
            for (pkg in old) {
              if (!(pkg in new)) {
                print pkg;
              }
            }
          }
        ' "$tmp_old_state" "$tmp_new_state" | sort -u)
        if [ -n "$changed_packages" ]; then
          handle_config_changes "$changed_packages" "$tmp_old_state" "$tmp_new_state"
        fi
        mv "$tmp_new_state" "$CONFIG_STATE_FILE"
      done
      # inotifyd 异常退出时等待后重启
      sleep 5
    done
  ) &
  echo "$!" > "$CONFIG_EVENT_COLLECTOR_PID_FILE"
  chmod 644 "$CONFIG_EVENT_COLLECTOR_PID_FILE"
}
