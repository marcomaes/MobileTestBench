# Location

Overrides the device GPS location with one point. Use it to test location features without moving.

| | |
|---|---|
| **Works on** | iOS simulator (verified); Android and physical devices not verified |
| **Needs** | mobilecli |

## Use it

1. Open the **Location** tab.
2. Enter latitude and longitude, or pick a place: Amsterdam, London, New York, Tokyo, Sydney or a saved place.
3. Press **Set location** and read the result in the status line.
4. Use **Save place...** to store the current coordinates under a name; **Delete place** removes a saved one.
5. Press **Clear override** to remove the override.

## Good to know

* Latitude is -90..90 and longitude is -180..180, with dot decimals, rounded to 6 decimals when applied.
* Pasting `52.37, 4.90` into either field fills both.
* The override is held by the mobilecli background process, so it may not outlive that process.
* An app only sees the location when it has location permission.
* **Clear override** is always available; presets cannot be deleted.
* Route simulation is not implemented. Not verified: that an Android app receives the coordinates.

## Technical notes and what is not verified

<details>
<summary>Full reference text</summary>

The **Location** tab of Device Tools overrides the device GPS location with one point (needs mobilecli). Enter latitude and longitude (-90..90, -180..180, dot decimals, rounded to 6 decimals when applied; pasting `52.37, 4.90` into either field fills both) or pick a place: Amsterdam, London, New York, Tokyo, Sydney, or places you saved with **Save place...** (name plus the current coordinates, remembered in the plugin settings; **Delete place** removes a saved one, presets stay). **Set location** applies it and the status line shows the result or mobilecli's own error text; **Clear override** removes it and is always available. The override is held by the mobilecli background process, so it may not outlive that process, and an app only sees it when it has location permission. Verified on an iOS simulator (set, error and clear via mobilecli); not verified: that an Android app receives the coordinates, whether the override survives the daemon's idle exit, and physical devices. Route simulation is not implemented.

</details>

[All Device Tools tabs](../device-tools.md) · [Feature guide](../../FEATURES.md)
