#!/system/bin/sh
# Storage Redirect X - Module Actions

MODDIR="/data/adb/modules/storage.redirect.x"
LOGDIR="$MODDIR/logs"

case "$1" in
  "Reload Redirect")
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "Reload redirect config"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""

    abi=$(getprop ro.product.cpu.abi 2>/dev/null)
    case "$abi" in
      arm64-v8a|aarch64) abi="arm64-v8a" ;;
      x86_64|x86-64) abi="x86_64" ;;
      *) abi="" ;;
    esac

    daemon_bin="$MODDIR/bin/$abi/srx_daemon"
    pid_file="$LOGDIR/.srx_daemon.pid"
    if [ -z "$abi" ] || [ ! -x "$daemon_bin" ]; then
      echo "error: missing daemon binary"
      echo "path=$daemon_bin"
    else
      old_pid=$(cat "$pid_file" 2>/dev/null)
      if [ -n "$old_pid" ]; then
        kill "$old_pid" 2>/dev/null
      fi
      "$daemon_bin" >/dev/null 2>&1 &
      new_pid="$!"
      echo "$new_pid" > "$pid_file"
      chmod 644 "$pid_file"
      echo "Hot reload daemon restarted"
      echo "pid=$new_pid"
    fi

    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "Reload queued"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    echo "Press any key to close..."
    read -n 1
    ;;

  "View Logs")
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "Storage Redirect X running log"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""

    if [ -f "$LOGDIR/running.log" ]; then
      tail -n 100 "$LOGDIR/running.log"
    else
      echo "error: missing log file path=$LOGDIR/running.log"
    fi

    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "Press any key to close..."
    read -n 1
    ;;

  "Clear Logs")
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "Clear logs"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""

    if [ -d "$LOGDIR" ]; then
      log_count=$(ls -1 "$LOGDIR"/*.log 2>/dev/null | wc -l)
      rm -f "$LOGDIR"/*.log 2>/dev/null

      if [ $? -eq 0 ]; then
        echo "Cleared log files: count=$log_count"
      else
        echo "error: clear logs failed"
      fi
    else
      echo "error: missing logs dir path=$LOGDIR"
    fi

    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "Press any key to close..."
    read -n 1
    ;;

  *)
    echo "error: unknown action=$1"
    exit 1
    ;;
esac

exit 0
