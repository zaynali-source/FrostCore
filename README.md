# FrostCore 2.0

Root-powered Android app freezer and background app manager for KernelSU, KernelSU Next, Magisk and APatch.

## Modes

- **Manual:** Explicit cgroup v2 Freeze / Thaw controls.
- **Screen-off:** Freezes configured app cgroups while the display is off, then thaws cgroups FrostCore owns when the display turns on.
- **Deep Freeze:** Watches the resumed foreground package. When a configured app leaves the foreground, FrostCore waits 8 seconds and then runs Android `am force-stop --user current PACKAGE`. When the user launches the app again, Android clears the stopped state as part of the normal launch. The foreground monitor is fail-closed: if it cannot identify the foreground package, it will not force-stop apps.

Deep Freeze is force-stop based, not Package Manager suspension. It terminates the app process and prevents its background components from running until the next user launch. Notifications, sync, jobs, alarms, VPN, accessibility and messaging may be interrupted. Do not add critical apps.

## Install

1. Install the v2.0 APK.
2. Flash `module/FrostCore-module.zip` in KernelSU / KernelSU Next / Magisk / APatch.
3. Reboot and grant root to FrostCore.
4. Start in Manual mode. Add only non-critical user apps to the managed list.
5. Test Deep Freeze with one expendable app before adding more.

## Emergency recovery

Open FrostCore and press **Emergency Thaw** to thaw only cgroups owned by FrostCore and clear Deep Freeze timers. Deep Freeze uses Android force-stop; launching an app from its icon clears its stopped state. You can switch the controller back to Manual mode.

## Technical notes and limitations

- cgroup Freeze requires cgroup v2 and the expected Android app cgroup layout. The controller only writes below `/sys/fs/cgroup/apps/uid_*/pid_*`.
- Deep Freeze polls the resumed activity every 2 seconds and uses an 8-second default grace period. If foreground detection returns no package, it fails closed.
- The 8-second grace period is fixed in this initial 2.0 build; it is not a per-app timer.
- Android versions and OEM ROMs vary in dumpsys output, process naming, cgroup layout, and root-manager behavior. Validate on the target device.
- Do not freeze or force-stop System UI, launcher, phone, keyboard, accessibility, VPN, security, alarm, or other critical packages.
- This is an experimental release. No claim of universal device compatibility or production validation is made.

## Build

GitHub Actions builds the debug APK and packages the flashable root module on pushes to main and manual workflow dispatch.
