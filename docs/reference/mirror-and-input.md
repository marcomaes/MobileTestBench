# Mirror and input

Shows the screen of a connected device in the IDE and sends your mouse and keyboard input to it. Use it to drive a device, capture screenshots and record video without leaving the IDE.

| | |
|---|---|
| **Works on** | Android emulators, Android devices, iOS simulators, iOS devices |
| **Needs** | mobilecli for iOS and for screen recording; adb for Android devices; WebDriverAgent for iOS simulators without mobilecli |

## How each device type is driven

| | Android emulator | Android device | iOS simulator | iOS device |
|---|---|---|---|---|
| Screen | Live stream via emulator gRPC (`streamScreenshot`) | `mobilecli screencapture` MJPEG (fallback `adb exec-out screencap` polling, about 1 frame per second) | `mobilecli screencapture` MJPEG (or WDA MJPEG without mobilecli), fallback screenshots | `mobilecli screencapture` MJPEG, fallback `mobilecli screenshot` |
| Input | gRPC `sendMouse` / `sendKey` | `adb shell input` | `mobilecli io` (or WebDriverAgent without mobilecli) | `mobilecli io` (tap, swipe, text, button, keys) |
| Hierarchy | `mobilecli dump ui` (fallback `uiautomator dump`) | `mobilecli dump ui` (fallback `uiautomator dump`) | `mobilecli dump ui` (or WDA `/source` without mobilecli) | `mobilecli dump ui` |

## Mouse and gestures

Use the mouse on the mirror like a finger on the device.

**Use it**

1. Click to tap, drag to swipe, and use the scroll wheel for a vertical swipe.
2. Hold the mouse still for about 700 ms for a long press. A ring shows when it fires.
3. Double click for a double tap.
4. Hold **Option/Alt** and drag to press, hold 700 ms, then drag.

**Good to know**

* Setting **Long press time** ranges from 300 to 3000 ms.
* Double tap and drag with hold need the Android emulator gRPC connection. Elsewhere a double click stays a plain tap and Option/Alt + drag stays a normal swipe.
* Inspect mode keeps its meaning (a click selects), but Option/Alt gestures still go to the device.
* Recording writes `await loc.longPress({ duration: 700 });` and `await loc.doubleTap();`. A drag with hold is recorded as a comment and not replayed.
* Not verified: the plugin's own gRPC code, iOS simulator through WebDriverAgent, and the iOS hold-drag and true iOS double tap.

## Keyboard and toolbar

Type on the keyboard while the screen has focus, and use the toolbar for device actions.

**Use it**

1. Click the screen, then type. Enter, Backspace and (Android) Escape = Back work as keys.
2. Use **Back**, **Home**, **Recent Apps** and **Type Text** in the toolbar.
3. Hover the status line to see the transport and, after a fallback, the reason.

**Good to know**

* The toolbar also has Refresh Devices, Connect, Disconnect, Device Power, Landscape, Inspect Elements, Refresh Hierarchy, Freeze, Grid, Record, Device Tools, Generate Page Object, Screenshot, Record Screen to Video and Settings.
* Each button has a tooltip.
* Not verified: the fallback wording on the status line was not checked against live fallbacks.

## Screenshot

Copy or save what the device shows right now.

**Use it**

1. Click **Screenshot** (the eye icon).
2. Choose to copy to the clipboard or save as a PNG. The file name has the device and the time.
3. With an element selected in the inspector, pick a variant that outlines it, handy for bug reports.
4. Toggle "Also save the hierarchy (XML) next to the PNG" to get `<name>.xml` with the PNG.

**Good to know**

* The XML option is remembered.
* The XML is the frozen hierarchy while Freeze is on, else the last one read, else a fresh read.
* Not verified on a device. Only the XML writer is unit tested.

## Record Screen to Video

Record the device screen to an MP4 file.

**Use it**

1. Click **Record Screen to Video** (toolbar toggle).
2. Choose where to save the MP4.
3. Click the button again to stop. The status line shows the saved file and its size.

**Good to know**

* Needs mobilecli.
* Disconnecting also stops and saves the recording.
* Setting **Screen recording | Time limit (seconds)** (0 = no limit, up to 3600) stops it automatically. It is read when the recording starts.
* A still screen gives a very short video, because emulator and simulator frames only change when the screen changes.
* Not verified: the automatic stop against a real device.

## Fallbacks

The plugin switches to a simpler transport when the preferred one is not usable.

**Use it**

1. Hover the status line to see which transport is in use.
2. Read the reason shown after a fallback.

**Good to know**

* If the emulator gRPC endpoint is not usable (for example JWT auth), the session falls back to adb automatically.
* An iOS simulator without mobilecli uses WebDriverAgent for input and hierarchy, as the table shows.
* The status line wording was not checked against live fallbacks.

## Technical notes and what is not verified

<details>
<summary>Full reference text</summary>

### How each device type is driven

| | Android emulator | Android device | iOS simulator | iOS device |
|---|---|---|---|---|
| Screen | Live stream via emulator gRPC (`streamScreenshot`) | `mobilecli screencapture` MJPEG (fallback `adb exec-out screencap` polling, about 1 frame per second) | `mobilecli screencapture` MJPEG (or WDA MJPEG without mobilecli), fallback screenshots | `mobilecli screencapture` MJPEG, fallback `mobilecli screenshot` |
| Input | gRPC `sendMouse` / `sendKey` | `adb shell input` | `mobilecli io` (or WebDriverAgent without mobilecli) | `mobilecli io` (tap, swipe, text, button, keys) |
| Hierarchy | `mobilecli dump ui` (fallback `uiautomator dump`) | `mobilecli dump ui` (fallback `uiautomator dump`) | `mobilecli dump ui` (or WDA `/source` without mobilecli) | `mobilecli dump ui` |

### Mouse, gestures and keyboard

* **Mouse:** click = tap, drag = swipe, scroll wheel = vertical swipe.
* **Gestures:** hold the mouse still for about 700 ms (setting **Long press time**, 300 to 3000 ms) for a **long press** (a ring shows when it fires, and the release sends nothing more); **double click** = double tap; **Option/Alt + drag** = press, hold 700 ms, then drag. Inspect mode keeps its meaning (a click selects), but Option/Alt gestures still go to the device.
  * Long press works everywhere: Android via emulator gRPC or `adb shell input swipe x y x y ms`, iOS via `mobilecli io longpress` (or a WebDriverAgent pointer action on a simulator without mobilecli). Verified on the Pixel_9 emulator and the iPhone 17 Pro simulator with mobilecli, 700 ms.
  * Double tap and drag with hold need the **Android emulator gRPC** connection: adb and mobilecli calls take 150 ms or more each, past Android's 300 ms double tap timeout, and mobilecli has no double tap or gesture command. Where they are unavailable (adb only, iOS through mobilecli) a double click stays a plain tap and Option/Alt + drag stays a normal swipe. The double click always sends its first click as a tap straight away (no delay on every tap), so the app sees a tap followed by the double tap.
  * Recording: `await loc.longPress({ duration: 700 });` and `await loc.doubleTap();` on the same locator a tap would get (the tap before a double click is folded into the double tap). Mobilewright has no drag API, so a drag with hold is recorded as a comment and not replayed. Replay performs long presses and double taps.
  * Not verified: the plugin's own gRPC code (only a scratch harness with the same calls was run), iOS simulator through WebDriverAgent (WDA was not running), and the iOS hold-drag and true iOS double tap (no working mobilecli path was found; the WDA payloads are only unit tested).
* **Keyboard:** type while the screen has focus. Enter, Backspace and (Android) Escape = Back.
* **Toolbar:** Refresh Devices, Connect, Disconnect and Device Power; Back, Home, Recent Apps, Type Text and the Landscape toggle; Inspect Elements, Refresh Hierarchy, Freeze, Grid, Record and Device Tools; Generate Page Object, Screenshot, Record Screen to Video and Settings. Each button has a tooltip.
* Hover the status line to see the transport and, after a fallback, the reason (emulator gRPC failed or is unavailable, mobilecli or WDA MJPEG stream ended, WebDriverAgent not configured). The wording was not checked against live fallbacks.
* **Record Screen to Video** (toolbar toggle, needs mobilecli): choose where to save an MP4, record, and press the button again to stop; the status line shows the saved file and its size. Disconnecting also stops and saves it. Setting **Screen recording | Time limit (seconds)** (Settings | Tools | Mobile Test Bench, 0 = no limit, up to 3600) stops and saves it by itself; the limit is read when the recording starts. The automatic stop was not tried against a real device. Emulator and simulator frames only change when the screen changes, so a still screen gives a very short video.

### Screenshot and fallbacks

* **Screenshot** (the eye icon): copy the current screen to the clipboard or save it as a PNG (the file name has the device and the time). With an element selected in the inspector you can also choose the variants that outline that element in the picture, handy for bug reports. The last menu entry toggles "Also save the hierarchy (XML) next to the PNG" (remembered): Save as PNG then also writes `<name>.xml` with the same base name, the UI tree of the connected device (the frozen hierarchy while Freeze is on, else the last one read, else a fresh read) with the device attributes and bounds as x/y/width/height. Not verified on a device; the XML writer is unit tested.
* If the emulator gRPC endpoint is not usable (for example JWT auth), the session falls back to adb automatically.

</details>

[Feature guide](../FEATURES.md) · [Device Tools](device-tools.md)
