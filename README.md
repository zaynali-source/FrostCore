# FrostCore (early alpha)

Root app freezer for Android. Target-tested kernel capability: cgroup v2 `cgroup.freeze`
was manually verified on POCO F6 / peridot. This project is a source + flashable-module
starter; the APK is built by GitHub Actions. It is NOT a claim of production validation.

## Included
- Android app (Kotlin + Jetpack Compose): installed-app list, search, manual freeze toggle,
  whitelist, mode selector, status and emergency thaw.
- Root module payload: boot service, conservative shell controller, config and logs.
- GitHub Actions workflow to build a debug APK.

## Build APK
1. Create a GitHub repository and upload this project.
2. Open Actions -> `Build FrostCore APK` -> Run workflow.
3. Download `FrostCore-debug-apk` artifact.

## Install module
1. Install the APK.
2. Flash `module/FrostCore-module.zip` in KernelSU / KernelSU Next / Magisk / APatch.
3. Reboot. Open FrostCore and grant root when prompted.
4. Start in **Manual** mode. Add only ordinary user apps; do not freeze system apps,
   launchers, phone, System UI, keyboard, accessibility, VPN, alarms, or security apps.

## Safety / limitations
- cgroup v2 only in this alpha. It discovers each PID's own cgroup via `/proc/PID/cgroup`.
- It only attempts paths below `/sys/fs/cgroup/apps/uid_*/pid_*` and requires
  `cgroup.freeze`, `cgroup.events`, and a populated cgroup.
- It records only cgroups it changed and thaws only those it owns.
- Automatic mode is deliberately conservative: freezes configured packages only while
  screen is off; it does not freeze while screen is on. This avoids guessing foreground
  state and avoids aggressive app-launch interception.
- Multi-process apps are handled PID-by-PID. Android may move/recreate cgroups; the daemon
  re-discovers them on each pass.
- Native Android freezer coordination is best-effort, not guaranteed. If a cgroup is
  already frozen, FrostCore does not claim ownership or thaw it.
- This is an alpha project. Test one non-critical app at a time and keep Emergency Thaw
  available. Never freeze Android/system components.
- Root-manager module formats are broadly compatible, but each manager/version still needs
  device testing. SELinux policy and vendor cgroup layouts vary.
