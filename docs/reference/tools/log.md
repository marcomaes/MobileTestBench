# Log

A live device log below the screen. Use it to follow what an app prints and to jump from a stack trace to your source. The Device Tools panel has more tabs next to it.

| | |
|---|---|
| **Works on** | Android emulator and phone (`adb logcat`), iOS simulator (simulator log stream), physical iPhone (`mobilecli device logs`, experimental) |
| **Needs** | adb for Android; mobilecli for **Restart app** and for a physical iPhone |

## Use it

1. Open **Device Tools** (the console icon) and stay on the **Log** tab.
2. Pick a **Level** (Info and up by default) and filter by message text, tag or process.
3. Fill the **App** field: a package name on Android, part of the process name on iOS.
4. Turn on the **.*** toggle to use regular expressions in the message and tag filters.
5. Use **Pause**, **Clear** and **Save...** to freeze, empty or write out the shown lines.
6. Double-click a stack trace row, or right-click and choose **Open in Editor**, to open the source line.

## Good to know

* Only the newest 20,000 lines are kept, and **Save...** writes only the lines that are shown.
* **Restart app** relaunches the app named in the **App** field so the first lines of the start are captured.
* **Open in Editor** only finds files in your project, not libraries or Android framework classes.
* The iOS log is very busy (about 100 lines per second), so the filters matter there.
* An invalid regular expression turns its field red and is ignored until fixed.
* Not verified: a physical iPhone, **Restart app** on an iOS simulator, and Swift/Objective-C frames.

## Technical notes and what is not verified

<details>
<summary>Full reference text</summary>

**Device Tools** (the console icon) opens a tabbed panel below the screen with the tabs Log, Apps, Open URL, Crashes, Location, Display, Input, Network, Battery, Biometrics, WebView, Files, Performance, Visual and Notifications, described below. The first tab, **Log**, is a live log: `adb logcat` for Android emulators and devices, and the simulator's log stream for iOS simulators (a physical iPhone uses `mobilecli device logs`, experimental). Filter by **Level** (Info and up by default), by text in the message, by tag or process, and by **App**: for Android type the package name (`com.example.app`; the app's process ids are looked up and followed when it restarts), for iOS part of the process name. Errors and warnings are coloured, multi-line messages (stack traces) stay together, **Pause** freezes the list while the log keeps arriving, **Clear** empties it, **Save...** writes the lines that are shown to a file, and right-click copies the selected lines. The newest 20,000 lines are kept. The **.*** toggle makes the message and tag filters regular expressions (case-insensitive, found anywhere in the text; the App field stays plain text); an invalid pattern turns its field red, the tooltip and the status line give the reason, and it is ignored until fixed. Regular expressions only look at the first 20,000 characters of a message, but a pathological pattern can still be slow. The text of the message filter is highlighted inside the rows (first line of each message). **Open in Editor** (right-click, or double-click a row) opens the source of a stack trace line such as `at com.example.Foo.bar(Foo.java:12)` or `(Foo.kt:34)`: it looks the file name up in the project (platform file index, so only files in the project, not libraries or Android framework classes), prefers the folder that matches the package when several files share the name, and opens it at that line; a message with several lines uses its first frame, and the status line says when nothing was found. `(Native Method)`, `(Unknown Source)` and obfuscated frames are rejected. **Restart app** (next to the App field, enabled when the App field is filled) un-pauses, empties the list, terminates and relaunches the app named in the App field with `mobilecli apps terminate/launch` (a package on Android, a bundle id on iOS; a failing terminate is ignored, a failing launch is shown) so the first lines of the start are captured; on Android the new process id is looked up for up to 5 seconds so those lines are shown. On iOS a bundle id in the App field also matches the process name by its last part (`com.example.MyApp` matches `MyApp`). A physical iPhone uses `mobilecli device logs` as an experimental source (status line says so); the other devices keep adb and simctl. mobilecli was evaluated as a source for emulators and simulators and not used there: its Android stream has no history (`-T 1`), its iOS stream has no Debug lines, its `--filter` is exact case-sensitive equality that hangs silently when nothing matches (so none of our filters is passed to it) and it adds nothing our parsers do not already give. Closing a log now ends the whole process tree (the mobilecli wrapper leaves its native child behind otherwise). Not verified: a physical iPhone (no device here), the mobilecli daemon being down, Restart app on an iOS simulator (only Android was run), Swift/Objective-C frames (only Java/Kotlin format was checked against real logcat), and opening the file in the IDE by hand (the lookup is tested with fixture files, the open itself uses `OpenFileDescriptor`). The iOS log is very busy (about 100 lines per second), so the filters matter there.

</details>

[All Device Tools tabs](../device-tools.md) · [Feature guide](../../FEATURES.md)
