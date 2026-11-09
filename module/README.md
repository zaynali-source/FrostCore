FrostCore 2.0 flashable root module for KernelSU, KernelSU Next, Magisk and APatch.

The module installs a root daemon and controller at /data/adb/frostcore.
Modes:
- manual: explicit cgroup v2 freeze/thaw
- auto: freeze configured app cgroups while screen is off, thaw FrostCore-owned cgroups when screen turns on
- deep: after a configured app leaves the foreground, wait 8 seconds then run Android am force-stop. Tapping its launcher icon starts it normally again.

Deep Freeze is force-stop based, not Package Manager suspension. It can interrupt notifications, sync, alarms, jobs, VPN, accessibility and messaging. Never add critical/system packages.

Commands:
- frostcore.sh status
- frostcore.sh add PACKAGE
- frostcore.sh remove PACKAGE
- frostcore.sh freeze PACKAGE
- frostcore.sh thaw-pkg PACKAGE
- frostcore.sh thaw-all

Test on a non-critical app first. Android/OEM foreground activity output and cgroup layouts vary. The daemon fails closed when foreground detection is unavailable.
