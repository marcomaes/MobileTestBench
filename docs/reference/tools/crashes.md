# Crashes

Lists the crash reports of the connected device and shows the full report. Use it to read and save a crash without digging through logs.

| | |
|---|---|
| **Works on** | Android emulator and iOS simulator (verified); physical devices not verified |
| **Needs** | mobilecli |

## Use it

1. Open the **Crashes** tab; the list loads when the tab is shown.
2. Use the filter box to narrow the list, and **Refresh** to reload it.
3. Select a report to read it in the read-only area.
4. Check the bold red summary line above the report.
5. Use **Copy** or **Save...** (`.txt` for Android, `.ips` for iOS).

## Good to know

* The list shows time, process or app, and id, newest first. There is no polling.
* On iOS, mobilecli reads the Mac's `~/Library/Logs/DiagnosticReports`, so the list holds host crashes (macOS apps too).
* The Android list comes from the logcat crash buffer, so it includes system processes, and ANRs are not listed.
* The process name of an Android Java crash is often a framework class such as `android.os.Parcel`, not your app.
* Not verified: an empty list, a crash of a simulated iOS app, and physical devices.

## Technical notes and what is not verified

<details>
<summary>Full reference text</summary>

The **Crashes** tab of Device Tools lists the crash reports of the connected device (time, process or app, id), newest first, with a filter box and **Refresh** (it loads when the tab is shown; there is no polling). Select one to read the full report in a read-only monospace area, with **Copy** and **Save...** (`.txt` for Android, `.ips` for iOS). A bold red line above the report shows the crash summary when it can be found: the exception for Java crashes (`java.lang.IllegalStateException: ...`), the signal and fault address for native Android crashes (`SIGSEGV (SEGV_MAPERR) fault addr 0x...`), and exception type, signal and termination reason for iOS (`EXC_CRASH (SIGABRT) - Abort trap: 6`). Needs mobilecli. Verified on the Android emulator and iOS simulator for list and get (real output is in the test fixtures). Caveats: on iOS mobilecli reads the Mac's `~/Library/Logs/DiagnosticReports`, so the list holds host crashes (macOS apps too), not only the simulator's; the tab says so. The Android list comes from the logcat crash buffer, so it includes system processes, ANRs are not listed, and the process name of a Java crash is often a framework class such as `android.os.Parcel`, not the app. Not verified: an empty list, a crash of a simulated iOS app, physical devices.

</details>

[All Device Tools tabs](../device-tools.md) · [Feature guide](../../FEATURES.md)
