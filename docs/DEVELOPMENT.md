# Development

Build, test, release, architecture and known limitations. Back to the [README](../README.md).

## Install, build and release

### Install from disk

Build the zip with `./gradlew buildPlugin` (result: `build/distributions/MobileTestBench-1.0.0.zip`, about 11.7 MB, with `LICENSE` and `NOTICE` inside). In the IDE open Settings, Plugins, the gear icon, **Install Plugin from Disk...** and pick the zip. You need `mobilecli` on the PATH, `adb` for Android and Xcode for iOS simulators.

To let someone else try it, send them the zip together with `TESTING.md` (requirements, install steps, things to try, what is unverified and how to report a problem).

### Build and verify

* `./gradlew test` runs all tests.
* `./gradlew buildPlugin` builds the zip. The change notes come from `CHANGELOG.md` (section of the current version), the description from `plugin.xml`, since-build 253 with no until-build. The build fails with an error if CHANGELOG.md has no section (or no bullets) for the current version, so the Marketplace never gets empty change notes.
* `./gradlew verifyPlugin` runs the IntelliJ Plugin Verifier against the recommended IDEs (`pluginVerification { ides { recommended() } }`). It downloads several IDEs and takes about 5 to 6 minutes. Last run (IU 253, 261, 262, 263): compatible everywhere, 16 deprecated-API usages (14 are overrides in the generated emulator protobuf code, 2 were the varargs `FileSaverDescriptor` constructor in the log and crash save dialogs, since replaced by `FileSaverDescriptor(title, description).withExtensionFilter(...)`, not yet re-verified), no experimental, internal or scheduled-for-removal usages.
* `META-INF/pluginIcon.svg` (40x40) and `pluginIcon_dark.svg` are the Marketplace icons.

### Continuous integration

`.github/workflows/ci.yml` runs on every push to `main`, every pull request and on request (Actions tab, *Run workflow*):

* **Test and build** (Ubuntu, JDK 21): `./gradlew test buildPlugin`, which includes the platform tests (the tool window smoke test). The plugin zip is uploaded as an artifact, and the HTML test report when something fails.
* **Plugin Verifier** (`./gradlew verifyPlugin`): only for tags starting with `v` and on request, because it downloads about 3 GB of IDEs.

CI does not sign or publish anything: signing and publishing stay a manual step with the owner's environment variables.

### Recording the README GIF

`scripts/make-gif.sh` turns a screen recording into a small GIF (needs `ffmpeg`).

1. Boot the Android emulator, start `./gradlew runIde`, connect the device and open the **Recording** tab. Quit other apps and notifications so they do not show up.
2. Start the macOS recorder with `Cmd+Shift+5`, choose *Record Selected Portion* and select the IDE window.
3. About 20 seconds: press **Clear**, press **Record** (the red dot), tap a field on the mirror, type, tap a button, press **Assert** and click an element, press **Record** again to stop, then press **Replay**.
4. Stop the recording, then run:

   ```bash
   scripts/make-gif.sh ~/Desktop/recording.mov docs/screenshots/recorder.gif
   ```

   The script warns when the GIF is larger than 8 MB. Pass a smaller width and frame rate (`... 900 10`) or record a shorter clip.
5. Reference it from the README with `![Recording and replaying a test](docs/screenshots/recorder.gif)`.

### Sign and publish

Signing and publishing read environment variables only; no value is stored in any file.

Generate the key and certificate chain once, in a folder outside the repository (see the JetBrains [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html) guide). JetBrains Marketplace has no place to register the certificate yet, so there is nothing to upload: it re-signs the plugin with its own certificate. Keep the key and its passphrase in a password manager and never commit them.

```bash
openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
openssl rsa -in private_encrypted.pem -out private.pem
openssl req -key private.pem -new -x509 -days 3650 -out chain.crt
```

The first command asks for the passphrase (`PRIVATE_KEY_PASSWORD`). The second writes an unencrypted copy: delete `private.pem` after the third command and sign with `private_encrypted.pem`.

```bash
export CERTIFICATE_CHAIN="$(cat chain.crt)"
export PRIVATE_KEY="$(cat private_encrypted.pem)"
export PRIVATE_KEY_PASSWORD='...'
./gradlew signPlugin
export PUBLISH_TOKEN='...'
./gradlew publishPlugin
```

Without the variables `signPlugin` is skipped and `publishPlugin` fails before uploading anything. **Nothing has been signed or published.** The behaviour with real credentials is unverified.

### License

Mobile Test Bench is licensed under the **Apache License, Version 2.0**: see `LICENSE` (the unmodified official text) and `NOTICE` (copyright, the AOSP emulator protocol file and the bundled third-party libraries with their licenses). The copyright line in `NOTICE` names Marco Maes; change it if someone else (for example an employer) holds the rights. `LICENSE` and `NOTICE` are packaged with the plugin: `MobileTestBench/LICENSE` and `MobileTestBench/NOTICE` in the distribution zip, and `META-INF/LICENSE` and `META-INF/NOTICE` inside the plugin jar (`build.gradle.kts`, tasks `prepareSandbox` and `jar`). JetBrains Marketplace asks for the license when you upload.

### Protobuf version

`protobufVersion` is 3.25.9, matching what grpc-java 1.84 declares (see the comments in `gradle.properties`). This is covered by compilation and the unit tests only; the emulator gRPC stream and input path was not run again with it.

## Architecture

```
core/                      IDE independent, unit tested
  Model.kt                 Device, Size, Rect, DeviceKey
  ScreenGeometry.kt        panel <-> device coordinate mapping (letterboxing, retina)
  exec/                    process runner, adb/xcrun lookup
  discovery/               adb devices -l, simctl JSON and mobilecli devices parsers
  power/                   Device Power: boot/shutdown/reboot actions, offline device parser and merge, polling
  session/DeviceSession    common interface: frames, input, hierarchy
  android/                 AdbClient, AndroidSession, emulator discovery files
  ios/                     SimctlClient, WdaClient, MobilecliClient, MJPEG reader, IosSimulatorSession, IosMobilecliSession
  inspector/               UiNode, uiautomator, WDA and mobilecli dump parsers, locator generation, tree filter query
  input/                   Gestures (mouse rules), Input (hardware buttons, keys, clipboard)
  recording/               ScriptRecorder, ScriptPlayer, assertions, scroll until visible
  freeze/ reconnect/       FreezeState, ReconnectWatcher
  apps/ permissions/ crashes/ location/ display/ network/ battery/ biometrics/ webview/ files/ perf/ visual/ notifications/   logic of the Device Tools tabs
android/GrpcEmulatorLink   emulator gRPC (generated from src/main/proto/emulator_controller.proto)
settings/                  persistent settings + Settings page
ui/                        tool window, ScreenView (rendering + input), InspectorPanel
```

Coordinates: input and hierarchy bounds use a "logical" space per platform (pixels on Android, points on iOS). Frames can have a different resolution, so the view maps through normalized coordinates (`ScreenGeometry`).

`emulator_controller.proto` comes from AOSP (`platform/external/qemu`, Apache 2.0).

## Status and known limitations

* **Tested so far:** the `core` package has unit tests (parsers, locators, locator query, accessibility rules, sessions with fake processes). The plugin has been run in a sandbox IDE (`./gradlew runIde`) against an Android emulator (gRPC) and an iOS simulator (mobilecli). Also run against a physical iPhone 13 (iOS 27) and a Samsung Z Fold8 Ultra (Android 17): mirror, tap, swipe, long press, text input and the inspector, and the Fold's display switch when folding.
* **gRPC frames:** the emulator sends uncompressed RGB888 frames at native resolution. Requesting a scaled `ImageFormat` would reduce CPU use.
* **WDA URL (fallback only, used when mobilecli is missing):** there is one WDA URL for all simulators. Running several simulators at once needs one WDA port per device.
* **mobilecli** was checked at 1.0.14 (help output) and again at 1.0.17 against an Android emulator and an iOS simulator: devices, device info, UI dumps, screenshots, tap, swipe, text, keys, buttons and the MJPEG stream. **Physical devices** (an iPhone 13 on iOS 27 with the agent installed, and a Z Fold8 Ultra on Android 17) were checked for discovery, the MJPEG stream, tap, swipe, long press, text input and the hierarchy.
* **Multi-touch, rotation buttons and multiple displays** are not implemented.

## Ideas for next steps

* Scaled gRPC frames and frame dropping when the UI is slow.
* Insert the locator in the language of the test (Java, Kotlin, Python, ...) instead of as shown.
