# Recorder and replay

Record what you do on the device and get test code for it, then replay the steps on the device. Use it to draft tests without writing locators by hand.

| | |
|---|---|
| **Works on** | The connected device. Recording and replay must be on the same platform. Clearing a field on replay was verified on native text, password and multiline fields only. |
| **Needs** | mobilecli for hierarchy dumps and for clearing fields on replay. An iOS simulator without mobilecli (WebDriverAgent only) cannot clear, so `fill` fails there. |

## Recording taps and typing

Every tap, swipe and key press becomes a line of Mobilewright code with a stable locator.

**Use it**

1. Press **Record** (the red dot in the toolbar).
2. Tap, type and swipe on the device.
3. Open the **Recording** tab to see the code.
4. Use **Copy**, **Insert at Caret** or **Clear** on the code.

**Good to know**

* A tap becomes `await screen.getByTestId('login-btn').tap();`. Typing after a tap on a field turns it into `.fill('text')`.
* Swipes become `screen.swipe('up')`, Home becomes `screen.pressButton('HOME')`, Android Back becomes `screen.goBack()`.
* Taps without a locator are written as a comment, never as raw coordinates.
* A tap waits at most 2 s for a fresh hierarchy. After that the line ends with `// check: matched against the screen before the previous action`.

## Assertions and waits

Add checks to the recording by clicking elements instead of tapping them.

**Use it**

1. While recording, press **Assert**.
2. Click an element on the screen. It is not tapped on the device.
3. Choose an entry from the menu, such as *Is visible* or *Has text 'x'*.

**Good to know**

* Example output: `await expect(screen.getByTestId('title')).toHaveText('Welcome');`. The test needs `expect` from `@mobilewright/test`.
* The menu also has *Becomes hidden*, the waits *Wait until visible / hidden / enabled / disabled*, and *Count of matching elements = N* (only when two or more elements match).
* Not verified: the semantics of `waitFor`, `toBeHidden` and `toHaveCount` were read from the Mobilewright package code. The generated code was not executed by Mobilewright.

## Output flavours

Choose the language the steps are written in.

**Use it**

1. Open the **Output** combo in the **Recording** tab.
2. Pick *Mobilewright*, *Appium Java*, *Appium Kotlin*, *Appium Python*, *Appium C#* or *Appium JavaScript (WebdriverIO)*.
3. Use **Copy** or **Insert at Caret**.

**Good to know**

* The choice is remembered. Recording and replay do not change, only the written code.
* A step whose element has no unique Appium locator is written as a comment that says so.
* *Count of matching elements* and a drag with hold are not written as Appium code. They stay comments.
* Not verified: none of the Appium code was compiled or run against an Appium server.

## Replay

Run the recorded steps again on the connected device.

**Use it**

1. Record or load steps in the **Recording** tab.
2. Press **Replay**.
3. Watch the running step highlight and the passed, failed or skipped marks.
4. Press **Stop** to cancel.

**Good to know**

* Each step waits up to the replay element timeout (default 10 s) for its element. The first failing step stops the replay and shows why.
* `fill` clears the field with select all + backspace (`cmd+a` on iOS, `ctrl+a` on Android) and fails with "Failed to clear element" if text is left.
* Custom widgets (React Native, Flutter, WebViews, Compose) may ignore select all.
* Replay uses the plugin's own device control. It does not run the generated code. Your own clicks are ignored while it runs.

## Replay controls and step timeouts

Replay part of a recording, slow it down, or give single steps their own timeout.

**Use it**

1. Select a step and press **Replay from Step**, or select rows and press **Replay Selected**.
2. Set the defaults in Settings | Tools | Mobile Test Bench, group *Replay*: **Replay element timeout (seconds)** and **Delay between steps (ms)**.
3. Right-click a wait, expect or count step and choose **Set timeout...**.
4. Type whole seconds (1 to 3600). An empty answer removes the timeout.

**Good to know**

* The element timeout defaults to 10 (1 to 300). The delay defaults to 0 (0 to 10000). Both are read when a replay starts.
* Starting in the middle assumes the app is already on the right screen.
* A step timeout appears in every output language. Appium output rounds it up to whole seconds.
* Not verified: the Mobilewright `timeout` option names were applied to some matchers by analogy and nothing was tried on a real device.

## Scroll until visible

Replace a run of swipes with one step that scrolls until the next element shows.

**Use it**

1. Select the swipe steps (shift/ctrl click).
2. Press **Scroll Until Visible**, or right-click and choose the menu entry.
3. To change direction or limit, right-click the step and choose **Set scroll direction and limit...**.

**Good to know**

* The swipes must be next to each other, go the same direction and be followed by a step on an element.
* The default limit is 10 swipes, or your selection if higher. The setting range is 1 to 100.
* After the limit, replay fails with "did not become visible after N swipes up".
* Not verified: the generated `isVisible()` loop was not run by Mobilewright, and replay was tested with fake sessions only.

## Technical notes and what is not verified

<details>
<summary>Full reference text</summary>

### Recorder

Press **Record** (the red dot in the toolbar) and use the device: the **Recording** tab turns what you do into Mobilewright code.

* A tap becomes `await screen.getByTestId('login-btn').tap();`. The plugin reads the hierarchy right before the tap, finds the element under the finger and picks its most stable locator: a unique test id (also from a parent button or cell when you tap its text), then any unique `getByRole`, `getByLabel` or `getByText`, then the same locator scoped by a unique parent (`screen.getByTestId('product-item-2').getByTestId('add-to-cart')`), and as a last resort the first locator with `.nth(n)`. The hierarchy is captured in the background (after the device has been quiet for 0.3 s following an action, and every second while idle), so a tap normally does not wait. mobilecli dumps take anywhere from 0.1 to several seconds, so a tap waits at most 2 s for a hierarchy taken after your previous action; after that it uses the newest older one and the line ends with `// check: matched against the screen before the previous action`. Typing does not invalidate the hierarchy. `idea.log` records every capture and how long each tap waited. A screen that changes by itself in the second before a tap can still be matched against the older hierarchy.
* Typing after a tap on a field turns the tap into `.fill('text')`, and Backspace edits that text.
* Swipes and scrolling become `await screen.swipe('up')` (the direction of the finger), Home becomes `screen.pressButton('HOME')` and Back on Android `screen.goBack()`.
* Taps that cannot be tied to a locator are written as a comment, never as raw coordinates.
* **Copy**, **Insert at Caret** and **Clear** work on the recorded code. Stopping the recording keeps the code.
* **Assert** (while recording) adds an assertion instead of an action: press it, click an element on the screen (it is not tapped on the device), and choose from a menu that fits the element: *Is visible*, *Has text 'x'*, *Contains text 'x'*, *Has value 'x'* (input fields), *Is enabled / disabled*, *Is checked* (switches and checkboxes). It is recorded as `await expect(screen.getByTestId('title')).toHaveText('Welcome');`, so the test needs `expect` from `@mobilewright/test`. Mobilewright assertions retry until they hold, so they also act as explicit waits. The element under the cursor is highlighted while Assert is on. The menu also has *Becomes hidden* (`await expect(loc).toBeHidden();`; an element that left the screen counts as hidden), explicit waits *Wait until visible / hidden / enabled / disabled* (`await loc.waitFor({ state: 'hidden' });`, same rules as Mobilewright: hidden also holds for a missing element, disabled needs the element to exist) and, only when the locator matches two or more elements on the screen, *Count of matching elements = N* (`await expect(loc).toHaveCount(3);`, N is prefilled and editable, and the locator is written without `.nth()`). The semantics of `waitFor`, `toBeHidden` and `toHaveCount` were read from the installed Mobilewright package code, not run against it, and the generated code was not executed by Mobilewright.
* **Output** (combo in the Recording tab, remembered): *Mobilewright* (default), *Appium Java*, *Appium Kotlin*, *Appium Python*, *Appium C#* or *Appium JavaScript (WebdriverIO)*. Recording and replay do not change; the same steps are only written in the chosen language in the list, **Copy** and **Insert at Caret**. Each step carries the best unique Appium locator of its element, computed from the same hierarchy when it is recorded (`id`, then `accessibility id`, `-android uiautomator` / iOS predicate / class chain, `xpath (in parent)`, `xpath`, the same choice as the Generate Page Object dialog). A step whose element has no unique Appium locator is written as a comment that says so (never as coordinates).
  * WebdriverIO output: `await $(...).click()`, `setValue(...)`, `expect($(...)).toBeDisplayed()` / `toHaveText(...)` assertions and `waitForDisplayed({ timeout })` / `waitForEnabled` waits; gestures use `driver.execute('mobile: ...')` like the other flavors. The code assumes `$`, `driver` and `expect` from `@wdio/globals` (a leading comment names the import). A count assertion is a comment, as in the other Appium flavors.
  * Conventions: the code assumes a `driver` and, for waits and asserts, a `wait` (a `WebDriverWait` of 10 s); when a recording needs them, leading comment lines show the declaration and the import. Tap is `driver.findElement(By.id("x")).click();`; fill is `clear()` then `sendKeys(...)` (Mobilewright `fill` replaces the text); *Is visible* and the waits use `ExpectedConditions` (Java, Kotlin, Python `expected_conditions`; C# uses `wait.Until(d => ...)` lambdas because `ExpectedConditions` is no longer part of Selenium .NET); text, contains, value, enabled and checked are asserts on the visible element: JUnit 5 `assertEquals` / `assertTrue` (Java, Kotlin), plain `assert` (Python), NUnit `Assert.That` (C#). Becomes hidden is `invisibilityOfElementLocated`. Scroll until visible is a bounded loop of swipes. Back is `driver.navigate().back()`, Home is `mobile: pressKey` (Android) or `mobile: pressButton` (iOS).
  * Gestures use the Appium `mobile:` commands of the recorded platform, because no single form works on both: Android UiAutomator2 `mobile: longClickGesture`, `doubleClickGesture` and `swipeGesture` (the whole window, 75 %); iOS XCUITest `mobile: touchAndHold`, `doubleTap` and `swipe`. A recording is for one platform, so the output is too.
  * Not written as Appium code: *Count of matching elements* (the unique Appium locator matches one element) and a drag with hold; both stay comments.
  * Verified: the `mobile:` command names and argument names (UiAutomator2 `docs/android-mobile-gestures.md`, `README.md` and the XCUITest `execute-methods.md` on GitHub), the `executeScript` forms in the Java, Python and C# examples of those docs, and the locator strings (shared with the Locators tab). Not verified: none of the generated code was compiled or run against an Appium server; the swipe direction meaning of `swipeGesture` (assumed to be the finger direction, as in Mobilewright), the 75 % distance (a guess), `RemoteWebElement.getId()` / C# `WebElement.Id` / Kotlin `id` for the element id on your client versions, the C# `MobileBy` names (see the Locators tab note), and that `WebDriverWait` ignores a missing element in the wait lambdas.
* **Replay** runs the recorded steps again on the connected device, with the running step highlighted and finished steps marked passed, failed or skipped. Like Mobilewright, each step waits (up to the replay element timeout, default 10 s) for its element to show up in the hierarchy and then acts on its center; assertions retry for up to that timeout (or the step's own timeout; see Replay controls and Step timeouts below) and fail with what the element had instead; the first step that fails stops the replay and shows why. `fill` taps the field, clears it and types the text: it presses select all + backspace in one mobilecli call (`cmd+a` on iOS, `ctrl+a` on Android; ctrl+a on iOS only moves the cursor) and reads the field back, and like Mobilewright it fails the step with "Failed to clear element" when text is left, instead of appending. Clearing was verified with mobilecli on native text, password and multiline fields only; custom widgets (React Native, Flutter, WebViews, Compose) may ignore select all, and an iOS simulator without mobilecli (WebDriverAgent only) cannot clear, so `fill` fails there. On Android, text equal to the field's `hint` attribute counts as empty (uiautomator reports the hint as text); a uiautomator dump without a `hint` attribute cannot be told apart and was not verified. Waits and assertions poll like `expect`, swipes use the middle of the screen, comments are skipped, and **Stop** cancels the run. The recording and the device must be the same platform. Your own clicks are ignored while a replay runs.
* **Replay controls**: *Replay* runs everything. *Replay from Step* (button, or right-click **Replay from this step**) runs the selected step and everything after it; with several rows selected it starts at the first one. *Replay Selected* (button, or **Replay selected steps**) runs from the first to the last selected row (a selection with gaps replays the gaps too). Highlighting and result marks use the real row numbers, steps outside the range keep no mark, and the run still stops at the first failure. Starting in the middle assumes the app is already on the right screen. Two settings in Settings | Tools | Mobile Test Bench, group *Replay*: **Replay element timeout (seconds)** (default 10, 1 to 300) is how long a step waits for its element, wait or assertion when the step has no timeout of its own, and **Delay between steps (ms)** (default 0, 0 to 10000) pauses between two steps that run, for slow motion (not before the first step, not for skipped comments). The settings are read when a replay starts.
* **Step timeouts**: right-click a wait, expect or count step (several can be selected) and choose **Set timeout...**, then type whole seconds (1 to 3600); an empty answer removes it and the replay timeout setting applies again. Scroll until visible has no timeout (its limit is its swipe count). The timeout is part of the step in every output language: Mobilewright `await loc.waitFor({ state: 'visible', timeout: 30000 });` and `await expect(loc).toHaveText('x', { timeout: 30000 });` (also `toHaveCount(3, { timeout: ... })`); Appium uses a dedicated wait for that step, e.g. Java `new WebDriverWait(driver, Duration.ofSeconds(30)).until(...)`, Kotlin `WebDriverWait(driver, Duration.ofSeconds(30))`, Python `WebDriverWait(driver, 30)`, C# `new WebDriverWait(driver, TimeSpan.FromSeconds(30))`; the count assertion has no Appium form. Replay uses the step timeout instead of the setting. Not verified: the Mobilewright `timeout` option names were taken from the Mobilewright skill docs (`waitFor({ state, timeout })`, `toBeVisible({ timeout })`) and applied to the other matchers by analogy, they were not run against Mobilewright; the Appium forms were not run against an Appium server; nothing was tried on a real device (fake sessions in tests). Appium output rounds a timeout up to whole seconds.
* **Scroll until the next element is visible**: select one or more swipe steps in the list (shift/ctrl click), then press the **Scroll Until Visible** button or right-click and choose the menu entry. The swipes must be next to each other, go the same direction and be followed by a step on an element (tap, long press, double tap, fill, or an expect or wait that wants the element visible; "is hidden" or "not visible" steps are rejected). They are replaced by one step, written as `for (let i = 0; i < 10 && !(await loc.isVisible()); i++) { await screen.swipe('up'); }`, with the element step kept after it. The limit is 10 swipes, or the number of swipes you selected when that is higher. Replay checks the hierarchy before the first swipe and after each one (the same swipe as a Swipe step, with a short pause for the scroll to settle) and stops as soon as the element is visible and its center is on the screen; if the hierarchy cannot be read it does not swipe but retries until the element timeout; after the limit it fails with "did not become visible after N swipes up". Not verified: the generated `isVisible()` loop was not run by Mobilewright (it is the locator method as named in the brief), and the replay was tested with fake sessions only, not on a device. To change the direction (up, down, left, right) or the limit (1 to 100 swipes), right-click the scroll step and choose **Set scroll direction and limit...**; the generated code in every output language follows. The dialog itself was not tried by hand, only the core logic is unit tested.
* While recording, clicks act on the device (they do not select elements, as they do when only inspecting).
* Mobilewright code only for now. Replay uses the plugin's own device control, not Mobilewright: it does not run the generated code.

</details>

[Feature guide](../FEATURES.md) · [Device Tools](device-tools.md)
