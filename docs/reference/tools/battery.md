# Battery

Simulates the battery of an Android emulator or device and sends memory warnings to an app. Use it to test low battery, charging states and `onTrimMemory` handling.

| | |
|---|---|
| **Works on** | Android emulator and device. On iOS simulators the tab only explains what to do instead. |
| **Needs** | `adb` |

## Use it

1. Open the **Battery** tab.
2. Move the level slider (0 to 100, applied on release), pick a status, and tick or untick the AC / USB power checkboxes. Each change applies at once.
3. Click **Reset to real battery** to return to the real battery.
4. In the **Memory warning** section, pick the foreground app or type a package, and choose a level.
5. Click **Send memory warning** so the app's `onTrimMemory` fires.

## Good to know

* The simulated battery state stays on the device after the tab or IDE closes. The tab shows a note while it is active.
* The controls show what `dumpsys battery` reports afterwards, or the error text.
* The app must be running to receive a memory warning.
* On iOS simulators `simctl` cannot change the battery or send a memory warning. Use Simulator > Debug > Simulate Memory Warning.
* Low storage simulation is not implemented.
* Not verified on a live device: whether apps react (low battery, power saver), physical Android devices and wireless charging.

## Technical notes and what is not verified

<details>
<summary>Full reference text</summary>

The **Battery** tab of Device Tools simulates the battery of an Android emulator or device with `adb shell dumpsys battery set level|status|ac|usb <value>` and `dumpsys battery reset`. A level slider (0 to 100, applied on release), a status combo (unknown, charging, discharging, not charging, full) and AC / USB power checkboxes apply at once; the device is read back from `dumpsys battery` afterwards and the controls show what it reports, or the error text (dumpsys exits 0 for bad input, so the output text is checked). **Reset to real battery** runs `dumpsys battery reset`. The simulated state stays on the device after the tab or IDE closes, so the tab shows a note while it is active. iOS simulators: the tab explains that `simctl` cannot change the battery (`status_bar --batteryLevel` only changes the icon). Parsing and command building are unit tested; not verified against a live device: the panel UI, whether apps react (low battery, power saver), physical Android devices and wireless charging. The same tab has a **Memory warning** section: pick the foreground app or type a package, choose a level (hidden, running moderate/low/critical, background, moderate, complete) and **Send memory warning** runs `adb shell am send-trim-memory <package> <LEVEL>` so the app's `onTrimMemory` fires (the app must be running; the output text is checked because it exits 0 for an unknown process). iOS simulators: the tab points to Simulator > Debug > Simulate Memory Warning, as `simctl` has no such command. Command building and result interpretation are unit tested; not verified against a live device. Low storage simulation is not implemented.

</details>

[All Device Tools tabs](../device-tools.md) · [Feature guide](../../FEATURES.md)
