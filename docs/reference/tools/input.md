# Input

Sends hardware buttons, special keys and clipboard text to the device. Use it to press buttons the mirrored screen does not offer and to move text between your computer and the device.

| | |
|---|---|
| **Works on** | Android emulator and phone, iOS simulator. Physical devices are not verified. |
| **Needs** | `mobilecli` for iOS, for the clipboard and for most keys. On Android, `adb` is the fallback for buttons and keys. |

## Use it

1. Open the **Input** tab. A status line at the bottom shows the result or the tool's error text.
2. Under **Hardware buttons**, press Volume up, Volume down, Home, Power / Lock, Back, App switch, Assistant (Android) or Shake (iOS simulator).
3. Under **Keyboard**, press Tab, Esc, Enter, Backspace, Delete or an arrow key. On Android use **Hide keyboard** to close the soft keyboard.
4. Under **Clipboard**, use **Get from device**, **Set on device**, **Set and paste** or **Clear**.

## Good to know

* Buttons that do not work on the current platform are disabled with a tooltip.
* Power / Lock toggles: a second press wakes the screen. On Android, **Wake** sends the WAKEUP key.
* After a button press, the plugin reads the device state back, because "ok" does not prove an effect.
* The clipboard group is disabled without `mobilecli`. Android emulator clipboards are shared with your computer, so Set on device and Clear also change your Mac clipboard.
* On iOS, paste shows an "Allow Paste" prompt on the device that you must answer.
* Hide keyboard is Android only. There is no Show keyboard and no way to hide the iOS keyboard with these tools.

## Technical notes and what is not verified

<details>
<summary>Full reference text</summary>

The **Input** tab of Device Tools has three groups; a status line at the bottom shows the exact result or the tool's own error text. **Hardware buttons**: Volume up, Volume down, Home, Power / Lock, Back, App switch, Assistant (Android) and Shake (iOS simulator), each enabled only on the platform where it was found to work (the others are disabled with a tooltip). On Android they go through `mobilecli io button` (names must be UPPER CASE; adb `input keyevent` is used when mobilecli is missing, and always for Assistant, key 219). On iOS simulators they go through mobilecli too (HOME, VOLUME_UP, VOLUME_DOWN, LOCK; there is no Power or Siri), and Shake sends `xcrun simctl spawn <udid> notifyutil -p com.apple.UIKit.SimulatorShake` (an app has to react to it, for example "Undo Typing" in a focused text field, whose alert stays until dismissed). Power / Lock **toggles**: a second press wakes the screen; **Wake** (Android) sends the WAKEUP key. Because "ok" does not prove an effect, a button press is followed by a read-back: on Android the screen state (`dumpsys power`) and the media volume (`cmd media_session volume`), on iOS the lock state (`notifyutil -g com.apple.springboard.lockstate`). The first volume press while the volume panel is hidden may only show the panel without changing the level. After Lock on iOS, wake it and press Home to unlock, sometimes twice. **Keyboard**: Tab, Esc, Enter, Backspace, Delete and the arrow keys are sent with `mobilecli io keys` (Android falls back to adb key events without mobilecli; iOS needs mobilecli). **Hide keyboard** (Android only) reads `mImeWindowVis` and sends Escape when the keyboard is shown; it never sends Back, which would leave the screen. There is no Show keyboard (tap a text field) and no way to hide the iOS keyboard with these tools. **Clipboard** (needs mobilecli): a text area with **Get from device**, **Set on device**, **Set and paste** (sets, then presses ctrl+v on Android or cmd+v on iOS) and **Clear**. Adb cannot read or set the Android 17 clipboard and `simctl pbcopy` did not work on the tested simulator, so without mobilecli the group is disabled and says so. **Android emulator clipboards are shared with your computer**: Set on device and Clear also change your Mac clipboard. On iOS, paste shows an "Allow Paste" prompt on the device that you must answer; the plugin cannot confirm the paste. Verified with the commands above on a Pixel 9 emulator (Android 17) and an iPhone 17 Pro simulator (iOS 26.5), mobilecli 1.0.17. Not verified: the panel UI against live devices, physical devices, Assistant on a device without the Google app, wake on a device with a PIN lock, a full soft keyboard (the tested AVD shows only Gboard's hardware-keyboard toolbar) and Escape hiding it, iOS volume effects, very long clipboard text, non-text clipboard content on iOS, and clipboard text that starts with `-` (sent after `--`).

</details>

[All Device Tools tabs](../device-tools.md) · [Feature guide](../../FEATURES.md)
