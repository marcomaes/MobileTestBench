# Notifications

Sends test notifications to a device. On the iOS simulator it pushes an APNs payload; on Android it posts a shell notification or sends an intent.

| | |
|---|---|
| **Works on** | iOS simulator and Android (physical devices not supported) |
| **Needs** | `xcrun` (`simctl push`) for iOS; `adb` for Android |

## Use it

1. Open the **Notifications** tab. The last line shows the exact output or error of the tool.
2. iOS simulator: pick the app, fill Title, Subtitle, Body, Badge, Sound, Category and optional custom data, or tick **Raw JSON**, then press **Send**.
3. Android: fill title, text, style and tag, then press **Post**.
4. Android: use the **Send intent** group (`am broadcast`) to trigger your app's own debug receiver.
5. Reload an earlier send from the **History** combo (last 15).
6. Android: press **Clear shell notifications** to remove the ones you posted.

## Good to know

* iOS payloads are limited to 4096 bytes. Invalid JSON, a missing `aps` or a non-numeric badge disable **Send**.
* "Sent" only means the simulator accepted the payload. A banner appears only if the app has been granted notification permission, which simctl cannot grant.
* Android notifications come from `com.android.shell`, not your app, so they cannot test your app's channels or tap actions.
* Android cannot send FCM messages from adb.
* `am broadcast` prints "completed: result=0" even when no receiver exists, so that does not prove delivery.
* Not verified: the panel UI in the sandbox IDE and delivery when the app is not running.

## Technical notes and what is not verified

<details>
<summary>Full reference text</summary>

The **Notifications** tab of Device Tools sends test notifications; the last line shows the exact output or error of the tool (tooltip: the full text) and a **History** combo reloads any of the last 15 sends (kept in the plugin settings, newest first, no duplicates). **iOS simulator**: pick the app (an editable combo of the installed bundle ids, the foreground app preselected; a bundle id that is not installed gets a warning, because `simctl push` accepts any id), then fill Title, Subtitle, Body, Badge, Sound, Category and an optional custom data JSON object, or tick **Raw JSON** and write the payload yourself. A read-only preview shows the payload as it will be sent (`{"aps":{"alert":{...},"badge":..,"sound":..,"category":..}, ...custom}`) with its size as `N / 4096 bytes` (UTF-8 bytes of the compact JSON; red above the limit, which the simulator refuses). Invalid JSON, a missing or empty `aps` or a non-numeric badge disable **Send**. Send writes the compact payload to a temporary file and runs `xcrun simctl push <udid> <bundle> <file>`; because simctl exits 0 for invalid JSON, success means exit 0 AND "Notification sent to ..."; otherwise simctl's error text is shown. "Sent" only means the simulator accepted the payload: a banner appears only if the app has asked for and been granted notification permission, which simctl cannot grant (Settings > Notifications in the simulator). **Android**: **Post** runs `adb shell "cmd notification post ..."` with title, text, a style (plain, big text, inbox lines, messaging with `who:text` lines) and a tag (posting again with the same tag replaces it). Every value is single-quoted for the device shell and sent as one command string (adb joins arguments and the device shell splits them again, so separate arguments would post the wrong title and tag); newlines in the fields are refused. This notification comes from the Android shell package (`com.android.shell`), not from your app, so it cannot test your app's own handling, channels or tap actions. **Clear shell notifications** cancels the shell package's notifications with `service call notification 1 ...` (there is no `cmd notification cancel`; the transaction number is version specific, so the tab lists the notifications afterwards and says when some remain). Android cannot send FCM messages from adb: use your backend or the Firebase console, or the **Send intent** group, which runs `am broadcast` with an action, a package or `package/.Class` component and extras (`key=value` per line, optional type prefix `i:`, `z:`, `l:`, `f:`; strings by default) so you can trigger your app's own debug receiver. `am broadcast` prints "completed: result=0" even when no receiver exists, so that does not prove delivery. Verified on a Pixel 9 emulator (Android 17) and an iPhone 17 Pro simulator (iOS 26.5) when scouting the commands and their error output (saved as fixtures); the payload building, quoting, parsing and history are unit tested. **Not verified**: the panel UI in the sandbox IDE, whether a banner shows once permission is granted, delivery when the app is not running, how individual `aps` keys beyond alert, badge, sound and category behave, Android bigpicture/media styles, icons and content intents, the clear call on other Android versions, an `am broadcast` to a real receiver with extras, and physical devices (not supported).

</details>

[All Device Tools tabs](../device-tools.md) · [Feature guide](../../FEATURES.md)
