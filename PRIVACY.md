# Privacy

Last updated: 2026-10-14

Mobile Test Bench is a JetBrains IDE plugin. It does not collect, store on a server or transmit personal data, usage statistics, crash reports or telemetry. The author receives no data from your use of the plugin.

## What the plugin does with your data

- **Devices.** The plugin talks to your Android emulators and devices, and your iOS simulators and iPhones, through the command line tools `adb`, `xcrun` (`simctl`) and `mobilecli`. Screenshots, the UI hierarchy, logs, files and other data from a device stay on your machine and are shown in the IDE.
- **Local connections.** The plugin connects to the gRPC port of a local Android emulator. It connects to a WebDriverAgent URL only when you configure one in the settings. That URL is normally on your own machine or network.
- **Settings and project state.** Your settings, such as saved places, notification payload history, baseline screenshots and the last used device, are stored locally by the IDE in its own configuration and workspace files. The plugin does not send them anywhere.

## Third-party tools

`adb`, Xcode (`xcrun`, `simctl`) and `mobilecli` are separate tools with their own behaviour and privacy terms. The plugin only runs them on your machine. Apps you inspect or test may send data of their own. The plugin does not control that.

## Changes

If this changes, for example if the plugin ever adds telemetry, this page will be updated and the change will be listed in the [changelog](CHANGELOG.md). Any data collection would need your explicit permission first.

## Contact

Questions: [github.com/marcomaes/MobileTestBench/issues](https://github.com/marcomaes/MobileTestBench/issues).
