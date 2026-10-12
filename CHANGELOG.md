# Changelog

## 1.0.1

- First signed build. No functional changes since 1.0.0.

## 1.0.0

- Mirror and control Android emulators, Android phones, iOS simulators and iPhones in the Mobile Devices tool window. An iPhone needs a one-time agent install with a development provisioning profile.
- Android phones stream through mobilecli (adb screenshots as the fallback), and a foldable switches to the active display when it is folded or unfolded.
- Inspector with element tree, highlight, duplicate detection and locators for Mobilewright, Appium and WebdriverIO, plus scoped locators and an accessibility audit.
- Element filter with OR groups (OR or |), negated words (-word, !word) and /regex/ words. The last filter text is remembered.
- Locator hints warn about index-based XPath and name the next best unique locator in the tooltip.
- Locators tab: "Copy as" language list (Mobilewright, Appium Java, Kotlin, Python, C# and WebdriverIO) and a "Whole line" option.
- Recorder and player for interaction scripts, with assertions, replay controls and Appium or WebdriverIO output. Scroll until visible steps have a configurable direction and swipe limit.
- Device Tools tabs: Log, Apps, Open URL, Crashes, Location, Display, Input, Network, Battery, Biometrics, WebView, Files, Performance, Visual and Notifications.
- Apps tab: Hide system apps option, on by default.
- Display tab: Android Pointer location and Don't keep activities toggles.
- Battery tab: simulate battery level, status and power source on Android, send memory warnings (onTrimMemory) to a running app.
- Visual tab: baseline names default to the foreground app, with one-click Update and Rename.
- Screenshot can also save the hierarchy XML next to the PNG.
- Screen recording with an optional time limit (0 to 3600 s) that stops and saves the video automatically.
- Setting for the long press time (300 to 3000 ms, default 700).
- The status line tooltip names the transport and why a fallback happened.
- Show Locator on Device editor action (Ctrl+Alt+Shift+M).
- Device power actions, automatic reconnect, and the last device is remembered per project.
- Clear messages when adb or mobilecli is missing. mobilecli is required for the element inspector on Android 17.

## 0.1.0

- Mirror and control Android emulators, Android devices and iOS simulators in the Mobile Devices tool window.
- Inspector with element tree, highlight on screen and Mobilewright and Appium locators with duplicate detection.
- Device Tools tabs for logs, performance, files, crashes and more.
- Recorder and player for interaction scripts with assertions.
- Show Locator on Device editor action (Ctrl+Alt+Shift+M).
- Device power actions, automatic reconnect and a Settings page.
- Not tried on a physical iPhone or Android phone.
