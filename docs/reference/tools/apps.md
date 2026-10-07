# Apps and permissions

Lists the apps on the device and lets you launch, stop, reset and install them. Its Permissions dialog grants or revokes runtime permissions for one app.

| | |
|---|---|
| **Works on** | Android emulator and phone; iOS simulator; iPhone for list, launch and install (clearing data and permissions are skipped there) |
| **Needs** | mobilecli; adb for the Android system-app split and permissions; `simctl` for iOS simulator permissions |

## Use it

1. Open the **Apps** tab; use the filter box, **Hide system apps** and **Refresh** to find an app.
2. Select an app and press **Launch**, **Terminate**, **Clear data** or **Uninstall**.
3. Use **Install...** (or drop a file on the tab): .apk for Android, zipped .app (.zip) for a simulator, .ipa for a real iPhone.
4. Use **Show log of this app** to fill the **Log** tab App filter and switch to it.
5. Press **Reset state...** and tick the steps: terminate, clear data, reset permissions, relaunch.
6. Press **Permissions...** and use **Grant**, **Revoke** or **Reset all** on the list.

## Good to know

* The foreground app is shown above the list and in bold.
* On Android, an app counts as system when it is not in `adb shell pm list packages -3`; on iOS, when its bundle id starts with `com.apple.`.
* **Reset state...** stops at the first failure and shows the exact error.
* Android permissions are read from `adb shell dumpsys package`; install-time permissions such as INTERNET cannot be changed.
* On an iOS simulator, permission state shows as Unknown, and grant or revoke may terminate a running app.
* Not verified: install, uninstall, clear data, the **Reset state...** dialog end to end, and real iPhones.

## Technical notes and what is not verified

<details>
<summary>Full reference text</summary>

The **Apps** tab of Device Tools lists the installed apps (name, id, version) with a filter box, a **Hide system apps** checkbox (on by default, remembered) and **Refresh**. mobilecli does not flag system apps, so on Android an app counts as system when it is not in `adb shell pm list packages -3` (third-party apps), and on iOS when its bundle id starts with `com.apple.` (Apple apps installed from the App Store, such as Pages, are hidden too). The selected app stays visible; if the Android package list cannot be read nothing is hidden and the status line says so, otherwise it shows how many system apps are hidden. Not verified on a device: the split was only unit tested, not run against a real emulator, simulator or phone. The foreground app is shown above the list (with the activity on Android, "Home screen" on iOS) and in bold in the list. Buttons and the right-click menu: **Launch** (starts at once, also on double-click), **Launch with options...** (asks for an optional locale such as `fr-FR,en-GB`, and on Android an optional activity), **Terminate**, **Clear data** and **Uninstall** (both ask for confirmation; clearing is Android and simulator only), **Copy id**, **Show log of this app** (fills the Log tab App filter and switches to it) and **Install...** (.apk for Android, zipped .app (.zip) for a simulator, .ipa for a real iPhone). You can also drop such a file on the tab. Needs mobilecli. The Android list contains launchable apps only; iOS lists system helper apps too, so use the filter. **Reset state...** (button and right-click menu) opens a dialog with four ticked checkboxes: Terminate the app, Clear its data, Reset its permissions (Android: revoke all runtime permissions and clear their flags; simulator: `simctl privacy reset all`) and Relaunch it afterwards. The steps run in that order, each row shows its result and time as it goes, and the first failure stops the run and shows the exact error. Clear and permissions are skipped with a note on a real iPhone. The choices are remembered. Note that on Android and on the simulator Clear data already kills the app, and on Android it also revokes runtime permissions, so the permission step matters mainly when Clear is unticked. Android terminate succeeds even for a package that does not exist. Checked by hand against mobilecli on the emulator and simulator: terminate, clear, permission reset and launch separately (about 1 s on iOS, about 13 s on Android with 3 runtime permissions). Not verified: the dialog itself end to end, a real iPhone, whether `simctl privacy reset` terminates the app when a real entry changes, apps with many runtime permissions, and that the app is in front after the relaunch on Android (it only reports that mobilecli accepted the launch). Verified on the Android emulator and iOS simulator for list, foreground, launch and terminate. Not verified: install, uninstall, clear data, the effect of `--locale` on Android, and real iPhones. The result of an action (including an error such as a failed install or a wrong activity name) stays in the status line below the list while the list reloads.

### Permissions dialog

**Permissions...** (button and context menu of the **Apps** tab, one app selected) opens a dialog for that app. On **Android** it lists the app's runtime permissions as read from `adb shell dumpsys package` with their state (Granted / Denied) and flags; **Grant** and **Revoke** change the selected one (`pm grant` / `pm revoke`) and the list is re-read afterwards, so the table shows the real state (pm silently ignores a permission the app did not request, which is why only the app's own permissions are offered). Install-time permissions such as INTERNET are counted but cannot be changed. **Reset all** is not the system reset (Android has no per-app one; `pm reset-permissions` would hit every app and is never used): it revokes every runtime permission and clears its user-set and user-fixed flags so the app can prompt again. On an **iOS simulator** it offers the `simctl privacy` services (all, calendar, contacts, contacts-limited, location, location-always, photos, photos-add, media-library, microphone, camera, motion, reminders, siri) with Grant / Revoke / Reset; the state is shown as Unknown because it cannot be read, and simctl grant and revoke may terminate the app if it is running (reset did not in the scout test). Camera works but is not in simctl's help text; notifications are not a simctl service. Reset always passes the app's bundle id. Physical iPhones are not supported. A log under the table shows each command and its result or first error line. Verified by scouting on the Android 17 emulator (dumpsys layout, grant and revoke of a denied permission, error output) and on an iOS 26 simulator (grant, revoke, reset of several services); the dialog itself and the following are not verified: revoking a granted permission (kills the app), the Reset all sequence end to end, POST_NOTIFICATIONS, Android below 17, work profiles, and the iOS services location-always, photos-add, media-library, reminders, contacts and all.

</details>

[All Device Tools tabs](../device-tools.md) · [Feature guide](../../FEATURES.md)
