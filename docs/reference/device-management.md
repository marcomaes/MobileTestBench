# Device management and viewer tools

Start, stop and reconnect devices, and control how the mirror looks and behaves. Use it to keep a session alive, freeze a short-lived screen, or read exact coordinates.

| | |
|---|---|
| **Works on** | Android emulators and iOS simulators (boot, shut down, reboot); connected Android and iOS devices for the viewer tools |
| **Needs** | mobilecli (Device Power, Orientation, foreground app) |

## Device Power

Boot, shut down or reboot a device from the toolbar.

**Use it**

1. Select a device in the list. Offline devices are greyed out and marked "(offline)".
2. Click the **Device Power** button (lightning icon).
3. Choose **Boot** (offline devices only) or **Shut Down** / **Reboot** (running emulators and simulators only).
4. After a boot, click **Connect** in the notification "... booted. Connect to it?".

**Good to know**

* The plugin never connects on its own after a boot.
* **Shut Down** and **Reboot** ask for confirmation first. Shutdown is a hard stop.
* mobilecli lists only AVDs with an `avd.ini.displayname` line in `config.ini`, and only simulators that were booted at least once.
* Not verified: `device reboot` (never run), and the menu, dialogs and notification have not been run in the UI.

## Reconnect automatically

The panel waits for a device that went away and connects to it again.

**Use it**

1. Leave **Reconnect automatically** on (Settings | Tools | Mobile Test Bench, default on).
2. If the device disappears, the status line shows "Waiting for <device> to come back...".
3. Click **Cancel** to stop waiting.

**Good to know**

* The device counts as gone after two missed polls in a row (polled every 3 s). While waiting, the poll interval grows from 3 s to 10 s, with no time limit.
* Disconnect, Cancel, choosing another device and closing the tool window all end the wait.
* After a Device Power shutdown the panel keeps waiting until you boot the device again. Press **Cancel** if you do not want that.
* Not verified: the panel glue was only compiled, not run. Only the logic is unit tested.

## Project state and live foreground app

The panel remembers its layout per project and shows which app is in the foreground.

**Use it**

1. Reopen the tool window. The last device is pre-selected in the device combo and the layout is restored.
2. Turn on **Reconnect to the last device when the project opens** (Settings | Tools | Mobile Test Bench) to connect automatically.
3. Read the foreground app label at the right end of the status area.
4. Click the label to copy the package id.

**Good to know**

* State is stored in `.idea/workspace.xml`: last device, inspector open, Device Tools open and selected tab, and the splitter proportions.
* The last device is only selected, not connected, unless the setting is on (default off).
* The label is read every 3 s while connected and the tool window is visible. It shows "Home screen" on the iOS home screen and a dash when it cannot be read.
* Not verified: the label and restore against live devices in the sandbox IDE.

## Orientation

Rotate the device between portrait and landscape.

**Use it**

1. Connect a device.
2. Click the **Landscape** toggle in the toolbar.

**Good to know**

* The toggle shows the real orientation, read from the device on connect and after every change.
* After a rotation, click mapping, inspector bounds and recorder geometry use the new size.
* mobilecli switches Android auto-rotate off when it sets an orientation, and it stays off.
* Not verified: the Android gRPC frame in landscape, taps in landscape and physical devices.

## Freeze

Stop the mirror on the current frame to look at a toast, snackbar or loading state.

**Use it**

1. Click the **Freeze** toggle (pause icon).
2. Inspect the frozen screen. The hierarchy of that moment appears in the inspector.
3. Use Screenshot, copy or Generate Page Object on the frozen frame.
4. Click **Freeze** again to resume.

**Good to know**

* Clicks, typing, keys and the scroll wheel do not reach the device while frozen. A click in inspect mode selects from the frozen hierarchy.
* The session keeps running, but new frames are dropped. A "Frozen" badge is drawn on the screen.
* Disconnecting clears the freeze. A running replay is not blocked by Freeze.
* Not verified: whether the hierarchy capture is fast enough on a real device to catch a toast that disappears within a second or two.

## Coordinates, hover info and grid

Read exact positions and element sizes from the mirror.

**Use it**

1. Move the mouse over the mirror and read the hover line under it.
2. Click the **Grid** toolbar toggle to draw a grid and a crosshair.
3. Right click (Ctrl+click on macOS) the mirror to open the copy menu, for example `Copy "x, y"`.

**Good to know**

* Coordinates are logical units: points on iOS, pixels on Android.
* The hover line shows an element only when a hierarchy is already known. Hovering never fetches one, so the element can be stale.
* A right click is never a tap. A tap, double tap or long press sent from the mirror draws a ring with its coordinates for about a second.
* Not verified: the overlay and menu were not tried by hand on running devices, and the hover line was not checked against a rotated iOS frame.

## Technical notes and what is not verified

<details>
<summary>Full reference text</summary>

### Device Power (boot, shut down, reboot)

The device list also shows devices that are not running, greyed out and marked "(offline)": stopped Android AVDs and shut-down iOS simulators (from `mobilecli devices --include-offline`, so mobilecli is needed). Selecting one shows "Device is offline" in the screen area; Connect stays disabled. The **Device Power** button (lightning icon) opens a menu for the selected device:

* **Boot** (offline devices only) runs `mobilecli device boot`, which waits until the device is usable (about 8 s for a simulator, 30 s or more for a cold AVD; the timeout is 120 s). The plugin then looks every 2 s, up to 60 s, for the device in the list (an emulator is recognised by its AVD name, because its adb serial changes) and shows a notification "... booted. Connect to it?" with a **Connect** button. It never connects on its own. On a timeout the notification says to press Refresh Devices.
* **Shut Down** and **Reboot** (running emulators and simulators only) ask for confirmation first. If the device is the connected one, the session is disconnected before the command runs. Shutdown is a hard stop. With **Reconnect automatically** on (the default) a connected device that is shut down or rebooted from here is waited for and connected again by itself once it is back (see Reconnect automatically); with it off, the plugin waits after a reboot and offers Connect, like after a boot.
* The button is disabled while an operation runs, and the status line shows what is happening ("Booting Pixel 9...", "Waiting for ... to come up..."). Errors show mobilecli's own text (for example "simulator is already running").

Limitations and what is not verified:

* mobilecli lists only AVDs that have an `avd.ini.displayname` line in their `config.ini` (Android Studio adds it; AVDs made by hand may lack it), and only simulators that were booted at least once. Others are not shown and cannot be booted here.
* Verified with real mobilecli output: the offline list, boot and shutdown of an iOS simulator, boot and shutdown of a temporary Android AVD, and the error texts. Not verified: `device reboot` (never run; whether it waits, whether an emulator keeps its serial, and the iOS simulator behaviour are unknown, so a short fixed pause plus polling is used), boot of an AVD you actually use, a warm snapshot boot, offline physical devices (not listed), and boot from an IDE started with a minimal environment (mobilecli needs to find the emulator binary). The UI itself (menu, dialogs, notification) has not been run, and no state is saved when an emulator is shut down.
* Booting a second emulator next to a running one uses a lot of memory and CPU.


### Reconnect automatically

When the connected device goes away without you pressing Disconnect (emulator or simulator restarted or shut down, a reboot, adb going offline) the status line shows "Waiting for <device> to come back..." with a **Cancel** link, and the panel connects again by itself once the same device is listed as usable. A normal connect then follows: the hierarchy, the Device Tools tabs and the logs reload. How it works:

* While connected, discovery (the same one as Refresh Devices) is polled every 3 s. The device counts as gone when it is missing or not usable in two polls in a row; a session error counts as one missed poll. A failing discovery (for example adb not answering) changes nothing: discovery reports failures as warnings, so a result with warnings that does not list the device as usable counts as inconclusive, not as a missed poll.
* While waiting, the poll interval grows from 3 s to 10 s. There is no time limit. The device is matched by id, or for an Android emulator by AVD name, because the adb serial can change between boots.
* Disconnect, Cancel, choosing another device in the list and closing the tool window all end the wait. If the device is listed again but connecting fails (still booting), the plugin keeps waiting.
* Device Power Shut Down or Reboot of the connected device does not fight the watcher: it pauses while the power operation runs, then reconnects once the device is up. After a shutdown the panel keeps waiting until you boot the device again (press Cancel if you do not want that).
* Settings | Tools | Mobile Test Bench has a **Reconnect automatically** checkbox (default on). It is read when a connection is made; turning it off does not end a wait that already started (use Cancel).

Not verified against the live sandbox: the logic is unit tested with a fake discovery and clock (`ReconnectTest`), but the panel glue (status line, Cancel link, session teardown, reconnect after a real emulator restart or Device Power reboot) was only compiled, not run.

### Project state and live foreground app

* The panel remembers, **per project** (stored in the project's workspace file, `.idea/workspace.xml`, not in the application settings): the last connected device (id and name), whether the inspector was open, whether Device Tools was open and which tab was selected, and the splitter proportions of the mirror/inspector and of the mirror/Device Tools split. They are restored when the tool window opens, saved about one second after a change and when the panel is disposed. A saved tab that no longer exists is ignored, and invalid proportions fall back to the defaults.
* After the first device refresh the last device is pre-selected in the device combo when it is among the discovered ones (same id, else same name, because an emulator serial changes between boots). It is only selected, not connected, unless **Settings | Tools | Mobile Test Bench | Reconnect to the last device when the project opens** is on (default off); then it connects when that device is running.
* A label at the right end of the status area shows the **foreground app** (name and package/bundle id, "Home screen" on the iOS home screen, a dash when it cannot be read). While a device is connected and the tool window is visible it is read every 3 seconds with `mobilecli apps foreground` on a pooled thread, one call at a time (6 s timeout). Polling stops on disconnect, when the tool window is hidden and on dispose, and a result that arrives for an older generation or another device is dropped. Click the label to copy the package id; the tooltip shows the activity on Android.
* Not verified: the label and restore against the live devices in the sandbox IDE (the code is covered by unit tests, a platform test of the project service and the tool window smoke test), mobilecli's output for apps that have no `appName`, and polling load on a slow device (the on-device agent may be slow; a timeout shows the dash).

### Orientation

The "Landscape" toggle in the toolbar rotates the device between portrait and landscape through `mobilecli device orientation` (needs mobilecli). The toggle shows the real orientation: it is read from the device on connect and after every change, never assumed. After a rotation the click mapping, the inspector bounds and the recorder geometry use the new size, and the hierarchy reloads when the inspector is open. On an iOS simulator the framebuffer stays portrait, so the plugin turns frames upright itself. Limitations: mobilecli switches Android auto-rotate off when it sets an orientation and it stays off. Unverified: the Android gRPC frame in landscape, iOS inspector bounds for ordinary apps in landscape, taps in landscape, and physical devices.

### Freeze

The **Freeze** toggle (pause icon) stops the mirror on the current frame so you can look at a transient screen such as a toast, snackbar or loading state. While it is on:

* New frames are dropped (not queued) and a "Frozen" badge is drawn on the screen; the session itself keeps running.
* Clicks, typing, keys and the scroll wheel do not reach the device; the status bar says so. A click in inspect mode selects the element from the frozen hierarchy.
* Turning Freeze on at once captures the hierarchy of that moment in the background and shows it in the inspector (the inspector opens if it was closed). The automatic refresh is paused, so the tree stays the one of the frozen screen.
* Screenshot, copy and Generate Page Object use the frozen frame and hierarchy.

Turning it off resumes live frames and refreshes the hierarchy if the inspector is open. Disconnecting clears the freeze. Not verified: whether the hierarchy capture is fast enough on a real device to catch a toast that disappears within a second or two, and a running replay is not blocked by Freeze.

### Coordinates, hover info and grid

* **Hover line.** Under the mirror, above the status line, a line shows what is under the mouse while a device is connected: the device coordinates in logical units (points on iOS, pixels on Android), the pixel of the frame image when its resolution differs from the logical space (iOS retina), the position in percent (`x%, y%`, one decimal, taken from the middle of the logical unit) and, when a hierarchy is already known, the deepest element at that point (type, text or label, `#id`) with its size `w x h`. The hierarchy is the one the inspector loaded or froze, else the newest recorder snapshot. Hovering never fetches a hierarchy: with none loaded the line says so, and an element can be stale after the screen changed until the inspector refreshes. The line is separate from the fps status line.
* **Grid** (toolbar toggle, remembered). Draws a light grid over the mirror with the logical coordinate at the top and left edges, and a crosshair through the cursor labelled with its coordinates. The step is chosen from the logical size so that the longer side has at most 12 lines (smallest of 10, 20, 25, 50, 100, 200, 250, 500, 1000, 2000), for example 100 on an iPhone (points) and 200 on a Pixel (pixels). The brief said 50 or 100; larger steps are needed to keep a 2400 px Android screen at about 12 lines.
* **Copy menu.** Right click (or Ctrl+click on macOS) on the mirror opens a menu with `Copy "x, y"`, `Copy "x%, y%"` and, when the hierarchy has an element there, `Copy element size "w x h"` and `Copy element bounds "x, y, w, h"` (logical coordinates, the same as the inspector). The menu only reads, so it is offered in every mode (inspect, recording, assert, frozen). A right click is never a tap any more (before this it was sent like a left click) and does not select in inspect mode.
* **Tap marker.** A tap, double tap or long press that is sent from the mirror draws a ring with its logical coordinates for about a second at the spot. Clicks in inspect mode (selecting), while frozen, or during a replay are not marked.

Not verified: the overlay and the menu were not tried by hand on the running devices (only unit tests of the mapping, formatting and grid step and the tool window smoke test), a safe-area inset overlay is not implemented, and the hover line was not checked against a rotated iOS frame.

</details>

[Feature guide](../FEATURES.md) · [Device Tools](device-tools.md)
