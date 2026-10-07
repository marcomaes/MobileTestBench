# Device Tools

The tabbed panel below the screen (the console icon). One page per tab: a short guide first, and the full technical notes, limitations and what has not been verified in a collapsible block at the end. Back to the [feature overview](../FEATURES.md).

| Tab | What it does | Works on |
|---|---|---|
| [Log](tools/log.md) | A live device log below the screen. | Android emulator and phone (`adb logcat`), iOS simulator (simulator log stream), physical iPhone (`mobilecli device logs`, experimental) |
| [Apps and permissions](tools/apps.md) | Lists the apps on the device and lets you launch, stop, reset and install them. | Android emulator and phone; iOS simulator; iPhone for list, launch and install (clearing data and permissions are skipped there) |
| [Open URL](tools/open-url.md) | Opens a URL or deep link on the device. | Android emulator and iOS simulator (verified); real devices not verified |
| [Crashes](tools/crashes.md) | Lists the crash reports of the connected device and shows the full report. | Android emulator and iOS simulator (verified); physical devices not verified |
| [Location](tools/location.md) | Overrides the device GPS location with one point. | iOS simulator (verified); Android and physical devices not verified |
| [Display](tools/display.md) | Changes how the device looks and which language and time zone it uses. | Android emulator and phone (some rows), iOS simulator. Physical iPhones show "not supported". |
| [Input](tools/input.md) | Sends hardware buttons, special keys and clipboard text to the device. | Android emulator and phone, iOS simulator. Physical devices are not verified. |
| [Network](tools/network.md) | Changes the network of an Android device: airplane mode, Wi-Fi, mobile data and, on emulators, speed and latency. | Android emulator and phone. Emulator speed and latency only on emulators. iOS simulators are not controllable here. |
| [Battery](tools/battery.md) | Simulates the battery of an Android emulator or device and sends memory warnings to an app. | Android emulator and device. On iOS simulators the tab only explains what to do instead. |
| [Biometrics](tools/biometrics.md) | Simulates Face ID, Touch ID and fingerprint sensors. | iOS simulator and Android emulator. Physical devices and iPhones are disabled with an explanation. |
| [WebView](tools/webview.md) | Inspects the web views of hybrid apps and in-app browsers. | Android and iOS (simulator or development build) |
| [Files](tools/files.md) | Browses files on the device and the data of an app. | Android emulator and phone (physical Android not tried), iOS simulator |
| [Performance](tools/performance.md) | Charts memory and CPU of one app, plus frame statistics on Android, and measures app start time. | Android and iOS simulator (frame data on Android only; physical iPhones not supported) |
| [Visual](tools/visual.md) | A screenshot diff (visual regression) that runs inside the plugin. | The mirrored device (no device command is used) |
| [Notifications](tools/notifications.md) | Sends test notifications to a device. | iOS simulator and Android (physical devices not supported) |
