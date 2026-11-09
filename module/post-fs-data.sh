#!/system/bin/sh
MODDIR=${0%/*}
mkdir -p /data/adb/frostcore
[ -f /data/adb/frostcore/packages ] || : > /data/adb/frostcore/packages
[ -f /data/adb/frostcore/whitelist ] || : > /data/adb/frostcore/whitelist
[ -f /data/adb/frostcore/mode ] || echo manual > /data/adb/frostcore/mode
cp -f "$MODDIR/frostcore.sh" /data/adb/frostcore/frostcore.sh
chmod 0755 /data/adb/frostcore/frostcore.sh
