# Changelog

## 1.0.0

- Mirror and control Android emulators, Android phones, iOS simulators and iPhones in the Mobile Devices tool window. An iPhone needs a one-time agent install with a development provisioning profile.
- Android phones stream through mobilecli (adb screenshots as the fallback), and a foldable switches to the active display when it is folded or unfolded.
- Inspector with element tree, highlight, duplicate detection and locators for Mobilewright, Appium and WebdriverIO, plus scoped locators and an accessibility audit.
- Recorder and player for interaction scripts, with assertions, replay controls and Appium or WebdriverIO output.
- Device Tools tabs: Log, Apps, Open URL, Crashes, Location, Display, Input, Network, Battery, Biometrics, WebView, Files, Performance, Visual and Notifications.
- Show Locator on Device editor action (Ctrl+Alt+Shift+M).
- Device power actions, automatic reconnect, and the last device is remembered per project.
- Clear messages when adb or mobilecli is missing. mobilecli is required for the element inspector on Android 17.

## Changes during development of 1.0.0

- Android screenshots on devices with several displays (foldables): skip the adb "Multiple displays" warning before the PNG, so the mirror shows frames.
- Android phones (and emulators without gRPC) now mirror through the mobilecli MJPEG stream when mobilecli is installed, falling back to adb screenshots.
- Inspector on Android without mobilecli: the "uiautomator dump failed" error now shows the exit code and says to install mobilecli (Android 17 kills uiautomator).
- Foldable phones: the mobilecli stream restarts when the active display changes (folding or unfolding).
- README: Requirements section that says mobilecli is needed and what stops working without it.
- Missing adb: the device list now says that adb was not found (and which path) instead of staying empty, and the mirror placeholder wraps long messages.
- Mobilewright role map synced with @mobilewright/core 0.0.57: added the `radio` role, iOS `Other` as `listitem`, and the Material and AppCompat Android types.

- Screenshot: optional (remembered) hierarchy XML saved next to the PNG with the same base name.
- Status line tooltip names the transport and why a fallback happened (gRPC unavailable, MJPEG ended, WDA missing).
- Locators tab: "Whole line" checkbox next to Copy as (driver.findElement(...), driver.find_element(...), await $(...)).
- Display tab: Android Pointer location and Don't keep activities toggles (part of Reset to defaults).
- Screen recording: time limit setting (0 to 3600 s, 0 = none) that stops and saves the video automatically.
- Recorder: choose the direction and swipe limit of a scroll until visible step (right-click menu).
- Setting for the long press time (300 to 3000 ms, default 700).
- Element filter: OR groups (OR or |), negated words (-word, !word), /regex/ words, and the last filter text is remembered.
- Locator hints: warn on index-based XPath and name the next best unique, warning-free locator in the tooltip.
- Added an Appium JavaScript (WebdriverIO) flavor to the Locators tab "Copy as" list and the recorder Output combo.
- Apps tab: Hide system apps checkbox (Android via pm list packages -3, iOS via com.apple. prefix), on by default.
- Visual tab: baseline names default to the foreground app, one-click Update from the current frame, and Rename (with collision check).
- Battery tab: simulate battery level, status and AC/USB power on Android through dumpsys battery, with reset.
- Battery tab: Memory warning section sends onTrimMemory levels to a running Android app through am send-trim-memory.

## 0.1.0

- Mirror and control Android emulators, Android devices and iOS simulators in the Mobile Devices tool window.
- Inspector with element tree, highlight on screen and Mobilewright and Appium locators with duplicate detection.
- Device Tools tabs for logs, performance, files, crashes and more.
- Recorder and player for interaction scripts with assertions.
- Show Locator on Device editor action (Ctrl+Alt+Shift+M).
- Device power actions, automatic reconnect and a Settings page.
- Not tried on a physical iPhone or Android phone.
