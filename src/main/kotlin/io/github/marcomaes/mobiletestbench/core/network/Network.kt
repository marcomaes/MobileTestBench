package io.github.marcomaes.mobiletestbench.core.network

import io.github.marcomaes.mobiletestbench.core.exec.CommandFailedException
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import java.time.Duration

/** Emulator console speed presets (`adb emu network speed <arg>`) with the bits per second the console reports for them. 0 means unlimited. */
enum class SpeedPreset(val arg: String, val label: String, val downBits: Long, val upBits: Long) {
    FULL("full", "Full (no limit)", 0, 0),
    G5("5g", "5G (no limit)", 0, 0),
    EVDO("evdo", "EVDO (280 / 75 Mbit/s)", 280_000_000, 75_000_000),
    LTE("lte", "LTE (173 / 58 Mbit/s)", 173_000_000, 58_000_000),
    HSDPA("hsdpa", "HSDPA (14 / 5.8 Mbit/s)", 13_980_000, 5_760_000),
    UMTS("umts", "UMTS (384 kbit/s)", 384_000, 384_000),
    EDGE("edge", "EDGE (474 kbit/s)", 473_600, 473_600),
    GPRS("gprs", "GPRS (58 / 29 kbit/s)", 57_600, 28_800),
    HSCSD("hscsd", "HSCSD (58 / 14 kbit/s)", 57_600, 14_400),
    GSM("gsm", "GSM (14 kbit/s)", 14_400, 14_400);
}

/**
 * Emulator console latency presets (`adb emu network delay <arg>`). The numbers are what the console reported on the Android 17
 * emulator (gprs and umts both read 35-200 ms there, not the documented values); the label of a shown value always comes from the readback.
 */
enum class DelayPreset(val arg: String, val label: String, val minMs: Int, val maxMs: Int) {
    NONE("none", "None", 0, 0),
    GPRS("gprs", "GPRS (35-200 ms)", 35, 200),
    EDGE("edge", "EDGE (80-400 ms)", 80, 400),
    UMTS("umts", "UMTS (35-200 ms)", 35, 200);
}

/** What `adb emu network status` reports. */
data class EmuNetworkStatus(val downBits: Long, val upBits: Long, val minMs: Int, val maxMs: Int) {
    val unlimited: Boolean get() = downBits == 0L && upBits == 0L
    val noDelay: Boolean get() = minMs == 0 && maxMs == 0
    val normal: Boolean get() = unlimited && noDelay

    /** For example `473600 bits/s down, 473600 bits/s up, 80-400 ms`. */
    fun describe(): String =
        (if (unlimited) "unlimited speed" else "$downBits bits/s down, $upBits bits/s up") + ", " + (if (noDelay) "no delay" else "$minMs-$maxMs ms")
}

/** A snapshot of the connectivity switches. Null: could not be read, the reason is in [errors] (keys airplane, wifi, data, online, emulator). */
data class NetworkState(
    val airplane: Boolean?,
    val wifi: Boolean?,
    val mobileData: Boolean?,
    /** Whether Android has a default network (not a ping). */
    val online: Boolean?,
    val emulator: EmuNetworkStatus? = null,
    val errors: Map<String, String> = emptyMap(),
)

/** Parsing and matching. Pure. */
object NetworkParsers {
    private val SPEED = Regex("^\\s*(download|upload) speed:\\s+(\\d+) bits/s", RegexOption.MULTILINE)
    private val LATENCY = Regex("^\\s*(minimum|maximum) latency:\\s+(\\d+) ms", RegexOption.MULTILINE)

    /** `cmd connectivity airplane-mode` prints `enabled` / `disabled`; `settings get global airplane_mode_on` prints 1 / 0. Null for anything else (`null`, an error). */
    fun airplane(text: String): Boolean? = when (text.trim().lowercase()) {
        "enabled", "1" -> true
        "disabled", "0" -> false
        else -> null
    }

    /** Only the first line of `cmd wifi status` counts (`Wifi is enabled`); the rest is long. `settings get global wifi_on` (1 / 0) is accepted too. */
    fun wifi(text: String): Boolean? {
        val first = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.lowercase() ?: return null
        return when {
            first == "wifi is enabled" || first == "1" -> true
            first == "wifi is disabled" || first == "0" -> false
            else -> null
        }
    }

    /** `settings get global mobile_data`: 1 / 0, null for `null` or garbage. */
    fun mobileData(text: String): Boolean? = when (text.trim()) {
        "1" -> true
        "0" -> false
        else -> null
    }

    /** From `dumpsys connectivity | grep -m1 "Active default network"`: `Active default network: none` is offline, any id is online. */
    fun online(text: String): Boolean? {
        val line = text.lineSequence().firstOrNull { it.contains("Active default network") } ?: return null
        val value = line.substringAfter(':', "").trim()
        return if (value.isEmpty()) null else !value.equals("none", ignoreCase = true)
    }

    /**
     * The first `KO:` line of an `adb emu` answer, or null when there is none. adb exits 0 even for KO, and stray lines such as
     * `Warning: time of day goes back` can appear, so only OK / KO lines count.
     */
    fun emuError(output: String): String? =
        output.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("KO") }?.let { it.removePrefix("KO").removePrefix(":").trim().ifEmpty { "KO" } }

    /** Parses `emu network status`; null when the numbers are not all there. */
    fun emuStatus(output: String): EmuNetworkStatus? {
        val speeds = SPEED.findAll(output).associate { it.groupValues[1] to it.groupValues[2].toLongOrNull() }
        val latency = LATENCY.findAll(output).associate { it.groupValues[1] to it.groupValues[2].toIntOrNull() }
        return EmuNetworkStatus(
            speeds["download"] ?: return null, speeds["upload"] ?: return null,
            latency["minimum"] ?: return null, latency["maximum"] ?: return null,
        )
    }

    /** The preset equal to [status]; [prefer] wins when it matches too (full and 5g are the same numbers). Null means custom. */
    fun matchSpeed(status: EmuNetworkStatus, prefer: SpeedPreset? = null): SpeedPreset? {
        val all = SpeedPreset.values().filter { it.downBits == status.downBits && it.upBits == status.upBits }
        return if (prefer in all) prefer else all.firstOrNull()
    }

    fun matchDelay(status: EmuNetworkStatus, prefer: DelayPreset? = null): DelayPreset? {
        val all = DelayPreset.values().filter { it.minMs == status.minMs && it.maxMs == status.maxMs }
        return if (prefer in all) prefer else all.firstOrNull()
    }

    /** First non-blank line of an error, shortened. */
    fun errorLine(stdout: String, stderr: String, exit: Int): String =
        (stderr.trim().ifEmpty { stdout.trim() }.lineSequence().firstOrNull { it.isNotBlank() } ?: "adb exited with code $exit").take(200)
}

/** adb shell / emu command lines. Pure. */
class NetworkCommands(private val adb: String, private val serial: String) {
    private fun shell(vararg args: String) = listOf(adb, "-s", serial, "shell", *args)

    fun readAirplane() = shell("cmd", "connectivity", "airplane-mode")
    fun readAirplaneSetting() = shell("settings", "get", "global", "airplane_mode_on")
    fun readWifi() = shell("cmd", "wifi", "status")
    fun readMobileData() = shell("settings", "get", "global", "mobile_data")

    /** One quoted shell string, so grep runs on the device and only one line comes back (dumpsys is huge). */
    fun readDefaultNetwork() = shell("dumpsys connectivity 2>/dev/null | grep -m1 'Active default network'")
    fun emuStatus() = listOf(adb, "-s", serial, "emu", "network", "status")

    fun airplane(enable: Boolean) = shell("cmd", "connectivity", "airplane-mode", if (enable) "enable" else "disable")
    fun wifi(enable: Boolean) = shell("svc", "wifi", if (enable) "enable" else "disable")
    fun mobileData(enable: Boolean) = shell("svc", "data", if (enable) "enable" else "disable")
    fun speed(arg: String) = listOf(adb, "-s", serial, "emu", "network", "speed", arg)
    fun delay(arg: String) = listOf(adb, "-s", serial, "emu", "network", "delay", arg)
}

/** One thing the tab can change. */
enum class NetworkKey { AIRPLANE, WIFI, DATA, SPEED, DELAY }

/** Reads and writes the network conditions of one Android device. Blocking; call off the EDT. [emulator] gates the speed and latency part. */
class AndroidNetwork(adb: String, serial: String, private val runner: CommandRunner, val emulator: Boolean) {
    private val commands = NetworkCommands(adb, serial)

    /** Reads everything; a failed part becomes an error text, the rest still comes back. */
    fun read(): NetworkState {
        val errors = LinkedHashMap<String, String>()
        fun <T> part(name: String, block: () -> T?): T? =
            try { block() } catch (e: Exception) { errors[name] = e.message ?: e.javaClass.simpleName; null }

        val airplane = part("airplane") {
            // cmd connectivity airplane-mode exists from about Android 11; older builds fall back to the setting.
            NetworkParsers.airplane(text(commands.readAirplane(), failOk = true) ?: "")
                ?: NetworkParsers.airplane(text(commands.readAirplaneSetting()) ?: "")
                ?: throw CommandFailedException("Airplane mode state unknown")
        }
        val wifi = part("wifi") { NetworkParsers.wifi(text(commands.readWifi())!!) ?: throw CommandFailedException("Wi-Fi state unknown") }
        val data = part("data") { NetworkParsers.mobileData(text(commands.readMobileData())!!) ?: throw CommandFailedException("Mobile data state unknown") }
        val online = part("online") { NetworkParsers.online(text(commands.readDefaultNetwork(), failOk = true) ?: "") }
        val emu = if (!emulator) null else part("emulator") {
            val out = text(commands.emuStatus(), failOk = true)!!
            NetworkParsers.emuError(out)?.let { throw CommandFailedException(it) }
            NetworkParsers.emuStatus(out) ?: throw CommandFailedException("Unexpected console output: ${out.trim().take(120)}")
        }
        return NetworkState(airplane, wifi, data, online, emu, errors)
    }

    fun write(key: NetworkKey, command: List<String>) {
        val result = runner.run(command, TIMEOUT)
        if (key == NetworkKey.SPEED || key == NetworkKey.DELAY) {
            // adb emu exits 0 even for KO.
            NetworkParsers.emuError(result.text + "\n" + result.stderr)?.let { throw CommandFailedException(it.take(200)) }
            if (!result.ok) throw CommandFailedException(NetworkParsers.errorLine(result.text, result.stderr, result.exitCode))
        } else if (!result.ok) {
            throw CommandFailedException(NetworkParsers.errorLine(result.text, result.stderr, result.exitCode))
        } else if (result.text.contains("Exception") || result.text.contains("Error:")) {
            // cmd and svc print a Java exception to stdout for a bad request.
            throw CommandFailedException(NetworkParsers.errorLine(result.text, result.stderr, result.exitCode))
        }
    }

    fun setAirplane(on: Boolean) = write(NetworkKey.AIRPLANE, commands.airplane(on))
    fun setWifi(on: Boolean) = write(NetworkKey.WIFI, commands.wifi(on))
    fun setMobileData(on: Boolean) = write(NetworkKey.DATA, commands.mobileData(on))
    fun setSpeed(arg: String) = write(NetworkKey.SPEED, commands.speed(arg))
    fun setDelay(arg: String) = write(NetworkKey.DELAY, commands.delay(arg))

    /** The write commands a [NetworkChange] stands for. */
    fun apply(change: NetworkChange) {
        when (change.key) {
            NetworkKey.AIRPLANE -> setAirplane(change.on!!)
            NetworkKey.WIFI -> setWifi(change.on!!)
            NetworkKey.DATA -> setMobileData(change.on!!)
            NetworkKey.SPEED -> setSpeed(change.arg!!)
            NetworkKey.DELAY -> setDelay(change.arg!!)
        }
    }

    private fun text(command: List<String>, failOk: Boolean = false): String? {
        val result = runner.run(command, TIMEOUT)
        if (!result.ok && !failOk) throw CommandFailedException(NetworkParsers.errorLine(result.text, result.stderr, result.exitCode))
        return result.text
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}

/** A write: a switch ([on]) or a console argument ([arg]). */
data class NetworkChange(val key: NetworkKey, val on: Boolean? = null, val arg: String? = null)

/** What Reset to normal writes, and the values it remembers. Pure. */
object NetworkReset {
    /** Console argument for a measured speed: the preset name when one matches, else `<up>:<down>` in kbit/s. */
    fun speedArg(status: EmuNetworkStatus): String =
        if (status.unlimited) "full" else NetworkParsers.matchSpeed(status)?.arg ?: "${status.upBits / 1000}:${status.downBits / 1000}"

    fun delayArg(status: EmuNetworkStatus): String =
        if (status.noDelay) "none" else NetworkParsers.matchDelay(status)?.arg ?: "${status.minMs}:${status.maxMs}"

    /**
     * The writes that bring [current] back to [originals] (the values before this tab changed anything), or to normal (airplane off, Wi-Fi and
     * data on, full speed, no delay) when nothing was remembered. Only values that differ are written. Order: airplane comes off first so
     * Wi-Fi and data can be switched on, and goes on last.
     */
    fun plan(originals: NetworkState?, current: NetworkState, emulator: Boolean): List<NetworkChange> {
        val airplane = originals?.airplane ?: false
        val wifi = originals?.wifi ?: true
        val data = originals?.mobileData ?: true
        val out = ArrayList<NetworkChange>()
        if (!airplane && current.airplane != false) out += NetworkChange(NetworkKey.AIRPLANE, on = false)
        if (current.wifi != wifi && !(airplane && current.airplane == true)) out += NetworkChange(NetworkKey.WIFI, on = wifi)
        if (current.mobileData != data) out += NetworkChange(NetworkKey.DATA, on = data)
        if (airplane && current.airplane != true) out += NetworkChange(NetworkKey.AIRPLANE, on = true)
        if (emulator) {
            val want = originals?.emulator
            val speed = want?.let { speedArg(it) } ?: "full"
            val delay = want?.let { delayArg(it) } ?: "none"
            val now = current.emulator
            if (now == null || now.upBits != (want?.upBits ?: 0L) || now.downBits != (want?.downBits ?: 0L)) out += NetworkChange(NetworkKey.SPEED, arg = speed)
            if (now == null || now.minMs != (want?.minMs ?: 0) || now.maxMs != (want?.maxMs ?: 0)) out += NetworkChange(NetworkKey.DELAY, arg = delay)
        }
        return out
    }
}

/** The state before this tab's first change, per device, in memory only. */
class NetworkBaseline {
    private val originals = HashMap<String, NetworkState>()

    @Synchronized fun remember(deviceKey: String, state: NetworkState) {
        if (deviceKey !in originals) originals[deviceKey] = state
    }

    @Synchronized fun originals(deviceKey: String): NetworkState? = originals[deviceKey]

    @Synchronized fun clear(deviceKey: String) {
        originals.remove(deviceKey)
    }
}

/** Orchestration: write, remember after the first successful write, stop at the first error; Reset clears the baseline only on success. */
class NetworkActions(val baseline: NetworkBaseline = NetworkBaseline()) {
    /** Returns the error text of the first failing write, or null. */
    fun change(deviceKey: String, backend: AndroidNetwork, before: NetworkState, changes: List<NetworkChange>): String? {
        val first = changes.firstOrNull() ?: return null
        // Remember only once the first write went through, so a refused write leaves no baseline (and no "changed" note) behind.
        attempt(backend, first)?.let { return it }
        baseline.remember(deviceKey, before)
        return write(backend, changes.drop(1))
    }

    /** Returns the failure text (null on success) and whether remembered values were used. */
    fun reset(deviceKey: String, backend: AndroidNetwork): Pair<String?, Boolean> {
        val originals = baseline.originals(deviceKey)
        val failure = write(backend, NetworkReset.plan(originals, backend.read(), backend.emulator))
        if (failure == null) baseline.clear(deviceKey)
        return failure to (originals != null)
    }

    private fun attempt(backend: AndroidNetwork, c: NetworkChange): String? =
        runCatching { backend.apply(c) }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }

    private fun write(backend: AndroidNetwork, changes: List<NetworkChange>): String? =
        changes.firstNotNullOfOrNull { attempt(backend, it) }
}
