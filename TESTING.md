# Testing Mobile Test Bench

Thank you for trying it. This is an early version (0.1.0): it was built against one Android emulator and one iOS simulator, and a lot of the interface has never been used by anyone but its author. Bugs are expected, and a clear bug report is the most useful thing you can send back.

## What it is

An IntelliJ-platform plugin that mirrors Android emulators, Android devices and iOS simulators in a tool window, lets you control them with the mouse and keyboard, inspects the screen (element tree, Mobilewright and Appium locators), records and replays interactions, and adds a set of device tools (logs, apps, files, network, location and more).

## What you need

* An IDE of build **253 or newer**: IntelliJ IDEA 2025.3 or later, or another IntelliJ-based IDE on the same platform. Older builds cannot install it.
* **mobilecli** on the PATH: `npm install -g mobilecli`. Most Device Tools features and all iOS input need it. Check with `mobilecli devices`.
* For Android: the Android SDK with `adb`. The plugin looks at the adb path setting, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `~/Library/Android/sdk` and the PATH.
* For iOS simulators: a Mac with Xcode and the command-line tools (`xcrun simctl`).
* A running emulator or booted simulator. A physical device is not supported yet, and was never tried.

## Install

1. Get `MobileTestBench-0.1.0.zip` from whoever sent it to you. Do not unzip it.
2. In the IDE: **Settings | Plugins**, click the gear icon, **Install Plugin from Disk...**, pick the zip.
3. Accept the restart. The IDE may show a one-time note about third-party plugins; the plugin is not signed yet.
4. Settings that you may want are under **Settings | Tools | Mobile Test Bench** (adb path, replay timeouts, reconnect).

To remove it again: Settings | Plugins | Installed, Mobile Test Bench, Uninstall.

## First steps

1. Start an emulator or boot a simulator.
2. Open the **Mobile Devices** tool window (right edge of the IDE).
3. Pick the device in the drop-down and press the green **Connect** button. The status line below the screen shows the device, the transport (for an Android emulator `emulator gRPC` is the fast one) and the frame rate.
4. Click, drag and scroll on the screen; type while the screen has focus. The toolbar has Back, Home, Recent Apps and more. Hover over a toolbar icon to see its name.
5. The **console icon** in the toolbar opens **Device Tools** below the screen.

## Things worth trying

Do as many as you like, in any order. If something is unclear or looks broken, that is a finding too.

**Mirror and input**
* Click, double-click and hold the mouse still for about a second on the screen. Scroll with the wheel. Type text.
* Rotate with the **Landscape** toggle and check that clicks still land where you point.
* **Freeze** pauses the picture; **Grid** shows a coordinate grid; right-click the screen to copy coordinates.
* **Screenshot** and **Record Screen to Video**.

**Inspector** (the target icon, "Inspect Elements")
* Click an element on the screen, then look at its locators in the **Locators** tab. Type a locator into the field above the table to see which elements it matches.
* **Copy as** changes the language of the locator you copy. Right-click an element in the tree for more.
* The **Accessibility** tab lists issues; the **Properties** tab shows all attributes.
* Open a test file in the IDE, put the caret on a locator line and use **Show Locator on Device** (Ctrl+Alt+Shift+M, also in the editor context menu).

**Recorder**
* Press **Record**, tap and type in an app, press **Assert** and click an element to add an assertion. Look at the **Recording** tab.
* Change the **Output** language (Mobilewright or Appium). Press **Replay**, **Replay from Step** and try **Set timeout...** on a wait.
* **Generate Page Object...** (class icon) writes a page object for the current screen.

**Device Tools tabs**: Log, Apps, Open URL, Crashes, Location, Display, Input, Network, Biometrics, WebView, Files, Performance, Visual, Notifications. Pick the ones that matter for your work. In the Log tab try the level, the `.*` regular-expression toggle and the App filter (an Android package name or an iOS process name). The **Apps** tab can launch, terminate, clear and uninstall apps, so be careful with data you care about.

**Also**
* **Device Power** (boot, shut down, reboot) and the automatic reconnect when a device restarts.
* Close and reopen the project: the last device and the panel layout should come back.

## What is known to be unverified

* Nothing has been tried on a physical iPhone or Android phone.
* Most of the newest screens (the Device Tools tabs and the recorder additions) were checked by unit tests and code review only, not clicked through by hand.
* The generated Appium and Mobilewright code was checked against documentation, never compiled or run against a real Appium server or Mobilewright.
* Landscape, the contrast hints and Android clearing of text fields in custom widgets (React Native, Flutter, Compose) are weakly tested.

## How to report a problem

Please send:

1. What you did, what you expected and what happened. A screenshot of the IDE helps.
2. Your setup: IDE name and version, macOS version, Android emulator image or simulator model and OS version, `mobilecli --version`.
3. The **IDE log**: **Help | Show Log in Finder** opens the folder with `idea.log` (on a Mac it is in `~/Library/Logs/JetBrains/<IDE and version>/`). Send the lines around the time of the problem, or the whole file if you are not sure. If the Mobile Devices tool window does not open at all, the log shows why: search it for `mobiletestbench`.
4. If a tab shows an error message, copy it exactly.

The plugin talks to local tools and devices only (adb, simctl, mobilecli and the emulator's local gRPC port) and does not send anything anywhere else as far as the code goes. The log and any screenshots stay on your machine until you choose to send them.
