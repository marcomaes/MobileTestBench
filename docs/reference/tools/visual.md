# Visual

A screenshot diff (visual regression) that runs inside the plugin. You save a baseline of the mirror and later compare the current screen against it.

| | |
|---|---|
| **Works on** | The mirrored device (no device command is used) |
| **Needs** | Nothing beyond a connected device |

## Use it

1. Open the **Visual** tab, enter a **Name** (or leave it empty for a default) and press **Save baseline**.
2. Later, select the baseline in the list and press **Compare**.
3. Read the verdict ("Match" or "N regions, X% of the pixels changed") and the baseline | current | diff view. Changed regions are drawn on the mirror.
4. Adjust the **Tolerance** slider (0 to 64, default 8) if small colour differences count as changes.
5. Add **Ignore** areas (device x, y, w, h) to skip dynamic parts such as the status bar.
6. Use **Update**, **Rename**, **Delete** and **Refresh** to manage baselines.

## Good to know

* Baselines are saved under `<project>/.mobiletestbench/baselines/<device name>/`. Add the folder to `.gitignore` or commit it to share baselines.
* **Save baseline** and **Compare** use the frozen frame while Freeze is on.
* If the baseline and the current frame differ in size (rotation, another device or scale), nothing is compared.
* A 1 px shift of a text edge is not a change; a 2 px shift is.
* Ignore areas are remembered per baseline.
* Not verified on devices: the comparison is unit tested on generated images only, and retina iOS scaling of the overlay and ignore areas was not checked.

## Technical notes and what is not verified

<details>
<summary>Full reference text</summary>

The **Visual** tab of Device Tools is a screenshot diff (visual regression) that runs inside the plugin and needs no device command. **Save baseline** stores the mirror frame (the frozen one while Freeze is on) as `<project>/.mobiletestbench/baselines/<device name>/<name>.png` (the IDE system folder when the project has no base path) plus a `<name>.json` with the device, logical and frame size, time and ignore areas; saving over a name replaces the image and keeps its ignore areas (the tab asks for confirmation when the name already exists; selecting a baseline does not fill the Name field; an empty Name field is pre-filled with the foreground app id plus a timestamp, for example `com.example.app-20261007-1530`, or `screen-<timestamp>` on the home screen). Add `.mobiletestbench/` to your `.gitignore` or commit it to share baselines; names are sanitised so they cannot leave the folder. The list shows the baselines of the connected device (Delete, Refresh). **Update** replaces the image of the selected baseline with the current (or frozen) frame after a confirmation and keeps its ignore areas. **Rename** changes the name (image and json sidecar together, ignore areas kept) and refuses a name another baseline already uses. **Compare** diffs the selected baseline with the current frame and shows baseline | current | diff (changed pixels red over a faded current frame), a verdict line ("Match" or "N regions, X% of the pixels changed") and draws the changed regions on the mirror while the tab is open. The **Tolerance** slider (0 to 64, default 8, remembered) is the largest per-channel RGB difference that still counts as equal; an anti-aliasing allowance forgives a pixel that matches a neighbouring pixel of the other image, so a 1 px shift of a text edge is not a change (a 2 px shift is). Nearby changes are merged into one region (bounding box): the frame is cut into 8 px blocks and changes in the same or neighbouring blocks merge, so changes about 1 to 15 px apart merge depending on where they fall relative to the block grid, and changes 16 px or more apart never do and regions under 4 changed pixels are dropped as noise. If the baseline and the current frame differ in size (rotation, another device or scale) nothing is compared and the status says so. **Ignore** areas (device x, y, w, h in logical coordinates, the same as the inspector bounds) skip dynamic parts such as the status bar; they are remembered per baseline. Not verified on the devices: the comparison itself is covered by unit tests on generated images only, and the retina iOS frame scaling of the overlay and ignore areas was not checked against a real simulator.

</details>

[All Device Tools tabs](../device-tools.md) · [Feature guide](../../FEATURES.md)
