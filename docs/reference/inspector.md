# Inspector and locators

The inspector shows the element tree of the screen and suggests locators for the selected element. Use it to pick a locator that finds one element, and to try a locator before you put it in a test.

| | |
|---|---|
| **Works on** | Android and iOS (the element filter keys work on both) |

## Element tree and filter

The tree lists every element, and the filter above it keeps only the ones you want.

**Use it**

1. Toggle **Inspect Elements** (the target icon) to open the inspector next to the screen.
2. Start typing to use speed search, or type text in the filter to keep elements whose type, id, label, text or value contains it.
3. Tick **Visible** or **Interactive** to hide invisible or non-actionable elements.
4. Add attribute terms such as `clickable=true class~Button text="Log in"`, or use `OR` to start a new group.

**Good to know**

* Terms are `key=value`, `key!=value` and `key~value`, all case-insensitive. A leading `-` or `!` negates a word, and `/regex/` matches type, id, label, text and value.
* A term that cannot be applied is listed in red under the field and does not filter. An invalid regular expression is reported and ignored.
* The last filter text is remembered (setting `elementFilter`).
* Not verified: the filter is checked by unit tests on sample hierarchies only, not on a live device.

## Selecting and interacting

You can select elements from the screen or the tree, and still use the device.

**Use it**

1. Hover over the screen to highlight the element under the cursor.
2. Click to select it in the tree.
3. Hold Option/Alt while you click, drag or scroll to interact with the device.
4. Right-click an element in the tree to copy a locator or choose **Copy All Properties**.

**Good to know**

* A plain click selects an element and does not touch the device.
* The tree refreshes by itself shortly after you send input, and also when the screen changes without input (an animation ends, a screen loads). The plugin compares a sparse pixel grid of each frame, skipping the top strip so the status bar clock does not count as a change.
* The splitter keeps the inspector at least 200 px wide and the device screen at least 240 px wide, so neither can be dragged to nothing.
* Errors, such as a failed hierarchy dump, are shown in full in place of the tree.

## Locators tab and Copy as

The **Locators** tab lists Mobilewright and Appium locators for the selected element.

**Use it**

1. Select an element and open the **Locators** tab.
2. Check the **unique** or **dup (n)** label on each row.
3. Choose a format in **Copy as:**: *As shown*, *Mobilewright*, or Appium as *Java*, *Kotlin*, *Python*, *C#* or *JavaScript (WebdriverIO)*.
4. Double-click a row to copy it, or right-click and choose **Insert at Caret** to write it into the open editor.
5. Tick **Whole line** to wrap an Appium locator as a statement.

**Good to know**

* Mobilewright locators are written with single quotes (`getByTestId('x')`). Tick **Use double quotes in Mobilewright locators** in Settings | Tools | Mobile Test Bench (setting `useDoubleQuotes`, default off) to get `getByTestId("x")`; it applies to locators and recorded steps shown after the change.
* Mobilewright locators follow the order `getByTestId` > `getByRole` > `getByLabel` > `getByText`. Appium alternatives include `id`, `accessibility id`, `-android uiautomator`, `-ios predicate string`, `-ios class chain` and XPath.
* When the best locator is a duplicate, a **"... in parent"** variant is suggested first, for example `screen.getByTestId('product-item-2').getByTestId('add-to-cart')`.
* A row with no form in the chosen language is copied as shown, and the status line says so.
* Not verified: the Appium strings were checked against client sources, not compiled or run. C# uses `MobileBy` because the .NET client has no `AppiumBy`. Java needs java-client 8 or newer. The WebdriverIO strings are unit tested only.

## Trying a locator

Type a locator in the field above the table to see how many elements it finds.

**Use it**

1. Type a Mobilewright locator, an XPath or an Appium locator in the field above the table.
2. Read the match count.
3. Look at the green highlights on the screen.

**Good to know**

* Mobilewright chains with `.nth(n)`, `.first()` and `.last()` work.
* Appium locators work as `id=com.x:id/y` (the `id=` prefix is required) or as code such as `By.id("x")`. Anything the table or **Copy as** produces can be pasted back.
* Unsupported forms, for example `fromParent`, `UiScrollable` or `By.name`, show `not supported yet: ...` instead of a count.
* Not verified: nothing was compared with a real Appium server. How Appium combines `childSelector` with `findElements` and the `MATCHES` regex dialect are assumed.

## Weak-locator warnings

A warning icon marks locators that may break.

**Use it**

1. Look for the warning icon next to a locator.
2. Hover it to read the tooltip.
3. Use the next best locator named in the tooltip.

**Good to know**

* Warned locators are: not unique, matching nothing, absolute XPath, index based XPath or text/label based.
* The tooltip names the next best locator that is unique and has no warning.
* The generated `xpath (in parent)` locators use `[1]` on purpose and are not flagged.

## Properties

The **Properties** tab shows all raw attributes of the selected element.

**Use it**

1. Select an element.
2. Open the **Properties** tab.
3. Right-click the element in the tree and choose **Copy All Properties** to copy them.

**Good to know**

* **Copy All Properties** gives type, bounds, XPath and all raw attributes as `key=value` lines.
* Any raw attribute can be used as a filter key, for example `package=com.example` or `index=0`.

## Accessibility

The **Accessibility** tab lists elements without a label or text, small touch targets and text that may have too little contrast, following WCAG 2.2.

**Use it**

1. Open the **Accessibility** tab.
2. Read the status line to see what could be checked.
3. Read the **Kind** column: red is an AA failure, orange is guidance, gray is a hint.
4. Untick **Contrast hints** to switch the contrast estimate off.

**Good to know**

* Issues are outlined in red on the screen while the tab is open.
* Touch target sizes use 24 x 24 for AA, and guidance sizes of 44 x 44 pt on iOS and 48 x 48 dp on Android. Android sizes need the screen density, and if it cannot be read those rules are skipped.
* The contrast check is a hint: only ratios below 4.4 are reported, and icons and other non-text parts are not checked.
* Not verified: the iOS frame format, rotated screens, Compose UIs, and the tab on live devices beyond the Playground app.

## Technical notes and what is not verified

<details>
<summary>Full reference text</summary>

### Inspector

Toggle **Inspect Elements** (the target icon) to open the inspector next to the screen:

* **Element tree** with speed search (just start typing) and a filter above it: type text to keep only elements whose type, id, label, text or value contains it (their parents stay), and tick **Visible** or **Interactive** to hide invisible or non-actionable elements. The field also takes attribute terms next to free text, separated by spaces and all required to match: `key=value` (equals), `key!=value` (not equal) and `key~value` (contains), all case-insensitive, for example `clickable=true enabled=false class~Button text~Login`. Put a value with spaces in double quotes (`text="Log in"`). These keys work on Android and iOS: `enabled`, `clickable`, `focused`, `checked`, `selected`, `visible`, `interactive`, `checkable`, `focusable`, `scrollable`, `password`, `long-clickable` (true or false; a missing attribute counts as false, and `clickable` on iOS means a control type), `type` or `class` (short or full name), `id`, `identifier`, `resource-id` or `resourceid`, and `label`, `text`, `content-desc`, `contentdesc`, `desc` or `value` (these four all look at the label, text and value of the element). Any other key is read from the raw attribute (for example `package=com.example`, `index=0`). A term that cannot be applied (`=x`, `enabled=maybe`, an open quote) is listed in red under the field as not understood and does not filter. Besides that: a word with a leading `-` or `!` is negated (`-error`, `-clickable=true`, `-/spinner/`); a case-insensitive regular expression between slashes is matched against type, id, label, text and value (`/^btn_(ok|cancel)$/`; quote it if it contains spaces: `"/log in|sign in/"`); and `OR` (or `|`) starts a new group, so `type=Button OR type=TextField` or `login OR /sign ?in/` shows elements that match any group (all words inside a group must match). An invalid regular expression is reported under the field and ignored. Bare uppercase `OR`, `|`, words starting with `-` or `!` (except `!=`) and words written `/x/` are operators now, not free text; quote them to search for the literal text. The last filter text is remembered between sessions (setting `elementFilter`). Hover the field for the syntax. Verified by unit tests on the sample hierarchies only, not yet by eye on a live device. It refreshes by itself shortly after you send input to the device.
* **Option/Alt + click, drag or scroll** interacts with the device while inspecting; a plain click selects an element.
* **Right-click an element in the tree** to copy any of its locators (every Mobilewright and Appium strategy shown in the Locators tab) or **Copy All Properties** (type, bounds, XPath and all raw attributes as `key=value` lines).
* **Hover** over the screen to highlight the element under the cursor; **click** to select it in the tree.
* **Locators tab:**
  * Mobilewright locators in mobilewright's priority order (`getByTestId` > `getByRole` > `getByLabel` > `getByText`), ported from Mobilewright Inspector's `locator-derivation.js`.
  * Appium alternatives: `id`, `accessibility id`, `-android uiautomator`, `-ios predicate string`, `-ios class chain`, relative and absolute XPath.
  * Every locator shows **unique** or **dup (n)** so you can see whether it is ambiguous.
  * When the best locator is a duplicate (every product card has the same `add-to-cart` button), a **"... in parent"** variant is suggested first: the same locator inside the closest enclosing element that is unique, preferring one with a test id over one found by its label or text. "Enclosing" is by bounds, as in Mobilewright, so it also works in flattened trees (React Native) where the card is a sibling of its button. For example `screen.getByTestId('product-item-2').getByTestId('add-to-cart')`, plus an `xpath (in parent)` for Appium that is checked to find only that element. The Appium `xpath (in parent)` names the parent by its own attribute and is only offered when it provably finds just that element: `//Card[@resource-id="product-item-2"]//Button[...]` when the card is an ancestor, otherwise `.../following-sibling::Button[...][1]` (or `preceding-sibling`, `following`, `preceding`) for flattened trees where the card sits next to its button.
  * Double-click copies a locator. Right-click offers **Insert at Caret**, which writes it into the open editor.
  * **Copy as:** (above the table, remembered) chooses what double-click, right-click **Copy**, **Insert at Caret** and the element tree menu (**Copy Appium Java id**, ...) put on the clipboard or into the editor: *As shown* (default), *Mobilewright*, or an Appium locator as code in *Java*, *Kotlin*, *Python*, *C#* or *JavaScript (WebdriverIO)*. Examples: Java/Kotlin `By.id("x")`, `AppiumBy.accessibilityId("x")`, `By.xpath("...")`; Python `(AppiumBy.ID, "x")`; C# `By.Id("x")`, `MobileBy.AccessibilityId("x")`; WebdriverIO `$('id=x')`, `$('~x')`, `$('android=new UiSelector()...')`, `$('-ios predicate string:...')`, `$('-ios class chain:...')`, `$('//xpath')`. Page object generation does not offer WebdriverIO yet. The WebdriverIO strings are unit tested only and were not run against a real WebdriverIO session. A row that has no form in the chosen language (a Mobilewright row while Java is chosen, an Appium row while Mobilewright is chosen) is copied as shown and the status line says so. Mobilewright TypeScript and JavaScript are the same for a one-line locator, so there is one entry. The **Whole line** checkbox (remembered) wraps an Appium locator as a statement: Java/Kotlin `driver.findElement(By.id("x"))`, Python `driver.find_element(AppiumBy.ID, "x")`, C# `driver.FindElement(By.Id("x"))`, WebdriverIO `await $('~x')`; *As shown* and *Mobilewright* are unchanged. Unit tested only.
    * Not verified: the Appium strings were checked against the client sources on GitHub, not compiled or run. The .NET client has no `AppiumBy` class, so C# uses `MobileBy` (inferred from the source tree, not from the NuGet package; the generated page objects were corrected the same way). Java needs java-client 8 or newer for `AppiumBy`. Mobilewright is 0.0.1 and its API may change. On Android, Appium `id` is the full resource id as shown in the table.
  * Type a Mobilewright locator (also chained, `.nth(n)`, `.first()` and `.last()`) an XPath or an Appium locator in the field above the table to try it: the match count shows and the matches are highlighted in green on the screen.
    * **Appium locators** are evaluated too, in the forms people copy. Strategy and value as the table shows them: `id=com.x:id/y`, `accessibility id=Login`, `-android uiautomator=new UiSelector().text("Login")`, `-ios predicate string=type == "XCUIElementTypeButton" AND name == "x"`, `-ios class chain=**/XCUIElementTypeButton[`name == "x"`]`, `xpath=//...`, `class name=...` (a bare id is ambiguous, so the `id=` prefix is required). Code: Java/Kotlin `By.id("x")`, `AppiumBy.accessibilityId("x")`, `AppiumBy.androidUIAutomator("...")`, `AppiumBy.iOSNsPredicateString("...")`, `AppiumBy.iOSClassChain("...")`, `By.xpath("...")`; Python `(AppiumBy.ID, "x")` and `find_element(AppiumBy.X, "...")` (also `by=`/`value=`); C# `By.Id("x")`, `MobileBy.AccessibilityId("x")` (and `AppiumBy.*` spelled the same way); each optionally inside `driver.findElement(...)`, `findElements(...)`, `find_element(s)(...)` or `FindElement(s)(...)`. Everything the table's rows and the Copy as combo produce can be pasted back (a test checks that each finds exactly its own element).
    * Matching: `id` is the full resource id on Android (`:id/` short ids are not expanded) and `name` on iOS; `accessibility id` is `content-desc` on Android and `name` on iOS. UiSelector subset: `resourceId`, `resourceIdMatches`, `text`, `textContains`, `textStartsWith`, `textMatches`, `description`, `descriptionContains`, `descriptionStartsWith`, `descriptionMatches`, `className`, `classNameMatches`, `packageName`, `packageNameMatches` (only these string methods exist in UiSelector; `resourceIdContains` and the like are refused), the booleans `clickable checkable checked enabled focusable focused longClickable scrollable selected`, `index(n)`, `instance(n)` (both from 0) and `childSelector(...)` (any descendant); `...Matches` must match the whole string. iOS predicate subset: fields `type name label value identifier enabled visible accessible`, operators `== = != <> CONTAINS BEGINSWITH ENDSWITH LIKE MATCHES IN {...}` with `[c]` and `[d]`, combined with `AND`/`&&`, `OR`/`||`, `NOT`/`!` and parentheses. Class chain subset: `**/`, `/`, `*`, `XCUIElementTypeX`, `[`predicate`]` and `[n]` / `[-1]` (1-based, applied in order among the elements found below the current one).
    * Anything outside this (`fromParent`, `UiScrollable`, other predicate fields or operators, `$...$` class chain conditions, `By.name`, string concatenation) shows `not supported yet: ...` instead of a count. A strategy for the other platform (a UiSelector on an iOS screen) is refused with a message.
    * Not verified: only the plugin's own logic and the documented semantics were used, nothing was compared with a real Appium server. UiSelector `instance`, `index` and `childSelector` follow the Android reference (0-based, descendants at any depth); how Appium UiAutomator2 combines `childSelector` with `findElements`, how the XCUITest driver orders `[n]` against a `**` search and a following predicate, and the ICU regex dialect of `MATCHES` (the JDK regex is used) are assumed. `identifier` is treated as `name`; a boolean attribute (or `package`) that the loaded hierarchy dump never provides (for example `accessible` on iOS or `scrollable` on the mobilecli Android path) is refused with `not supported yet: '<attr>' is not in this hierarchy dump` instead of counted, while on a node that lacks it in a dump that has it elsewhere it counts as false; and a missing string attribute never matches `==` but matches `!=` (as `nil` does in NSPredicate).
  * A warning icon with a tooltip marks weak locators: not unique, matching nothing, absolute XPath, index based XPath (`//x[3]`, `(//x)[2]`, `[last()]`, `[position()=2]`, `[@index=1]`) or text/label based. The tooltip of a warned locator also names the next best locator that is unique and has no warning (same framework first, otherwise another one). The generated `xpath (in parent)` locators use `[1]` on purpose and are not flagged.
* **Properties tab** with all raw attributes.
* **Errors** (for example a failed hierarchy dump) are shown in full, wrapped, in place of the tree.
* **Accessibility tab** lists interactive elements without a label or text, touch targets that are too small, and text that may have too little contrast, following WCAG 2.2. A status line above the table says what could be checked (`Density 420 dpi (2.63x) - frame sampling on`, `Density unknown: Android size rules skipped`, `no frame: contrast skipped`), the **Contrast hints** checkbox (remembered) switches the contrast estimate off, and the **Kind** column colors AA failures red, guidance orange and hints gray. Issues are outlined in red on the screen while the tab is open (all kinds in the same color).
  * **Touch targets.** Below 24 x 24 without enough spacing around the target fails SC 2.5.8 (AA). Below the recommended size is guidance only, not an AA failure: 44 x 44 pt on iOS (Apple, WCAG AAA 2.5.5), 48 x 48 dp on Android (Material). iOS bounds are points. Android bounds are pixels, converted to dp with the screen density (`adb shell wm density`, an override wins, read once per connection; if it cannot be read the Android size rules are skipped and the status line says so). The 24 dp spacing circle is measured in dp too. Only the outermost control counts (iOS: not inside a cell or control; Android: no clickable or checkable ancestor, so a row's children and a spinner's text are part of the target). Not size checked: inline links (iOS Link, Android clickable TextView), iOS switches, sliders and text fields, Android switches, checkboxes, radio buttons, seek bars and spinners on the 48 dp rule (they draw smaller than their touch area; the 24 dp rule still applies), disabled Android controls, Android system UI (`com.android.systemui:` ids), and Android controls cut by the screen edge (their visible size says nothing). Android SeekBars are not clickable in the dump, so they are not audited at all.
  * **Low contrast hint (SC 1.4.3, 4.5:1).** For visible, enabled elements that show text (Android: the `text` attribute; iOS: label or value of static text, buttons, links and text fields) the audit samples the mirror frame inside the element bounds (the frozen frame while Freeze is on, otherwise the frame current when the hierarchy capture started). Pixels are grouped by their top 5 bits per channel; the background is the biggest group, the text color the group with the highest contrast against it, and both colors are the exact mean of their pixels. The message shows the ratio and both colors. It is a hint: regions that look like photos or gradients (many sizeable color groups, or no dominant background), regions under 12 x 8 frame pixels, text almost the same color as its background, frames whose shape does not match the screen, and disabled elements are skipped. Large text only needs 3:1, but the dump has no font size, so 4.5:1 is used. Because anti-aliased text makes the estimate slightly low, only ratios below 4.4 are reported. Contrast of icons and other non-text parts (SC 1.4.11) is not checked.
  * Not verified: the iOS frame format (a lossy transport could add color noise; Android frames are lossless), an `Override density` line (the parser follows the AOSP output format), rotated screens, Compose UIs, and the tab itself against live devices beyond the Playground app. The Playground sets `content-desc` to a test id, so Missing name rarely fires on Android there.

</details>

[Feature guide](../FEATURES.md) · [Device Tools](device-tools.md)
