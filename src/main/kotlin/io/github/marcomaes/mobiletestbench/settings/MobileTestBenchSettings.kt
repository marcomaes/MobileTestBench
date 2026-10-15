package io.github.marcomaes.mobiletestbench.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "MobileTestBenchSettings", storages = [Storage("mobiletestbench.xml")])
class MobileTestBenchSettings : SimplePersistentStateComponent<MobileTestBenchSettings.SettingsState>(SettingsState()) {

    class SettingsState : BaseState() {
        /** Empty means auto detect. */
        var adbPath by string("")
        var useEmulatorGrpc by property(true)
        var screenshotIntervalMs by property(250)

        var wdaUrl by string("http://localhost:8100")
        /** WebDriverAgent MJPEG server. Empty means use simctl screenshots. */
        var mjpegUrl by string("http://localhost:9100")

        // The last choices in the Generate Page Object dialog.
        var pageObjectLanguage by string("MOBILEWRIGHT_TS")
        var pageObjectLocatorStyle by string("BASE_CLASS_FIELDS")
        var pageObjectBaseClass by string("BasePage")
        var pageObjectGenerateBaseClass by property(true)
        var pageObjectSortByName by property(false)

        /** What locators of the Locators tab are copied as (a `LocatorLanguage` name). */
        var locatorLanguage by string("AS_SHOWN")

        /** Copy locators as a whole line (`driver.findElement(...)`) instead of the bare locator. */
        var locatorWholeLine by property(false)

        /** The last text of the inspector's element filter field. */
        var elementFilter by string("")

        /** Accessibility tab: estimate low contrast text from the screen image. */
        var contrastHints by property(true)

        /** What the Recording tab writes the steps as (a `RecorderOutput` name). */
        var recorderOutput by string("MOBILEWRIGHT")

        /** Replay: how long an element, wait or assertion without its own timeout may take (seconds, 1..300) and the pause between steps (ms, 0..10000). */
        var replayTimeoutSeconds by property(10)
        var replayDelayMs by property(0)

        /** Screen recording: stop automatically after this many seconds (0 = no limit, max 3600). */
        var screenRecordingLimitSeconds by property(0)

        /** Mirror: how long the mouse is held for a long press (ms, 300..3000). */
        var longPressMs by property(700)

        /** Save as PNG also writes the UI hierarchy as XML next to the image (same base name). */
        var saveHierarchyWithScreenshot by property(false)

        /** URL launcher history, newest first (at most 20). */
        var urlHistory by list<String>()

        /** Apps tab: hide system apps (Android: not in `pm list packages -3`, iOS: `com.apple.` ids). */
        var hideSystemApps by property(true)

        /** The last choices in the Apps tab "Reset state" dialog. */
        var resetTerminate by property(true)
        var resetClear by property(true)
        var resetPermissions by property(true)
        var resetRelaunch by property(true)

        /** Reconnect by itself when the connected device goes away and comes back (restart, reboot, adb offline). */
        var reconnectAutomatically by property(true)

        /** When the panel opens, connect to the device this project used last (if it is running). Off by default. */
        var reconnectToLastDevice by property(false)

        /** Places saved in the Location tab, each as `name|lat|lon`. */
        var savedPlaces by list<String>()

        /** The last folder chosen for a download in the Files tab. */
        var filesDownloadFolder by string("")

        /** Performance tab: chart window in seconds, runs per start measurement and the last kind (a `StartKind` name). */
        var perfWindowSeconds by property(60)
        var perfStartRuns by property(5)
        var perfStartKind by string("COLD")

        /** Visual tab: per-channel tolerance (0..64) of the comparison. */
        var visualTolerance by property(8)

        /** The Grid toolbar toggle of the mirror. */
        var showGrid by property(false)

        /** Notifications tab: the last 15 sends, newest first, one JSON string each (`PayloadHistory`). */
        var notificationHistory by list<String>()
    }

    companion object {
        fun getInstance(): MobileTestBenchSettings = service()
    }
}
