#!/system/bin/sh
MODDIR=${0%/*}
mkdir -p /data/adb/frostcore
cp -f "$MODDIR/frostcore.sh" /data/adb/frostcore/frostcore.sh
chmod 0755 /data/adb/frostcore/frostcore.sh
/data/adb/frostcore/frostcore.sh daemon >> /data/adb/frostcore/daemon.log 2>&1 &
echo $! > /data/adb/frostcore/daemon.pid
