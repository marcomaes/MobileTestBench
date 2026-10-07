package io.github.marcomaes.mobiletestbench.core.android

import io.github.marcomaes.mobiletestbench.core.Size
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import java.time.Duration

class AdbClient(private val adb: String, private val runner: CommandRunner) {

    /** The adb program this client runs (a path or just `adb`). */
    val executable: String get() = adb

    /** Command line of the device log: the last 300 lines, then following (long running; end it by ending the process). */
    fun logcatCommand(serial: String): List<String> = listOf(adb, "-s", serial, "logcat", "-v", "threadtime", "-T", "300")

    /** The process ids of an app by package name (`pidof`); empty when it is not running. */
    fun pidsOf(serial: String, packageName: String): Set<String> {
        if (!packageName.matches(Regex("[A-Za-z0-9._]+"))) return emptySet()
        val result = runner.run(listOf(adb, "-s", serial, "shell", "pidof", packageName), Duration.ofSeconds(5))
        return if (result.ok) result.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet() else emptySet()
    }

    fun devicesLongFormat(): String = runner.runChecked(listOf(adb, "devices", "-l")).text

    fun screencapPng(serial: String): ByteArray =
        AdbOutput.pngFrom(runner.runChecked(listOf(adb, "-s", serial, "exec-out", "screencap", "-p"), Duration.ofSeconds(10)).stdout)

    fun displaySize(serial: String): Size? =
        AdbOutput.parseWmSize(runner.run(listOf(adb, "-s", serial, "shell", "wm", "size")).text)

    /** Screen density in dpi from `wm density` (an override wins), null when the device does not say. */
    fun displayDensity(serial: String): Int? =
        AdbOutput.parseWmDensity(runner.run(listOf(adb, "-s", serial, "shell", "wm", "density"), Duration.ofSeconds(5)).text)

    fun avdName(serial: String): String? {
        val result = runner.run(listOf(adb, "-s", serial, "emu", "avd", "name"), Duration.ofSeconds(5))
        if (!result.ok) return null
        return result.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && it != "OK" }
    }

    fun osVersion(serial: String): String? =
        runner.run(listOf(adb, "-s", serial, "shell", "getprop", "ro.build.version.release"), Duration.ofSeconds(5))
            .takeIf { it.ok }?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { "Android $it" }

    fun tap(serial: String, x: Int, y: Int) {
        shell(serial, "input", "tap", "$x", "$y")
    }

    fun swipe(serial: String, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
        shell(serial, "input", "swipe", "$x1", "$y1", "$x2", "$y2", "$durationMs")
    }

    fun keyEvent(serial: String, keyCode: Int) {
        shell(serial, "input", "keyevent", "$keyCode")
    }

    /** Holds the key codes together (`input keycombination`), e.g. CTRL_LEFT + A. */
    fun keyCombination(serial: String, vararg keyCodes: Int) {
        shell(serial, "input", "keycombination", *keyCodes.map { "$it" }.toTypedArray())
    }

    fun text(serial: String, text: String) {
        if (text.isEmpty()) return
        shell(serial, "input", "text", AdbOutput.escapeInputText(text))
    }

    /** Returns the uiautomator XML of the current screen. */
    fun dumpHierarchy(serial: String): String {
        val remote = "/sdcard/mobiletestbench_window_dump.xml"
        val dump = runner.run(listOf(adb, "-s", serial, "shell", "uiautomator", "dump", remote), Duration.ofSeconds(30))
        if (!dump.ok || dump.text.contains("ERROR")) {
            val detail = "${dump.text.trim()} ${dump.stderr.trim()}".trim().ifEmpty { "exit code ${dump.exitCode}" }
            // Exit 137 (SIGKILL) is how Android 17 stops uiautomator; mobilecli reads the hierarchy another way.
            throw IllegalStateException("uiautomator dump failed: $detail. Install mobilecli (npm install -g mobilecli) for the element inspector on this device.")
        }
        return runner.runChecked(listOf(adb, "-s", serial, "exec-out", "cat", remote)).text
    }

    private fun shell(serial: String, vararg args: String) {
        runner.runChecked(listOf(adb, "-s", serial, "shell") + args)
    }

    companion object {
        const val KEYCODE_HOME = 3
        const val KEYCODE_BACK = 4
        const val KEYCODE_POWER = 26
        const val KEYCODE_VOLUME_UP = 24
        const val KEYCODE_VOLUME_DOWN = 25
        const val KEYCODE_ENTER = 66
        const val KEYCODE_DEL = 67
        const val KEYCODE_CTRL_LEFT = 113
        const val KEYCODE_A = 29
        const val KEYCODE_APP_SWITCH = 187
    }
}

object AdbOutput {
    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** Drops text before the PNG signature: devices with several displays (foldables) print `[Warning] Multiple displays were found...` first. */
    fun pngFrom(output: ByteArray): ByteArray {
        val start = (0..output.size - pngSignature.size).firstOrNull { i -> pngSignature.indices.all { output[i + it] == pngSignature[it] } }
        return if (start == null || start == 0) output else output.copyOfRange(start, output.size)
    }

    private val sizeRegex = Regex("""(Physical|Override) size:\s*(\d+)x(\d+)""")

    /** Parses `adb shell wm size`; an override size wins over the physical size. */
    fun parseWmSize(output: String): Size? {
        val sizes = sizeRegex.findAll(output).associate { it.groupValues[1] to Size(it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
        return sizes["Override"] ?: sizes["Physical"]
    }

    private val densityRegex = Regex("""(Physical|Override) density:\s*(\d+)""")

    /** Parses `adb shell wm density`; an override density wins over the physical density. */
    fun parseWmDensity(output: String): Int? {
        val values = densityRegex.findAll(output).associate { it.groupValues[1] to it.groupValues[2].toInt() }
        return (values["Override"] ?: values["Physical"])?.takeIf { it > 0 }
    }

    /**
     * `adb shell input text` goes through the device shell, so shell metacharacters must be
     * escaped and spaces must be sent as %s.
     */
    fun escapeInputText(text: String): String {
        val special = "()<>|;&*\\~\"'`$?#![]{}"
        val sb = StringBuilder()
        for (c in text) {
            when {
                c == ' ' -> sb.append("%s")
                c == '%' -> sb.append("\\%")
                special.indexOf(c) >= 0 -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
