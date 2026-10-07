# Open URL

Opens a URL or deep link on the device. Use it to test links, app schemes and system handlers without typing on the device.

| | |
|---|---|
| **Works on** | Android emulator and iOS simulator (verified); real devices not verified |
| **Needs** | mobilecli |

## Use it

1. Open the **Open URL** tab.
2. Type a link such as `https://...`, `myapp://...`, `tel:`, `geo:` or `market://`, or pick one from the history.
3. Press **Open** or Enter.
4. Read the result in the status line.
5. Use **Clear history** to empty the history.

## Good to know

* The value must be one line, contain no spaces and start with a scheme.
* The history keeps the last 20 distinct links that opened successfully and is remembered across sessions.
* Picking from the history only fills the field.
* Success only means the system accepted the link, not that the app showed what you expected.
* On iOS, exit code 115 is shown as "No app on the device handles this URL scheme" and 206 as "Not a valid URL".
* Android Settings cannot be opened this way. Not verified: a custom scheme handled by your own app, and real devices.

## Technical notes and what is not verified

<details>
<summary>Full reference text</summary>

The **Open URL** tab of Device Tools opens a URL or deep link on the device (`https://...`, `myapp://...`, also `tel:`, `geo:`, `market://`). Type or pick a link and press **Open** or Enter; picking from the history only fills the field. The history keeps the last 20 distinct links that opened successfully, newest first, and is remembered across sessions (**Clear history** empties it). The value must be one line, contain no spaces and start with a scheme. The status line shows the result or the device's error in plain words (iOS only reports an exit code, so 115 is shown as "No app on the device handles this URL scheme" and 206 as "Not a valid URL"). Success only means the system accepted the link, not that the app showed what you expected. Needs mobilecli. Verified on the Android emulator and iOS simulator with https, tel, geo, market, maps and unresolvable schemes. Not verified: a custom scheme handled by a real app of your own, real devices, very long or non-ASCII URLs, and whether iOS shows an "Open in app?" prompt for some schemes. Android Settings cannot be opened this way (it needs an intent action, not a URI).

</details>

[All Device Tools tabs](../device-tools.md) · [Feature guide](../../FEATURES.md)
