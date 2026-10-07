# Network

Changes the network of an Android device: airplane mode, Wi-Fi, mobile data and, on emulators, speed and latency. Use it to test how an app behaves offline or on a slow connection.

| | |
|---|---|
| **Works on** | Android emulator and phone. Emulator speed and latency only on emulators. iOS simulators are not controllable here. |
| **Needs** | `adb` |

## Use it

1. Open the **Network** tab. It reads the device when shown; **Refresh** reads again.
2. Toggle **Airplane mode**, **Wi-Fi** or **Mobile data**. Each change applies at once.
3. On an emulator, set **Emulator speed** and **Emulator latency**.
4. Check the "Connection" line to see whether Android has a default network.
5. Click **Reset to normal** to restore what this tab changed.

## Good to know

* After a change the device is read back every second until it shows the new value. Airplane mode takes about 8 s to reconnect when switched off.
* Wi-Fi off alone or mobile data off alone leaves the device online. Only airplane mode, or both off, goes offline.
* Conditions stay on the device after the tab or IDE closes. The tab shows a note while the emulator is throttled.
* iOS simulators share the Mac network. The tab points to the Network Link Conditioner on the Mac or a proxy.
* Not verified: that the speed and latency profiles really slow an app's traffic, and physical Android devices (`svc` may be refused).

## Technical notes and what is not verified

<details>
<summary>Full reference text</summary>

The **Network** tab of Device Tools changes the network of an Android device. **Airplane mode**, **Wi-Fi** and **Mobile data** are read when the tab is shown (**Refresh** reads again) and apply at once (`cmd connectivity airplane-mode enable|disable`, `svc wifi enable|disable`, `svc data enable|disable`); after each change the device is read back every second until it shows the new value (up to 15 s for airplane mode, which takes about 8 s to reconnect when switched off), with a "Reconnecting..." note, and the status text shows what the device reports or the exact error. A "Connection" line says whether Android has a default network (`dumpsys connectivity`, not a ping). Note that Wi-Fi off alone or mobile data off alone leaves the device online, because the other one takes over; only airplane mode, or both off, goes offline. On an **emulator** (serial `emulator-NNNN`) two more controls set **Emulator speed** (full, 5g, evdo, lte, hsdpa, umts, edge, gprs, hscsd, gsm) and **Emulator latency** (none, gprs, edge, umts) through the emulator console (`adb emu network speed|delay`; adb handles the console authentication, the plugin reads no token). The adb exit code is always 0 for the console, so the plugin looks for a `KO:` line in the output. The shown value always comes from `emu network status` (a value that matches no preset shows as custom; full and 5g, and gprs and umts, report the same numbers on the tested emulator). **Reset to normal** restores what this tab changed (the values it remembered before its first change, only those that differ); if the tab changed nothing yet it applies normal: airplane off, Wi-Fi and data on, full speed, no delay. The remembered values are stored only after the first write succeeded (a refused write leaves nothing to reset) and live only in the IDE session, and conditions stay on the device (the emulator stays throttled) after the tab or IDE closes, so the tab shows a note while the emulator is throttled. **iOS simulators** share the Mac network: the tab says so and points to the Network Link Conditioner on the Mac (all Mac traffic) or a proxy; `simctl status_bar` only changes the icons and is not used. Verified with the commands above on a Pixel 9 emulator (Android 17). Not verified: whether the speed and latency profiles really slow the traffic of an app (only the console readback was checked; ping on the emulator gives unusable timings), the panel UI against live devices, physical Android devices (`svc` may be refused), Android below 11 (airplane mode falls back to read only through `settings get global airplane_mode_on`, writes show the error), several emulators at once, and Network Link Conditioner (not installed on the test machine).

</details>

[All Device Tools tabs](../device-tools.md) · [Feature guide](../../FEATURES.md)
