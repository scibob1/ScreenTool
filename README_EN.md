# Screen Keep-On (屏幕常亮)

[中文](README.md) | package `com.wave.screentool` | MIT License

Long-running apps get interrupted when the system turns the screen off. This app keeps the screen on while showing a **pure black, very dim** picture, so it saves power and uses almost no resources.

- Pure Java, **zero third-party libraries**, UI built entirely in code, tiny APK.
- **No network**, no analytics, no ads, **no storage permission**.
- Everything loads on demand; nothing runs when there is nothing to show.
- Works on Android and on HarmonyOS versions that can run Android apps.

## Three ways to keep awake
| Mode | What it does |
|---|---|
| **Floating ball** | A small ball docked at the screen edge with an optional stopwatch. Tap it to open the curtain. |
| **Curtain** | A black cover (full screen or a custom area) with optional stopwatch and clock. |
| **Notification only** | No windows. A foreground notification and a wake lock keep the screen on. |

Ball and curtain can be enabled separately; if both are off you get notification-only mode.

**Linking:** tap the ball to open the curtain; tap the curtain or press Back to return to the ball. After the *operation window* (default 600 s) the ball turns into the curtain by itself; 0 means anti-mistouch (the curtain ignores taps, only Back returns). The stopwatch resets when the curtain returns to the ball (default on). After 5 seconds without a touch the ball slides three quarters into the screen edge and fades (default on).

## Home page
Black full-screen page (shows the time by default), supports system floating windows and split screen.
Swipe up/down: brightness · swipe left: settings · swipe right: turn on keep-awake · double-tap: exit · Back: hide the app (recent tasks keep a black snapshot). In settings, swipe right to go back.

## Highlights
- **Brightness:** window brightness plus system brightness (needs the *Modify system settings* permission); the original brightness is restored when you stop.
- **Keep-awake methods:** window flag, wake lock, and an invisible 1-pixel window as a fallback for systems that ignore the wake lock.
- **Eye rest:** a curtain pops up on a schedule (default every 20 min for 60 s), blocks touches and keys, has its own style, shows a toast 5 s before. Emergency exit: the notification's *Skip rest* button, or (if notification buttons are off) pressing the curtain for 5 s.
- **Styles:** three built-in styles and custom ones, 8 colors each with opacity, color wheel + hex input.
- **Background pictures / slideshow:** pick one or many pictures for the home, curtain or eye-rest background; only links are stored (no storage permission); preview, gallery management, interval/order/fit/fade settings.
- **Backup / restore:** `.json` (settings only) or `.zip` (with pictures; same-named pictures are overwritten on restore, missing ones skipped). File names contain the time.
- **Privacy protection (this app only, everything off by default):** a full-screen privacy cover for the curtain plus verification in front of this app's Settings, backup and protection settings. You combine five dimensions: *verification* (custom password / system lock screen / both), *triggers* (manual, timer, screen off, leaving the app), *curtain behavior* (taps cannot close it, Back needs verification, anti-screenshot, enhanced cover), *what verification authorizes* (remove the cover, open Settings, backup, change protection settings) and *failure response* (stay locked, escalating cooldown, clear authorized files/folders, clear app data). Combinations are constrained, not freely stackable: an unavailable verification method never silently downgrades (the cover stays locked and says why), data clean-up needs working file authorization and a limit of at least 3, and app data is only cleared after the authorized files were re-read and confirmed deleted. Every entry point (curtain tap, Back, notification, tile, shortcuts, Settings, backup, restore defaults, home exit) goes through one protection controller; ending an eye rest, low battery and the auto stop timer never count as verification; the cover and verification do not depend on notifications; press-and-hold on the locked curtain is an explicit emergency release that removes the cover but does not open protected data. It protects only this app: the system can always stop or uninstall it. See `docs/PRIVACY_DESIGN.md` (Chinese).
- **Stability:** guide for permissions, battery optimization, auto-start, task locking; automatic recovery when the system removes a floating window; optional boot start; optional **enhanced mode** (an accessibility service used only to get a higher window layer, it never reads your screen).
- **Shortcuts:** long-press the icon (ball, curtain, notification only, stop); quick-settings tile where supported (not on HarmonyOS).
- **Alerts:** optional periodic vibration after the screen turns off; auto stop on low battery (default 15 %); auto stop timer.

## Known limits
- Some system apps (for example Notes) use a higher window layer and may cover or intercept the floating windows.
- On HarmonyOS floating windows may only appear above other apps (not on the desktop) and may disappear when the hosting app is cleared; the app tries to restore them.
- Normal floating windows cannot cover the status bar; the enhanced mode can.
- The system shows its own "displaying over other apps" notice for normal floating windows; the app cannot remove it.

## Build
Push to GitHub and **Actions → Build APK** produces the APK (Artifacts). Tag `v1.3.0` to publish a Release (`bash tools/release.sh v1.3.0`). Local: Android Studio or `gradle assembleRelease` (JDK 17, SDK 35).
The repository includes a **public signing key** so that every build can be installed over the previous one; use it for personal distribution only.

## License
[MIT](LICENSE)
