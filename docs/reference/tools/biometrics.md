# Biometrics

Simulates Face ID, Touch ID and fingerprint sensors. Use it to test login and authentication flows without a real face or finger.

| | |
|---|---|
| **Works on** | iOS simulator and Android emulator. Physical devices and iPhones are disabled with an explanation. |
| **Needs** | `xcrun simctl` for iOS simulators, `adb` for Android emulators. |

## Use it

1. Open the **Biometrics** tab.
2. On an iOS simulator, click **Enroll**, then **Matching face** or **Non-matching face**. Use **Unenroll** to remove the enrolment.
3. On a Touch ID model, the same buttons read finger instead of face.
4. On an Android emulator, pick a finger preset or id (1 to 10), then click **Touch sensor** and **Lift finger**.
5. Use an id that is not enrolled (preset "Unknown finger 99") to make a scan fail.

## Good to know

* Face ID or Touch ID is guessed from the model name, because simctl does not report it.
* The plugin cannot enrol a fingerprint on Android. Set a screen lock and add a fingerprint in Settings, then press Touch sensor for each scan step.
* OK from the Android console does not mean authentication succeeded, and "Posted" on iOS only means the notification was sent.
* The iOS state line only shows what this tab or an earlier `notifyutil` call set, not enrolment done in Simulator.app.
* The Android enrolled count and accept / reject counters come from `dumpsys fingerprint`; **Refresh** reads again.
* Not verified: match and non-match against a live LocalAuthentication prompt, and Touch ID models.

## Technical notes and what is not verified

<details>
<summary>Full reference text</summary>

The **Biometrics** tab of Device Tools simulates Face ID, Touch ID and fingerprint sensors. **iOS simulator**: **Enroll** and **Unenroll** run `xcrun simctl spawn <udid> notifyutil -s com.apple.BiometricKit.enrollmentChanged 1|0` followed by `-p` of the same name (two processes); **Matching face** and **Non-matching face** post `com.apple.BiometricKit_Sim.pearl.match` / `.nomatch`. On Touch ID models the buttons read finger and post `com.apple.BiometricKit_Sim.fingerTouch.match` / `.nomatch`. Face ID or Touch ID is guessed from the model name (iPhone SE, iPhone 8 and older, iPads other than the Face ID Pros count as Touch ID; Face ID iPads are the iPad Pros except 9.7-inch, 10.5-inch and 12.9-inch 1st/2nd generation), because simctl does not report it. The state line is read with `notifyutil -g` and only shows what this tab or an earlier notifyutil call set, not enrolment done in Simulator.app. **Android emulator** (serial `emulator-NNNN`): a finger preset or id (1 to 10) plus **Touch sensor** (`adb emu finger touch <id>`) and **Lift finger** (`adb emu finger remove`); the enrolled count and accept / reject counters come from `dumpsys fingerprint` (Refresh reads again). Use an id that is not enrolled (preset "Unknown finger 99") to fail. adb exits 0 for the console, so the reply line (OK or `KO: reason`) is parsed; each action shows the exact result. Physical devices and iOS physical iPhones are disabled with an explanation. **What the plugin cannot do**: it cannot enrol a fingerprint on Android (there is no shell command; set a screen lock in Settings > Security, then Settings > Security > Fingerprint, and press Touch sensor for each scan step), and the console replies OK even when nothing is enrolled, so OK does not mean authentication succeeded. For the same reason notifyutil exits 0 for any name: "Posted" only means the notification was sent. Verified on a Pixel 9 emulator (Android 17) and an iPhone 17 Pro simulator (iOS 26.5): the enrol / unenrol effect (in the simulator log, Face ID went from not enrolled to usable and back), the console replies and the `dumpsys fingerprint` output with nothing enrolled. Not verified: the effect of match / non-match with a live LocalAuthentication prompt, Touch ID models and the fingerTouch notifications, an enrolled Android fingerprint and what the counters then show, and the panel UI against live devices.

</details>

[All Device Tools tabs](../device-tools.md) · [Feature guide](../../FEATURES.md)
