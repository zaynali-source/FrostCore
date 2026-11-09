Flashable root module. Standard module layout is recognized by KernelSU, KernelSU Next,
Magisk and APatch. Install only after reading the top-level README. This alpha does not
provide a privileged Binder/API integration with ActivityManager; native freezer
coordination is best-effort. Manual freeze command example:
`su -c '/data/adb/frostcore/frostcore.sh freeze com.example.app'`
Thaw owned cgroups:
`su -c '/data/adb/frostcore/frostcore.sh thaw-all'`
