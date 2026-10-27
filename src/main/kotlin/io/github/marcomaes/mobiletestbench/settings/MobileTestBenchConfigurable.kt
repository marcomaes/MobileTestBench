package io.github.marcomaes.mobiletestbench.settings

import io.github.marcomaes.mobiletestbench.core.input.Gestures
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import io.github.marcomaes.mobiletestbench.core.recording.ReplayOptions
import io.github.marcomaes.mobiletestbench.core.recording.ScreenRecordingLimit

class MobileTestBenchConfigurable : BoundConfigurable("Mobile Test Bench") {

    private val state get() = MobileTestBenchSettings.getInstance().state

    override fun createPanel(): DialogPanel = panel {
        group("Connection") {
            row {
                checkBox("Reconnect automatically")
                    .bindSelected({ state.reconnectAutomatically }, { state.reconnectAutomatically = it })
                    .comment("When the connected device restarts or disappears, wait for it and connect again by itself. Disconnect cancels the wait.")
            }
            row {
                checkBox("Reconnect to the last device when the project opens")
                    .bindSelected({ state.reconnectToLastDevice }, { state.reconnectToLastDevice = it })
                    .comment("The last device is always pre-selected when it is running. This also connects to it. Off by default.")
            }
        }
        group("Locators") {
            row {
                checkBox("Use double quotes in Mobilewright locators")
                    .bindSelected({ state.useDoubleQuotes }, { state.useDoubleQuotes = it })
                    .comment("Locators and recorded steps are written as getByTestId(\"x\") instead of getByTestId('x'). Applies to newly shown locators. Default off.")
            }
        }
        group("Gestures") {
            row("Long press time (ms):") {
                intTextField(Gestures.LONG_PRESS_RANGE)
                    .bindIntText({ state.longPressMs }, { state.longPressMs = it })
                    .comment("How long the mouse is held still on the mirror for a long press (300 to 3000). Default 700.")
            }
        }
        group("Screen recording") {
            row("Time limit (seconds):") {
                intTextField(ScreenRecordingLimit.SECONDS)
                    .bindIntText({ state.screenRecordingLimitSeconds }, { state.screenRecordingLimitSeconds = it })
                    .comment("The Record Screen to Video button stops and saves the video by itself after this long (0 = no limit, up to 3600).")
            }
        }
        group("Replay") {
            row("Replay element timeout (seconds):") {
                intTextField(ReplayOptions.TIMEOUT_SECONDS)
                    .bindIntText({ state.replayTimeoutSeconds }, { state.replayTimeoutSeconds = it })
                    .comment("How long a replayed step waits for its element, wait or assertion when the step has no timeout of its own (1 to 300).")
            }
            row("Delay between steps (ms):") {
                intTextField(ReplayOptions.DELAY_MS)
                    .bindIntText({ state.replayDelayMs }, { state.replayDelayMs = it })
                    .comment("Slow motion: pause between two replayed steps (0 to 10000).")
            }
        }
        group("Android") {
            row("adb path:") {
                textField()
                    .bindText({ state.adbPath ?: "" }, { state.adbPath = it })
                    .align(AlignX.FILL)
                    .comment("Leave empty to detect it from ANDROID_HOME, ANDROID_SDK_ROOT or the default SDK location.")
            }
            row {
                checkBox("Use the emulator gRPC API for emulators (live stream, fast input)")
                    .bindSelected({ state.useEmulatorGrpc }, { state.useEmulatorGrpc = it })
            }
            row("Screenshot interval (ms):") {
                intTextField(50..5000)
                    .bindIntText({ state.screenshotIntervalMs }, { state.screenshotIntervalMs = it })
                    .comment("Used for physical devices and as a fallback when gRPC is not available.")
            }
        }
        group("iOS Simulator (WebDriverAgent)") {
            row("WDA URL:") {
                textField()
                    .bindText({ state.wdaUrl ?: "" }, { state.wdaUrl = it })
                    .align(AlignX.FILL)
                    .comment("WebDriverAgent must be running on the simulator for input and inspection.")
            }
            row("MJPEG URL:") {
                textField()
                    .bindText({ state.mjpegUrl ?: "" }, { state.mjpegUrl = it })
                    .align(AlignX.FILL)
                    .comment("WDA's MJPEG server. Leave empty to use simctl screenshots instead.")
            }
        }
    }
}
