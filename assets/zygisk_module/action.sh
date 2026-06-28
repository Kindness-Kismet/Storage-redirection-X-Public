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

    CONFIG_DIR="$MODDIR/config/apps"
    if [ -d "$CONFIG_DIR" ]; then
      killed_count=0
      app_list=""

      for config_file in "$CONFIG_DIR"/*.json; do
        if [ -f "$config_file" ]; then
          package=$(basename "$config_file" .json)
          pid=$(pidof "$package" 2>/dev/null)
          if [ -n "$pid" ]; then
            kill -9 $pid 2>/dev/null
            killed_count=$((killed_count + 1))
            app_list="$app_list  ok $package\n"
          fi
        fi
      done

      if [ $killed_count -eq 0 ]; then
        echo "No redirected app running"
      else
        echo "Restarted apps: count=$killed_count"
        echo ""
        printf "$app_list"
      fi
    else
      echo "error: missing config dir path=$CONFIG_DIR"
    fi

    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "Reload done"
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
