# TODO

Feature ideas for Mobile Test Bench, written from the point of view of someone who writes and debugs mobile tests every day. Ordered by how much daily friction each group removes. Items marked (mobilecli) can build on a mobilecli subcommand that already exists in 1.0.14; the rest need new work.

## 1. Close the inspector-to-test loop (highest value)

- [x] "Insert locator at caret": right-click a locator, "Insert at Caret". Inserts the locator text as shown.
- [x] Insert locator in the language of the test: "Copy as" in the Locators tab (As shown, Mobilewright, Appium Java/Kotlin/Python/C#/JavaScript WebdriverIO), remembered; used by double-click, Copy, Insert at Caret and the tree menu. Also fixed C# page objects to use `MobileBy`.
- [x] Locator language whole-line form (checkbox "Whole line", setting `locatorWholeLine`).
- [ ] Locator language, next: a settings page entry and a WebdriverIO page object class.
- [x] Fix for duplicate ids: a "... in parent" locator (and an Appium `xpath (in parent)`, also for flattened trees through sibling/following axes) is suggested first when the best locator is not unique; the recorder uses it instead of `.nth(n)` when it can; the playground evaluates chained locators.
- [ ] Duplicate ids, next: confirm against Mobilewright that a chained `getByTestId` on a locator scopes by bounds (only `getByRole` and `getByPlaceholder` chaining is documented), Appium scoping for strategies other than XPath, and suggesting a fix for duplicate ids in the app itself.
- [x] Generate a PageObject class for the current screen, in Mobilewright (TypeScript, JavaScript) or Appium (Java, Kotlin, Python, C#), with a table to pick and rename elements (Generate Page Object... in the toolbar).
- [x] Page objects: locators as private fields from a generated (or your own, remembered) base class, getters or constructor fields as alternatives, sorting by name.
- [ ] Page objects, next: project conventions (the rules of an existing page object framework), one page object for several screens, components for repeated items such as a product card, waits and assertions as methods, saving into the right package, a package or namespace line for Java, Kotlin and C#, own base class members other than `screen`/`driver`, and checking the generated code against each client's real API.
- [x] Locator quality hints: warning icon and tooltip for not unique, matches nothing, absolute XPath and text/label locators.
- [x] Locator quality hints, more: warning for index based XPath, and the tooltip names the next best unique locator without a warning.
- [x] Locator playground: type a Mobilewright locator or an XPath in the Locators tab, see the match count and the matches highlighted in green on the screen.
- [x] Locator playground for Appium strategies other than XPath (id, accessibility id, UiSelector, predicate, class chain), in bare and Java/Kotlin/Python/C# code forms.
- [ ] Locator playground, next: UiSelector `fromParent` and `UiScrollable`, predicate `SELF`, numeric comparison and subqueries, class chain `$...$` conditions, and checking the counts against a real Appium server.
- [ ] Re-validate a saved locator against the current screen (still unique? still found?).
- [x] Auto refresh the hierarchy 700 ms after the last input while the inspector is open (toolbar buttons, keyboard, and taps/swipes/scroll made with Option held). Without Option, clicking in inspect mode still selects an element.
- [x] Accessibility tab: interactive elements without a label or text, and iOS standalone buttons/tabs under 24 pt without spacing (WCAG 2.2 SC 2.5.8, AA) or under 44 pt (recommendation) (children of cells and controls, inline links, and system switches/sliders/text fields, are skipped). Issues are outlined in red on the screen while the tab is open; selecting a row selects the element.
- [ ] Accessibility audit: validate the rules against a real accessibility audit (Xcode Accessibility Inspector) on several apps, to tune false positives.
- [x] Accessibility audit: Android touch targets in dp (24 dp AA minimum, 48 dp Material guidance, density from `wm density`) and a low contrast hint (WCAG 1.4.3) estimated from the mirror frame.
- [ ] Accessibility audit, next: duplicate ids that are not list items; large-text 3:1 detection (needs a font size); contrast for icons (SC 1.4.11); verify contrast on iOS frames, rotated screens and Compose UIs; per-kind overlay colors.
- [ ] Compare hierarchy of two devices: blocked by multi-device support (group 9).
- [x] Filter the element tree by text (type, id, label, text, value), visible and interactive.
- [x] Filter the element tree by enabled/clickable/focused and by attribute (`key=value`, `key!=value`, `key~value`).
- [x] Element filter, more: `OR` / `|` groups, negated words (`-word`, `!word`), `/regex/` words, last filter remembered (setting `elementFilter`).
- [ ] Element filter, next: attribute-name completion in the field (skipped).
- [x] Right-click an element in the tree: copy any locator (XPath, UiSelector, predicate, class chain, Mobilewright) or all properties.

## 2. Record and replay

- [x] Record taps, swipes and typing as Mobilewright code, using the best locator per tap (Record button, Recording tab).
- [x] Recorder: Appium output (Output combo: Mobilewright, Appium Java, Kotlin, Python, C#, JavaScript WebdriverIO), remembered.
- [ ] Recorder Appium output, next: a count assertion and drag (needs a non-unique locator and a drag step), running the generated code against an Appium server, a whole-test wrapper (class, setup, imports), and checking the WebdriverIO output against a real run.
- [ ] Recorder: assertions from the current screen (several at once) and left/right swipe names checked against Mobilewright (only up/down are documented).
- [x] Replay a recording on the connected device, with step highlighting (Replay / Stop in the Recording tab).
- [x] Recorder: long press and double tap steps (drag with hold is only a comment: Mobilewright has no drag API).
- [x] Replay: clear a field before fill (select all + backspace, then verified).
- [x] Replay: element timeout and delay between steps settings, replay from a selected step, replay selected steps.
- [ ] Replay: replay on a different device of the same platform, a speed setting beyond the fixed delay, a per-step delay, and a timeout for tap/fill element lookups on a single step.
- [x] Insert assertions while recording (Assert button: visible, text, contains text, value, enabled/disabled, checked).
- [x] Explicit waits (`waitFor`), becomes-hidden assertion, and element count assertions.
- [ ] Replay: on Android with the uiautomator fallback, a uiautomator without a `hint` attribute reports an empty field's hint as text, so the clear check can fail a correctly emptied field; unverified on a real uiautomator dump.
- [x] Recorder: a per-step timeout on waits and expects (right-click Set timeout..., written in every output language).
- [ ] Recorder: `not` for the other matchers, and clearing fields in custom widgets (React Native, Flutter, WebView) and with WebDriverAgent only.
- Dropped: export to Gherkin. A recorder cannot know the user's intent, so it can only write imperative, UI-level steps ("I tap on ..."), which is the opposite of good BDD. Recorded Mobilewright code is the output instead.

## 3. Debug failing tests

- [x] Screenshot on demand: copy to clipboard or save as PNG, optionally with the selected element outlined.
- [x] Screenshot: optionally save the hierarchy dump (XML) next to the PNG (remembered toggle in the Screenshot menu).
- [ ] Screenshot: annotate with text/arrows.
- [x] Screen recording start/stop with save location (toggle in the toolbar, mobilecli `screenrecord`).
- [x] Screen recording: time limit setting (`screenRecordingLimitSeconds`, 0 = none, auto stop timer in `DeviceViewerPanel`).
- [ ] Screen recording: record while replaying a recorded flow, physical Android phones (mobilecli id unverified).
- [x] Live log viewer: `adb logcat` / simulator `log stream` with level, text, tag and app filters (Log tab of Device Tools).
- [x] Log viewer upgrades: regular-expression filters (`.*` toggle, invalid pattern in red and ignored), highlight of the message filter in the rows, "Open in Editor" for stack trace lines (project file by name and package path), "Restart app" next to the App filter (terminate, launch, clear first), and `mobilecli device logs` as an experimental source for physical iPhones.
- [ ] Log viewer, next: verify the physical iPhone source on a real device, Swift/Objective-C frames and Swift fatal-error locations in "Open in Editor", pick the clicked frame (not the first) in a multi-line message, verify "Restart app" on an iOS simulator, a Source choice if mobilecli ever proves better, a time or session marker, and a regex guard stronger than the 20,000 character input cap.
- [x] Crash report viewer (mobilecli `device crashes`), in the Crashes tab.
- [ ] Crash viewer, next: scope iOS reports to the selected simulator (needs fetching reports and matching CoreSimulator/<UDID>), show the app name instead of `android.os.Parcel` for Java crashes, ANR reports, physical devices, and an "only this app" filter.
- [ ] Network inspection: show requests of the app (proxy setup helper, HAR export).
- [x] Show the current activity / foreground app and bundle id (mobilecli `apps foreground`), in the Apps tab.
- [x] Foreground app: keep it live in the toolbar or title (now only read on refresh and after an action).
- [ ] Foreground app, next: show the current Android activity in the label, poll faster right after an action, and pause polling while another mobilecli call is running.
- [ ] Open the failing test's trace/screenshot from Allure or a test report next to the live device.
- [x] Pause/freeze the stream to inspect a transient screen (toast, snackbar, loading state): the Freeze toolbar toggle.
- [ ] Freeze, next: a keyboard shortcut, freezing automatically when a given element appears, and blocking a running replay too (it still drives the device while frozen). Not verified on a real device that the hierarchy capture is quick enough to catch a toast.

## 4. Device and app control

- [x] App list with install, uninstall, launch, terminate, clear data (mobilecli `apps`): Apps tab.
- [x] Hide system apps: checkbox in the Apps tab (on by default, remembered); Android via `pm list packages -3`, iOS via the `com.apple.` prefix (Apple apps from the App Store are hidden too). Unit tested only, not checked on a device.
- [ ] App manager, next: version/size columns, check install/uninstall/clear/--locale on Android and a real iPhone (unverified).
- [x] Install an apk / ipa / app by drag and drop onto the Apps tab.
- [ ] Drag and drop install onto the whole tool window (now only the Apps tab), and install a plain .app folder.
- [x] Deep link / URL launcher with history.
- [ ] URL launcher: try a custom app scheme on a real app and a physical device, and handle the iOS "Open in app?" prompt.
- [x] Orientation toggle (mobilecli `device orientation`): toolbar toggle, state re-read after each change.
- [ ] Orientation, next: verify Android gRPC frame rotation, iOS inspector bounds with a normal app in landscape and tap space in landscape (all unverified); Android auto-rotate stays off after use (mobilecli side effect).
- [x] Boot / shutdown / reboot simulators and emulators from the tool window (mobilecli `device boot|shutdown|reboot`): offline devices in the combo, Device Power toolbar menu.
- [ ] Device Power, next: verify `device reboot` (blocking, serial stability, iOS simulator) and Android shutdown/boot of a real user AVD; AVDs without `avd.ini.displayname` and never-booted simulators are not listed by mobilecli (maybe list via `emulator -list-avds` / `simctl` and boot with `emulator -avd`); offline physical devices.
- [x] Permissions per app: Permissions... button and context menu in the Apps tab. Android: runtime permissions read from `dumpsys package`, Grant / Revoke / Reset all via adb. iOS simulator: `simctl privacy` grant / revoke / reset for all, calendar, contacts, contacts-limited, location, location-always, photos, photos-add, media-library, microphone, camera (undocumented), motion, reminders, siri. Notifications have no simctl service.
- [ ] Permissions, next: verify Android revoke of a granted permission (kills the app), the Reset all sequence end to end, POST_NOTIFICATIONS on an app that requests it, Android below 17 and work profiles, and the iOS services location-always, photos-add, media-library, reminders, contacts and all; offer "relaunch app afterwards" on iOS; read the iOS state (TCC.db, fragile) if wanted.
- [x] Dark mode and font size switcher: Display tab (Android via adb, iOS simulators via `simctl ui`), with Reset to defaults.
- [x] Locale, language and time zone switcher to test localisation: Display tab (Android `cmd locale` / `cmd alarm`, iOS `simctl spawn defaults`), with Reset to defaults.
- [ ] Language and time zone, next: verify the controls on live devices, Android older than 17, ar-SA right-to-left layout, `-u-` extension preservation, restoring a multi-language Android list without the Settings UI, an "Set app language" button (`cmd locale set-app-locales`), a SpringBoard restart for iOS, and a per-app iOS time zone (`SIMCTL_CHILD_TZ`, unproven). Also verify the Display tab on physical Android devices and iOS animations (reduce motion is unproven).
- [x] GPS location override: single point from the Location tab (mobilecli `device location set|clear`), presets and saved places.
- [ ] GPS route simulation (waypoints, speed) and a map picker; verify that an Android app really receives the override, whether it survives the mobilecli daemon idle exit, and behaviour on a physical device.
- [x] Network conditions: Network tab with airplane mode, Wi-Fi and mobile data toggles, emulator speed and latency profiles (`adb emu network`) and Reset to normal; iOS simulators show why this is not possible per simulator.
- [ ] Network conditions on physical Android devices and Android older than 11 (airplane mode command differs), a real-traffic throttle check, and iOS via Network Link Conditioner automation.
- [x] Battery: Battery tab on Android (`dumpsys battery set level|status|ac|usb`, reset); iOS simulators show why it is not possible.
- [x] Memory warning: Battery tab section sends `am send-trim-memory <package> <LEVEL>` (HIDDEN to COMPLETE) to the foreground or a typed app; iOS simulators point to Simulator > Debug > Simulate Memory Warning.
- [ ] Battery and memory, next: verify the tabs against a live emulator/device and that apps react (onTrimMemory, low battery); low storage simulation remains.
- [x] Biometrics: Biometrics tab. iOS simulator: Enroll / Unenroll Face ID or Touch ID and matching / non-matching face or finger (`simctl spawn notifyutil`). Android emulator: Touch sensor with a finger id and Lift finger (`adb emu finger`), enrolled count from `dumpsys fingerprint`.
- [ ] Biometrics, next: Android fingerprint enrolment (needs a screen lock plus the Settings UI) remains manual; verify the match / non-match notifications against a live Face ID / Touch ID prompt, Touch ID simulator models, what `dumpsys fingerprint` shows with an enrolled finger, and whether Simulator.app's Features menu enrolment updates the notifyutil state.
- [x] Push notifications: Notifications tab. iOS simulator: APNs payload form or raw JSON sent with `simctl push`. Android: notification posted by the shell package (`cmd notification post`) plus an `am broadcast` intent sender. FCM cannot be sent from adb; the intent group triggers the app's own receiver instead. Last 15 sends are remembered.
- [ ] Notifications, next: verify a banner with notification permission granted (simctl cannot grant it) and delivery while the app is not running; Android per-tag cancel (only cancel-all through binder transaction 1, verified on Android 17 only); bigpicture/media styles, icons and content intents; the panel UI against live devices; physical iPhones.
- [x] Keyboard: special keys (Tab, Esc, Enter, Backspace, Delete, arrows) and Hide keyboard (Android, Escape) in the Input tab; paste through Clipboard "Set and paste".
- [ ] Keyboard: a Show keyboard button, hiding the iOS keyboard, and checking Escape against a full soft keyboard (the tested AVD only shows the hardware-keyboard toolbar).
- [x] Clipboard get/set in the Input tab (mobilecli; warns that emulators share the computer clipboard).
- [ ] Clipboard: a path without mobilecli, avoiding the iOS Allow Paste prompt, and non-text or very long content (unverified).
- [x] Reset app state: "Reset state..." button and context menu item in the Apps tab; a dialog with Terminate, Clear data, Reset permissions and Relaunch (all ticked, last choices remembered), run in that order with a result per step, stopping at the first failure.
- [ ] Reset state, next: verify on a real iPhone (terminate and launch only), an Android app with many runtime permissions (slow, one adb call each), and a simctl reset of a real TCC entry; optionally wait for the app to be in front after the relaunch on Android.

## 5. Input fidelity

- [ ] Multi-touch: pinch and rotate gestures.
- [x] Long press (hold 700 ms), double tap (double click) and drag with hold (Option/Alt + drag); recorded and replayed as `longPress` / `doubleTap`.
- [x] Setting for the long press time (Settings | Tools | Mobile Test Bench, 300 to 3000 ms, default 700); the mirror uses it for the timer and the hold sent to the device. Not tried on a device with a non-default value.
- [ ] Gestures: iOS double tap and hold-drag through mobilecli (no CLI command; needs its JSON-RPC server or WDA), Android double tap without emulator gRPC, and optionally delaying the first tap of a double click.
- [x] Hardware buttons panel in the Input tab (volume, power/lock, home, back, app switch, assistant on Android, shake on iOS simulators).
- [ ] Hardware buttons, next: Siri on iOS (no tool found), a deterministic unlock, iOS volume read-back, and checking everything on physical devices. Record/replay of the Input tab buttons is not wired (DeviceKey is unchanged).
- [x] Swipe/scroll helpers: "scroll until element visible", with the generated code. In the Recording tab, select recorded swipes and convert them (see docs/reference, Recorder).
- [x] Scroll until visible: direction and swipe limit can be chosen (right-click > Set scroll direction and limit...).
- [ ] Scroll until visible: support a scroll inside a container instead of the whole screen, and run the generated `isVisible()` loop in Mobilewright to confirm it works.
- [x] Coordinate overlay and ruler: show tap coordinates, logical vs pixel values (hover line, Grid toggle with crosshair, tap marker, copy menu).
- [ ] Safe area insets overlay (needs a cheap source in the hierarchy; not looked at yet).
- [x] Mouse hover coordinates and element size in the status bar (own hover line under the mirror).
- [ ] Try the hover line, grid and copy menu by hand on both devices (retina frame, rotation).

## 6. Hybrid and web content

- [x] WebView inspector: DOM tree and CSS/XPath locators for web views (mobilecli webview commands). New "WebView" tab, see docs/reference.
- [x] Switch between native and web context and show both trees (the native tree stays in the inspector; the WebView tab names the native element that owns the selected web view by matching bounds).
- [x] Run JavaScript in a web view from the plugin and show the result.
- [ ] WebView, next: load more than 2000 elements (lazy subtrees), pick an element by clicking the mirror, iframes and shadow DOM, an Appium-style hybrid context switch (`NATIVE_APP` / `WEBVIEW_*`), apps that are not debug builds (Chrome DevTools sockets), and verifying XPath counts and the highlight on a device (not done yet).
- [ ] Chrome DevTools / Safari Web Inspector shortcut for the current web view.

## 7. Files and data

- [x] Device file browser: list, download, upload, delete (mobilecli file transfer, `adb`, simctl app containers). New "Files" tab, see docs/reference.
- [x] App data viewer: SharedPreferences, UserDefaults (preview in the Files tab); SQLite is pulled with its WAL files and shown in the file manager (no viewer, not opened in the IDE database tool).
- [ ] Files, next: physical iPhones (mobilecli bundle-id paths), non debuggable Android apps (no root; only `/sdcard/Android/data/<pkg>`), a SQLite viewer or opening a pulled database in the IDE database tool, multiple selection and drag and drop, progress for large transfers, a recursive download of an Android app folder, Android users and work profiles, and verifying a real iOS UserDefaults plist.
- [ ] Keychain / keystore reset.

## 8. Performance and quality

- [x] FPS, CPU and memory graphs for the app under test: new "Performance" tab (Android adb: memory PSS/RSS, CPU, janky frames; iOS simulator: footprint and CPU), see docs/reference.
- [x] App start time measurement (cold/warm) with history: Performance tab (Android `am start -W`, iOS simulator log), last 20 kept in the session.
- [ ] Performance, next: iOS frame rate and jank (needs xctrace/Instruments), Android apps with several processes (sum over all pids), physical devices (recheck the PSS cadence, USER_HZ and gfxinfo there), persisting the start history across IDE restarts, and checking the iOS log timing on other iOS versions and scene based apps.
- [ ] Frame rate of the mirroring itself: scaled gRPC frames, drop frames when the UI is behind (see `performance-guard`).
- [x] Screenshot diff / visual regression: compare the current screen with a baseline, highlight differences: Visual tab (baselines per device in `.mobiletestbench/baselines`, tolerance, anti-aliasing allowance, merged regions, ignore areas).
- [x] Visual tab, more: default baseline name from the foreground app, Update from the current frame in one click, Rename (with collision check).
- [ ] Visual tab, next: a swipe/overlay slider view of the diff, region based ignore picked by dragging on the mirror, a command-line/CI run of the comparison, and checking the overlay on a retina iOS simulator.
- [x] Layout bounds overlay and "show touches" toggle (Android developer options), and animations off/on for test stability: Display tab.
- [x] Pointer location and "Don't keep activities" toggles (Android) in the Display tab, in Reset to defaults.
- [ ] Display tab, next: confirm the show touches dots visually and the layout bounds on a physical device, verify pointer location and "don't keep activities" on a live device (implemented, only unit tested), and an iOS animations control once reduce motion is proven.

## 9. Multi-device and environments

- [ ] Several devices side by side (one WDA port per simulator, one session per device).
- [ ] Mirror an action to all connected devices (smoke check on Android + iOS at once).
- [ ] Cloud devices: connect to Mobile Next cloud / BrowserStack / Sauce Labs devices through mobilecli.
- [x] Remember last device and inspector state per project.
- [ ] Per-project state, next: remember the Device Tools tab filters, the Recording output tab and several devices.
- [ ] Device presets: save a named combination (device, locale, permissions, app build) and apply it in one click.

## 10. IDE integration

- [ ] Run configuration: start the device and app before a test run, collect screenshot and logs after.
- [x] Editor action "Show Locator on Device" (editor context menu, Tools menu, Ctrl+Alt+Shift+M): finds the locator in the selection or on the caret line, evaluates it against a fresh hierarchy of the connected device and highlights the matches (see docs/reference, Locators in the editor).
- [ ] Show on device, next: a real gutter icon or intention on locators in test code, a locator spread over several lines without selecting it, stack-trace navigation (a failed test line jumps to the locator and shows it), and locators held in variables or page object constants (only a literal in the selection/line is read).
- [ ] Navigate from a failing locator in a stack trace to the matching element in the hierarchy.
- [ ] Appium server and WebDriverAgent management: start/stop, status, logs, session list.
- [ ] Appium Inspector parity: capabilities editor, session start from the plugin.
- [ ] Test data: snippets for common flows (login, onboarding) generated from recordings.
- [ ] Settings: per-project overrides for adb path, mobilecli path, locator flavor.

## 11. Robustness and polish of what exists

- [x] Verified physical iOS on an iPhone 13 (iOS 27.0.1): agent install, MJPEG stream (22 fps), hierarchy, tap, swipe, long press, text input, clear; tool window mirror and inspector highlight. Not yet: hardware buttons, pinch, the other Device Tools tabs.
- [x] Verified physical Android (Z Fold8 Ultra, Android 17): mobilecli id equals the adb serial, hierarchy, tap, swipe, long press, text input, clear, tool window mirror (adb screenshots). Fixed: multi-display devices print an adb warning before the PNG. The mirror uses the mobilecli MJPEG stream. Unfolded Fold mirrors the 2256x2504 inner display (adb default screencap, mobilecli and `wm size` agree). The stream restarts when `wm size` changes (fold/unfold, polled every 2 s; unit tested and confirmed live on the Fold). Not yet: the other Device Tools tabs.
- [ ] Android 17 emulator: report that `uiautomator dump` is killed (exit 137) and track a proper fix instead of the fallback.
- [x] Reconnect automatically when a device or emulator restarts.
- [ ] Reconnect automatically, next: verify in the live IDE (emulator kill and restart, simulator shutdown, Device Power reboot, adb kill-server); a cooldown for a device that flaps; a checkbox state change should apply to a wait in progress.
- [x] Show the transport and why a fallback happened (gRPC unavailable, MJPEG ended, WDA missing) in the status bar tooltip.
- [ ] Transport tooltip, next: check the wording against live fallbacks (emulator without gRPC, mobilecli stream ending, simulator without WDA).
- [x] Clean-machine install test (2026-10-07, zip in a fresh IDE with a fake HOME, minimal PATH, no mobilecli): the plugin loads without errors; no mobilecli gives an empty iPhone list, 1 fps Android mirror and an Android 17 inspector error that now names mobilecli; a wrong adb path now says adb was not found. The mobilecli-required messages are covered by `DeviceServicesWithoutMobilecliTest` (generic call, screen recording, physical iPhone session, simulator files, log source, Input clipboard); not run by hand in the IDE.
- [x] GitHub Actions workflow (`.github/workflows/ci.yml`): `test buildPlugin` on push and pull request, `verifyPlugin` on tags and on request. Checked locally with a from-scratch build (no build cache, 734 tests) and a YAML parse; the workflow itself has not run on GitHub yet.
- [ ] Integration tests against a real emulator/simulator in CI.
- [x] Make `gradlew` executable in the repo (`./gradlew` and `sh gradlew` both work).
- [x] Align `protobufVersion` with grpc 1.84: now 3.25.9, reasons in `gradle.properties`.
- [ ] Run the emulator gRPC stream and input against Pixel_9 with the 3.25.9 build (could not run here, only compiled and unit-tested).
- [x] Marketplace readiness: description, change notes (CHANGELOG.md), dark icon, verifyPlugin config, signing/publishing from env vars. Nothing signed or published.
- [x] LICENSE (Apache 2.0) and NOTICE added and packaged in the plugin zip and jar.
- [x] Replaced the two deprecated `FileSaverDescriptor(String, String, String...)` calls (LogPanel.save, CrashesPanel.save) with `FileSaverDescriptor(title, description).apply { withExtensionFilter(label, ...) }`, which keeps the extension filter.
- [x] `./gradlew verifyPlugin` on the 1.0.0 zip (2026-10-07): Compatible with IU-253, 261, 262 and 263, 14 deprecated usages (generated protobuf code), no errors.
