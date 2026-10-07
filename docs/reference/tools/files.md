# Files

Browses files on the device and the data of an app. You can preview, download, upload, create folders and delete.

| | |
|---|---|
| **Works on** | Android emulator and phone (physical Android not tried), iOS simulator |
| **Needs** | `adb` for Android; mobilecli and `xcrun` for the iOS simulator |

## Use it

1. Open the **Files** tab and choose a **Location**: *Device storage* or *App data*.
2. For *App data*, pick or type an app, or press **Foreground app**.
3. Move around with **Up**, **Refresh**, the path bar and **Copy path**. Double click or Enter opens a folder.
4. Use **Download**, **Upload**, **New folder** and **Delete** (also in the right-click menu).
5. Select a file to see the preview on the right.

## Good to know

* App data on Android works for debuggable apps only (`run-as`).
* The preview reads at most 256 KB: text, JSON, XML, SharedPreferences and property lists. Anything else says "Binary file" or "Too large to preview".
* For a SQLite database, **Pull and show in the file manager** pulls it with its `-wal`, `-shm` and `-journal` files. There is no SQLite viewer in the plugin.
* The first change in an app's data asks for confirmation, because it can break the app.
* A failed download leaves no partial file and keeps an existing one.
* Physical iPhones are not supported.

## Technical notes and what is not verified

<details>
<summary>Full reference text</summary>

The **Files** tab of Device Tools browses files on the device and the data of an app. **Location** is *Device storage* (Android starts in `/sdcard` and can go up to `/`; an iOS simulator shows its data folder) or *App data*, where you pick or type an app (package name or bundle id; the list comes from the Apps list, **Foreground app** fills in the app in front). A path bar with **Up** (never above the root of the location), **Refresh**, an editable path (Enter goes there) and **Copy path** sits over a table of Name, Type, Size and Modified (click a header to sort; folders always come first). Double click or Enter opens a folder; on iOS and for Android symbolic links a probe decides between folder and file. The buttons **Download** (you pick a folder, the file keeps its name, an existing file asks before it is replaced; the result line shows the local size), **Upload** (a chosen file into the current folder, always as `<folder>/<name>`), **New folder** and **Delete** (asks first and names the entry) are also in the right-click menu; a thin progress bar runs during a transfer. The **preview** on the right reads at most 256 KB: text, JSON (pretty printed), XML (pretty printed), Android **SharedPreferences** (`<map>` XML as a Key / Type / Value table, including string sets) and Apple **property lists** (iOS UserDefaults, binary `bplist00` or XML, as a Key / Type / Value table with nested keys like `a.b[0]`, dates as ISO text, `<data>` as hex) are shown; anything else says "Binary file" or "Too large to preview ... use Download". For a **SQLite** database the preview recognises the file header and offers **Pull and show in the file manager**, which pulls the database together with its `-wal`, `-shm` and `-journal` files from the same folder to a temp folder and reveals it, so you can open it in a database tool; there is no SQLite viewer in the plugin. How it works: Android uses adb only (`find ... -exec stat` for listings, `adb pull`/`push` for device storage, `adb shell run-as <package>` for app data), because mobilecli's `fs pull` reports success for a missing or unreadable file and writes the error text into the local file, and its other errors lose the reason; adb exit codes are checked and its messages are shown. **App data on Android works for debuggable apps only** (`run-as`); for others the message says "not debuggable" and suggests `/sdcard/Android/data/<package>` under Device storage. iOS simulators use `mobilecli fs ls|pull|push|mkdir|rm` with absolute host paths (the app container comes from `xcrun simctl get_app_container <udid> <bundle> data`; system apps have none). Downloading a folder works for Android device storage (adb pull) and on the simulator (recursive copy), not for an Android app's data. App ids may only contain letters, digits, '.', '_' and '-' (the id reaches the device shell). A download goes to a temporary name first and replaces the target only when it completed, so a failed or timed out download leaves no partial file and keeps an existing one; a changed device resets the tab to Device storage. Binary plists with more than 100,000 objects (self referencing or shared arrays) are refused. The temp folder of a pulled SQLite database is removed when the pull fails and kept on success (it is what the file manager shows). The first change in an app's data asks for a confirmation, as files written through `run-as` can break the app. Verified against a Pixel 9 emulator (Android 17) and an iPhone 17 Pro simulator for listing, push, pull, mkdir, delete, preview and `run-as` listing and reading, and the parsers against saved real output. Not verified: physical devices (a physical iPhone is not supported; physical Android should work but was not tried), the panel UI in the sandbox IDE (the logic is unit tested), a real iOS UserDefaults plist (the plist reader was checked against system property lists and plutil-generated samples), huge folders or files, names with line breaks or non-UTF-8 names, several users or work profiles on Android, and pulling a SQLite database with its WAL and opening it in a tool.

</details>

[All Device Tools tabs](../device-tools.md) · [Feature guide](../../FEATURES.md)
