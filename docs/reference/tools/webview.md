# WebView

Inspects the web views of hybrid apps and in-app browsers. You see the DOM as a tree, get CSS and XPath selectors for a node, and run JavaScript in the page.

| | |
|---|---|
| **Works on** | Android and iOS (simulator or development build) |
| **Needs** | mobilecli; a debuggable Android app, or an iOS app with the `get-task-allow` entitlement |

## Use it

1. Open the **WebView** tab and press **Refresh** to list the web views of the **foreground app**.
2. Pick another web view in the combo box if the first visible one is not the one you want.
3. Select a node in the DOM tree to see its **CSS selector**, **XPath** and attributes, each selector with its match count and a **Copy** button.
4. Check the orange highlight on the mirror to confirm the element.
5. Type script in the JavaScript console and press **Run** or Ctrl/Cmd+Enter.

## Good to know

* Debug builds only. For other apps the status line shows mobilecli's error with a hint.
* Browsers (Safari, Chrome) and apps without a web view give an empty list.
* Large pages are cut at 2000 elements.
* The highlight is a snapshot. It does not follow scrolling, so select the node again.
* Console results are JSON: return primitives or `JSON.stringify(value)`. The console runs in the live page and can change the app.
* Not verified: the finished tab in a running IDE, XPath counting and the highlight on a device, iframes, shadow DOM, and real devices.

## Technical notes and what is not verified

<details>
<summary>Full reference text</summary>

The **WebView** tab of Device Tools inspects the web views of hybrid apps and in-app browsers (needs mobilecli; Android and iOS). **Refresh** lists the web views of the **foreground app** (title, URL, app) and loads the DOM of the first visible one; pick another in the combo box. The DOM is shown as a tree (`tag#id.class "text"`; head, script and style nodes are hidden unless you tick the box). Large pages are cut at 2000 elements and the status line says "showing the first 2000 of N elements" (a loaded DOM of a 14,000 element page takes about a second). Selecting a node shows:

* a **CSS selector** and an **XPath**, each with the number of matches the page itself reports (`querySelectorAll(...).length` and `document.evaluate(...)`; green for 1, orange for several, red for none or an invalid selector) and a **Copy** button. They are generated from the loaded DOM and prefer a unique id, then a test attribute (`data-testid`, `name`, `aria-label`, ...), then tag and classes, then a short child path from the nearest ancestor with a unique id (`#loginForm > button`, `//*[@id='loginForm']/button`). Elements inside `<svg>` or `<math>` use `*[local-name()='path']` steps in the XPath, because plain tag names do not match their namespace. Before the page answers, the count in the loaded DOM is shown.

* the **attributes** of the element and its own text.

* a **highlight on the mirror** (orange) of the element: its current bounding rect is mapped to device coordinates with the web view bounds and the page width (x = bounds.x + cssX * bounds.width / pageWidth). The same formula was checked against a real screenshot crop on the Android emulator and against the native UI dump on the iOS simulator. The highlight is a snapshot taken at selection time: it does not follow scrolling (select the node again) and is removed when you leave the tab or change device. An element outside the visible part of the web view (scrolled away, or below the fold) is clipped to the visible part, or not highlighted at all (the note says so).

* a **JavaScript console**: type script, press **Run** or Ctrl/Cmd+Enter. It runs as an indirect `eval` in the page, so statements, `var`/`let`, comments and `throw` work and the value of the last expression is returned; promises are awaited. The result is shown as JSON, or the error text. mobilecli converts results to JSON: DOM elements come back as `{}` on Android and as an error on iOS, `undefined` and `null` both show as "undefined (or null)", and object keys come back sorted; return primitives or `JSON.stringify(value)`. The console runs in the live page and can change the app.

* **Native and web:** the inspector keeps showing the native tree. The tab names the native element that owns the web view by comparing bounds (on Android typically `android.webkit.WebView at 0,289 1080x2135`); on iOS the native tree exposes the page as ordinary accessibility nodes, so only the web view bounds are shown (a native node is named only when its type says WebView).

* **Debug builds only.** mobilecli injects an agent into the app: Android needs a debuggable app, iOS the `get-task-allow` entitlement (a simulator or development build). For other apps the status line shows mobilecli's error with that hint. Browsers (Safari, Chrome) and apps without a web view give an empty list. Web view ids change when a screen is left and re-entered; the tab then lists again (once per action, so a persistent error does not loop) and re-selects by URL and title.

* Verified on the playground app's Web View screen with real mobilecli 1.0.17 output on an Android emulator and an iOS simulator (list, eval, tree script, rect mapping; the output is in the test fixtures). **Not verified:** the finished tab in a running IDE, XPath counting and the highlight on a device (the commands were exercised only through scripts, the XPath code path not at all), a scrolled or zoomed page (rects are viewport relative), iframes and shadow DOM (not descended into), several web views at once, and real devices.

</details>

[All Device Tools tabs](../device-tools.md) · [Feature guide](../../FEATURES.md)
