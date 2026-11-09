# FrostCore 2.2

Root-powered Android app freezer and background app manager for KernelSU, KernelSU Next, Magisk and APatch.

## What's new in 2.2

- App icons in the installed-app list.
- Searchable Suggested, Third-party and System app sections.
- Multi-select, section selection, and bulk add/remove.
- One-tap **Force stop selected** action. This uses Android's package-level `am force-stop`, not a raw PID kill, and stops all processes/background components belonging to each selected package.
- Import/export package lists.
- Dark mode and a mode selector that reads the active mode from root configuration when the screen opens.
- Root script command: `force-stop PACKAGE`; FrostCore refuses to force-stop its own UI package.

## Modes

- **Manual:** Explicit cgroup v2 Freeze / Thaw controls.
- **Screen-off:** Freezes configured app cgroups while the display is off, then thaws cgroups FrostCore owns when the display turns on.
- **Deep Freeze:** Watches the resumed foreground package. When a configured app leaves the foreground, FrostCore waits 8 seconds and then runs Android `am force-stop --user current PACKAGE`. When the user launches the app again, Android clears the stopped state as part of the normal launch. The foreground monitor is fail-closed: if it cannot identify the foreground package, it will not force-stop apps.

Deep Freeze and the manual bulk force-stop action use Android package force-stop. This is stronger than killing one PID: Android marks the package stopped and stops its processes/background components until a user launch. Notifications, sync, jobs, alarms, VPN, accessibility and messaging may be interrupted. Do not add critical apps or force-stop System UI, the launcher, phone, keyboard, security, VPN or accessibility services.

## Install

1. Install the v2.2 APK.
2. Flash the generated `FrostCore-v2.2-module.zip` in KernelSU / KernelSU Next / Magisk / APatch.
3. Reboot and grant root to FrostCore.
4. Start in Manual mode. Add only non-critical user apps to the managed list.
5. Test Deep Freeze with one expendable app before adding more.

## Emergency recovery

Open FrostCore and press **Emergency Thaw** to thaw only cgroups owned by FrostCore and clear Deep Freeze timers. Deep Freeze and bulk Force Stop use Android's stopped-package state; launch the app from its icon to clear that state. You can switch the controller back to Manual mode.

## Technical notes and limitations

- cgroup Freeze requires cgroup v2 and the expected Android app cgroup layout. The controller only writes below `/sys/fs/cgroup/apps/uid_*/pid_*`.
- Deep Freeze polls the resumed activity every 2 seconds and uses an 8-second default grace period. If foreground detection returns no package, it fails closed.
- Android versions and OEM ROMs vary in dumpsys output, process naming, cgroup layout, and root-manager behavior. Validate on the target device.
- This is an experimental release. GitHub Actions verifies compilation, shell syntax and module archive integrity; that is not a substitute for runtime testing on the target phone.

## Build

GitHub Actions builds the debug APK and packages the flashable root module on pushes to main and manual workflow dispatch.
