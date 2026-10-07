package io.github.marcomaes.mobiletestbench.core.battery

import io.github.marcomaes.mobiletestbench.core.exec.CommandFailedException
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import java.time.Duration

/** Battery status values of `dumpsys battery set status <n>` (BatteryManager.BATTERY_STATUS_*). */
enum class BatteryStatus(val code: Int, val label: String) {
    UNKNOWN(1, "Unknown"),
    CHARGING(2, "Charging"),
    DISCHARGING(3, "Discharging"),
    NOT_CHARGING(4, "Not charging"),
    FULL(5, "Full");

    companion object {
        fun fromCode(code: Int?): BatteryStatus? = values().firstOrNull { it.code == code }
    }
}

/** What `dumpsys battery` reports. [updatesStopped] is true while a simulated state is in force. */
data class BatteryState(
    val level: Int?,
    val status: BatteryStatus?,
    val ac: Boolean?,
    val usb: Boolean?,
    val wireless: Boolean?,
    val updatesStopped: Boolean,
)

object BatteryParsers {
    fun state(output: String): BatteryState? {
        val values = HashMap<String, String>()
        var stopped = false
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (line.equals("UPDATES STOPPED", ignoreCase = true)) { stopped = true; continue }
            val i = line.indexOf(':')
            if (i <= 0) continue
            values.putIfAbsent(line.substring(0, i).trim().lowercase(), line.substring(i + 1).trim())
        }
        val level = values["level"]?.toIntOrNull()
        if (level == null && values["status"] == null) return null
        fun bool(key: String) = values[key]?.lowercase()?.let { if (it == "true") true else if (it == "false") false else null }
        return BatteryState(
            level, BatteryStatus.fromCode(values["status"]?.toIntOrNull()),
            bool("ac powered"), bool("usb powered"), bool("wireless powered"), stopped,
        )
    }

    /** The error in the output of a `dumpsys battery set|reset` call, or null. dumpsys exits 0 for bad input. */
    fun error(output: String): String? =
        output.lineSequence().map { it.trim() }.firstOrNull {
            it.startsWith("Unknown", true) || it.startsWith("Bad", true) || it.contains("Exception") ||
                it.startsWith("Error", true) || it.startsWith("Permission", true) || it.contains("Invalid", true)
        }?.take(200)

    fun errorLine(text: String, stderr: String, exitCode: Int): String =
        (stderr.lineSequence() + text.lineSequence()).map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "Exit code $exitCode"
}

/** One write of the Battery tab. */
sealed class BatteryChange {
    data class Level(val percent: Int) : BatteryChange()
    data class Status(val status: BatteryStatus) : BatteryChange()
    data class Ac(val on: Boolean) : BatteryChange()
    data class Usb(val on: Boolean) : BatteryChange()
}

object BatteryCommands {
    fun validLevel(percent: Int) = percent in 0..100

    private fun shell(adb: String, serial: String, vararg args: String) = listOf(adb, "-s", serial, "shell", "dumpsys", "battery") + args

    fun read(adb: String, serial: String) = shell(adb, serial)
    fun reset(adb: String, serial: String) = shell(adb, serial, "reset")

    fun set(adb: String, serial: String, change: BatteryChange): List<String> = when (change) {
        is BatteryChange.Level -> shell(adb, serial, "set", "level", change.percent.toString())
        is BatteryChange.Status -> shell(adb, serial, "set", "status", change.status.code.toString())
        is BatteryChange.Ac -> shell(adb, serial, "set", "ac", if (change.on) "1" else "0")
        is BatteryChange.Usb -> shell(adb, serial, "set", "usb", if (change.on) "1" else "0")
    }
}

/** Reads and writes the simulated battery of one Android device. Blocking; call off the EDT. */
class AndroidBattery(private val adb: String, private val serial: String, private val runner: CommandRunner) {

    fun read(): BatteryState {
        val r = runner.run(BatteryCommands.read(adb, serial), TIMEOUT)
        if (!r.ok) throw CommandFailedException(BatteryParsers.errorLine(r.text, r.stderr, r.exitCode))
        return BatteryParsers.state(r.text) ?: throw CommandFailedException("Unexpected dumpsys battery output: ${r.text.trim().take(120)}")
    }

    fun apply(change: BatteryChange) {
        if (change is BatteryChange.Level && !BatteryCommands.validLevel(change.percent)) throw CommandFailedException("Level must be 0 to 100")
        run(BatteryCommands.set(adb, serial, change))
    }

    /** `dumpsys battery reset`: the real battery state is reported again. */
    fun reset() = run(BatteryCommands.reset(adb, serial))

    private fun run(command: List<String>) {
        val r = runner.run(command, TIMEOUT)
        if (!r.ok) throw CommandFailedException(BatteryParsers.errorLine(r.text, r.stderr, r.exitCode))
        BatteryParsers.error(r.text + "\n" + r.stderr)?.let { throw CommandFailedException(it) }
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
