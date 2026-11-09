#!/system/bin/sh
[ -x /data/adb/frostcore/frostcore.sh ] && /data/adb/frostcore/frostcore.sh thaw-all
rm -f /data/adb/frostcore/daemon.pid
