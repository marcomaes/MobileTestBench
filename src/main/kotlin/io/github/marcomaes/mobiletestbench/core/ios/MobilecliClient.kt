package io.github.marcomaes.mobiletestbench.core.ios

import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.MobilecliException
import io.github.marcomaes.mobiletestbench.core.MobilecliJson
import io.github.marcomaes.mobiletestbench.core.android.AdbClient
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import java.time.Duration

/**
 * Thin wrapper around the `mobilecli` CLI (https://github.com/mobile-next/mobilecli).
 * Used for iOS devices and simulators, and as Android hierarchy fallback. Every call is one process; failures surface as
 * [io.github.marcomaes.mobiletestbench.core.exec.CommandFailedException] (mobilecli exits 1 and prints the reason on stderr).
 * Coordinates are iOS points, the same space `dump ui` reports.
 */
class MobilecliClient(private val executable: String, private val runner: CommandRunner) {

    fun realIosDevicesJson(): String =
        run(listOf("devices", "--platform", "ios", "--type", "real")).text

    /** Also lists stopped AVDs and shut-down simulators (state "offline"). */
    fun devicesIncludingOfflineJson(): String = run(listOf("devices", "--include-offline")).text

    fun deviceInfoJson(id: String): String = run(listOf("device", "info", "--device", id)).text

    fun screenshotPng(id: String): ByteArray =
        run(listOf("screenshot", "--device", id, "-o", "-"), Duration.ofSeconds(15)).stdout

    fun dumpUiJson(id: String): String = run(listOf("dump", "ui", "--device", id), Duration.ofSeconds(60)).text

    /** Android: the agent's unprocessed tree (class, text, content-desc, resource-id, rect, ...). */
    fun dumpUiRawJson(id: String): String =
        run(listOf("dump", "ui", "--device", id, "--format", "raw"), Duration.ofSeconds(60)).text

    fun tap(id: String, x: Int, y: Int) {
        run(listOf("io", "tap", "--device", id, "$x,$y"))
    }

    fun swipe(id: String, fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) {
        run(listOf("io", "swipe", "--device", id, "--duration", durationMs.toString(), "$fromX,$fromY,$toX,$toY"))
    }

    /** Holds for [durationMs], then returns. There is no mobilecli command for double tap or gestures. */
    fun longPress(id: String, x: Int, y: Int, durationMs: Long) {
        run(listOf("io", "longpress", "--device", id, "--duration", durationMs.toString(), "$x,$y"), Duration.ofMillis(durationMs) + Duration.ofSeconds(20))
    }

    /**
     * Presses a hardware button. The name must be UPPER CASE on both platforms (mobilecli 1.0.17 rejects lower case although its help
     * says otherwise). Android accepts HOME, BACK, APP_SWITCH, POWER, VOLUME_UP, VOLUME_DOWN, ENTER; iOS simulators accept HOME,
     * VOLUME_UP, VOLUME_DOWN, LOCK (a toggle; there is no POWER). Throws [MobilecliException] with mobilecli's text for an unsupported name.
     */
    fun button(id: String, name: String) {
        keyCall(listOf("io", "button", "--device", id, name))
    }

    /** The clipboard text (empty when it is empty). Works on Android and iOS. */
    fun clipboardGet(id: String): String =
        MobilecliJson.data(call(id, listOf("io", "clipboard", "get")))?.takeIf { it.isJsonObject }?.asJsonObject?.get("text")
            ?.takeIf { it.isJsonPrimitive }?.asString ?: ""

    /** Sets the clipboard ("" clears it). The text travels as one argument, so no shell quoting applies. */
    fun clipboardSet(id: String, text: String) {
        // Text that starts with "-" would be read as a flag; "--" ends the flags (the same form `io text` uses).
        val args = if (text.startsWith("-")) listOf("io", "clipboard", "set", "--device", id, "--", text) else listOf("io", "clipboard", "set", "--device", id, text)
        val result = runner.run(listOf(executable) + args, Duration.ofSeconds(20))
        if (!result.ok || MobilecliJson.isError(result.text)) throw MobilecliException(MobilecliJson.errorMessage(result.text, result.stderr))
    }

    /** key: a mobilecli key combo such as "enter" or "backspace". */
    fun key(id: String, key: String) {
        keyCall(listOf("io", "keys", "--device", id, key))
    }

    /** Several combos in one call; each is its own argument. A bad name fails the whole call before anything is pressed. */
    fun keys(id: String, keys: List<String>) {
        keyCall(listOf("io", "keys", "--device", id) + keys)
    }

    /** `io keys` and `io button` report a bad name as a JSON error envelope on stdout (exit 1, empty stderr): surface that text. */
    private fun keyCall(args: List<String>) {
        val result = runner.run(listOf(executable) + args, Duration.ofSeconds(20))
        if (!result.ok || MobilecliJson.isError(result.text)) throw MobilecliException(MobilecliJson.errorMessage(result.text, result.stderr))
    }

    fun text(id: String, text: String) {
        run(listOf("io", "text", "--device", id, "--", text))
    }

    /** Command line of a screen recording to an MP4 file (long running; stop it by ending the process). */
    fun screenRecordCommand(id: String, output: String): List<String> =
        listOf(executable, "screenrecord", "--device", id, "-o", output, "--silent")

    /** Command line of the live device log (JSON lines, long running). No `--filter`: its filters are exact equality and hang when nothing matches. */
    fun logsCommand(id: String): List<String> = listOf(executable, "device", "logs", "--device", id)

    /** Command line of the MJPEG screen stream (long running, started by the caller). */
    fun screenStreamCommand(id: String): List<String> =
        listOf(executable, "screencapture", "--device", id, "-f", "mjpeg")

    /** Starts the MJPEG screen stream process; the caller reads its stdout and ends it with `destroy`. */
    fun startScreenStream(id: String): Process = runner.start(screenStreamCommand(id))

    /**
     * Generic call for features: runs `mobilecli <args> --device <id>` and returns stdout (usually the JSON envelope).
     * Throws [MobilecliException] with mobilecli's own error text when it fails (error envelope, non zero exit, or an error envelope with exit 0).
     */
    fun call(id: String, args: List<String>, timeout: Duration = Duration.ofSeconds(20)): String {
        val result = runner.run(MobilecliJson.commandLine(executable, id, args), timeout)
        val stdout = result.text
        if (!result.ok || MobilecliJson.isError(stdout)) throw MobilecliException(MobilecliJson.errorMessage(stdout, result.stderr))
        return stdout
    }

    private fun run(args: List<String>, timeout: Duration = Duration.ofSeconds(20)) =
        runner.runChecked(listOf(executable) + args, timeout)

    companion object {
        /** mobilecli names an Android emulator by its AVD name, not by the adb serial; everything else by the device id. */
        fun idFor(device: Device, adb: AdbClient): String =
            if (device.id.startsWith("emulator-")) adb.avdName(device.id) ?: device.id else device.id
    }
}
