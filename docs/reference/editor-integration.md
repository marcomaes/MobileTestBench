# Locators in the editor

Check a locator from your test code against the device screen without leaving the editor. It tells you how many elements the locator matches.

| | |
|---|---|
| **Works on** | The device connected in the Mobile Devices tool window |

## Show Locator on Device

The action takes a locator from your code and tries it on the mirrored screen.

**Use it**

1. Select the locator text, or put the caret on its line.
2. Run **Show Locator on Device** from the editor context menu, the Tools menu, or with `Ctrl+Alt+Shift+M`.
3. Read the result in the balloon and the viewer's status line.
4. Look for the green highlights on the mirror.

**Good to know**

* It understands lines such as `await screen.getByTestId("a").tap()`, `driver.findElement(By.id("x")).click()`, `LOGIN = (AppiumBy.ID, "x")` and quoted XPath strings. Only the locator is cut out, so `.tap()` or `.click()` is ignored.
* Results are `3 matches`, `1 match (unique)`, `no match`, `not supported: ...`, `no locator found ...` or `no device connected: connect one in Mobile Devices`.
* The hierarchy is read fresh on a background thread, unless the screen is frozen, in which case the frozen hierarchy is used.
* The highlight stays until you send the next input to the device. With the inspector open, the tree refreshes and the first match is selected.
* Not verified: it was not clicked in a running IDE against a live device, and the shortcut was not checked against every keymap. Locators over several lines must be selected, and locators in variables or constants are not followed.

## Technical notes and what is not verified

<details>
<summary>Full reference text</summary>

### Locators in the editor

**Show Locator on Device** (editor context menu, Tools menu, shortcut Ctrl+Alt+Shift+M) takes the selected text, or the line with the caret when nothing is selected, finds the locator in it, and tries it on the screen of the device connected in the Mobile Devices tool window. Examples it understands: `await screen.getByTestId("a").tap()`, `driver.findElement(By.id("x")).click()`, `self.driver.find_element(AppiumBy.ACCESSIBILITY_ID, "y")`, `LOGIN = (AppiumBy.ID, "x")`, `"xpath=//Button[@name='b']"`, `"id=com.x:id/y"`, a quoted XPath string, and a selection made inside an XPath. Only the locator is cut out (the `.tap()` or `.click()` is ignored); the evaluation is the same as the Locators tab playground (Mobilewright chains, XPath, all the Appium forms), so the same things are refused.

* The hierarchy is read fresh from the device on a background thread (the editor does not wait), unless the screen is frozen: then the frozen hierarchy is used.
* Every match is highlighted in green on the mirror; the highlight stays until you send the next input to the device (or the inspector refreshes). With the inspector open, the tree is refreshed and the first match is selected.
* The result is shown as an IDE balloon and in the viewer's status line for a few seconds: `3 matches`, `1 match (unique)`, `no match`, `not supported: ...`, `no locator found ...` or `no device connected: connect one in Mobile Devices`. If the tool window was never opened, the action opens it (and says no device is connected until you connect one).
* The viewer registers itself with a project service (`LocatorTargets`), which is how the action reaches it.
* Not verified: the shortcut is only checked not to clash with the defaults I know, not against every keymap; the action was tested without a device (extraction, evaluation on sample hierarchies, registry, enabled state, the tool window still builds) but not clicked in a running IDE against a live device. Locators spread over several lines must be selected; locators in variables or constants are not followed.

</details>

[Feature guide](../FEATURES.md) · [Device Tools](device-tools.md)
