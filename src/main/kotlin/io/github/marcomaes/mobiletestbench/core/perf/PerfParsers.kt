package io.github.marcomaes.mobiletestbench.core.perf

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** What one Android probe call read. Every field is null when its source was missing from the output. */
data class AndroidProbe(
    /** Device clock in seconds (`date +%s.%N`). */
    val deviceSeconds: Double?,
    /** The first pid of `pidof`; null when the app is not running. */
    val pid: Int?,
    /** utime + stime of /proc/<pid>/stat in clock ticks. */
    val cpuTicks: Long?,
    val rssKb: Long?,
    val swapKb: Long?,
    val totalFrames: Long?,
    val jankyFrames: Long?,
)

/** The result of `am start -W`. */
data class AmStartResult(
    /** `COLD`, `WARM`, `HOT` or `UNKNOWN (0)` as printed. */
    val launchState: String?,
    val totalMs: Long?,
    val waitMs: Long?,
    /** The `Warning:` text, if any (for example "Activity not started, its current task has been brought to the front"). */
    val warning: String?,
    /** An error text (`Error:` line or `Status:` other than ok); null when the start worked. */
    val error: String?,
)

/** Parsers for the Android side: `/proc`, `dumpsys meminfo` and `gfxinfo`, `pidof`, `am start -W`. Pure. */
object AndroidPerfParsers {

    /** Package names and bundle ids are put into a shell script, so only these characters are allowed. */
    fun isSafeId(id: String): Boolean = id.isNotEmpty() && id.length <= 200 && id.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }

    /** The one adb shell script that reads clock, pid, cpu ticks, rss and the frame counters of [pkg] in a single call. */
    fun probeScript(pkg: String): String =
        "p=\$(pidof $pkg); set -- \$p; p=\$1; date +%s.%N; " +
            "if [ -n \"\$p\" ]; then echo \"PID=\$p\"; cat /proc/\$p/stat; grep -E 'VmRSS|VmSwap' /proc/\$p/status; fi; " +
            "dumpsys gfxinfo $pkg | grep -E 'Total frames|Janky frames:'; true"

    private val clockRegex = Regex("""^\d+(\.\d+)?$""")
    private val pidRegex = Regex("""^PID=(\d+)""")
    private val kbRegex = Regex("""^(VmRSS|VmSwap):\s*(\d+)\s*kB""")
    private val totalRegex = Regex("""^\s*Total frames rendered:\s*(\d+)""")
    private val jankyRegex = Regex("""^\s*Janky frames:\s*(\d+)\s*\(""")

    fun parseProbe(output: String): AndroidProbe {
        var clock: Double? = null
        var pid: Int? = null
        var ticks: Long? = null
        var rss: Long? = null
        var swap: Long? = null
        var total: Long? = null
        var janky: Long? = null
        for (raw in output.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (clock == null && clockRegex.matches(line)) { clock = line.toDouble(); continue }
            pidRegex.find(line)?.let { pid = it.groupValues[1].toInt(); return@let }
            kbRegex.find(line)?.let { if (it.groupValues[1] == "VmRSS") rss = it.groupValues[2].toLong() else swap = it.groupValues[2].toLong() }
            totalRegex.find(line)?.let { total = it.groupValues[1].toLong() }
            jankyRegex.find(line)?.let { janky = it.groupValues[1].toLong() }
            if (ticks == null && line.contains(')') && line.first().isDigit()) ticks = statTicks(line)
        }
        return AndroidProbe(clock, pid, ticks, rss, swap, total, janky)
    }

    /**
     * utime + stime (fields 14 and 15) of a `/proc/<pid>/stat` line. The command name is in parentheses and may contain spaces, so the fields are
     * counted after the LAST `)`.
     */
    fun statTicks(line: String): Long? {
        val rest = line.substringAfterLast(')').trim().split(Regex("\\s+"))
        val utime = rest.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = rest.getOrNull(12)?.toLongOrNull() ?: return null
        return utime + stime
    }

    /** First pid of `pidof` output (several space separated pids for a multi process app), or null. */
    fun firstPid(output: String): Int? = output.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()

    private val pssRegex = Regex("""TOTAL PSS:\s+(\d+)""")
    private val pssRowRegex = Regex("""^\s*TOTAL\s+(\d+)\s""", RegexOption.MULTILINE)

    /** Total PSS in KB from `dumpsys meminfo <pkg>`; null when the package is not running or the dump timed out (it prints that with exit code 0). */
    fun parsePssKb(output: String): Long? {
        if (output.contains("No process found")) return null
        return (pssRegex.find(output) ?: pssRowRegex.find(output))?.groupValues?.get(1)?.toLongOrNull()
    }

    /** The frame counters of `dumpsys gfxinfo <pkg>`: (total frames, janky frames) or null when missing. */
    fun parseGfxinfo(output: String): Pair<Long, Long>? {
        var total: Long? = null
        var janky: Long? = null
        for (line in output.lines()) {
            totalRegex.find(line)?.let { total = it.groupValues[1].toLong() }
            jankyRegex.find(line)?.let { janky = it.groupValues[1].toLong() }
        }
        return if (total != null && janky != null) total to janky else null
    }

    /** `adb shell cmd package resolve-activity --brief <pkg>`: the last line that looks like `pkg/.Activity`. */
    fun parseResolvedActivity(output: String): String? =
        output.lines().map { it.trim() }.lastOrNull { it.contains('/') && !it.contains(' ') }

    fun parseAmStart(output: String): AmStartResult {
        var state: String? = null
        var total: Long? = null
        var wait: Long? = null
        var warning: String? = null
        var error: String? = null
        for (raw in output.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("LaunchState:") -> state = line.substringAfter(':').trim()
                line.startsWith("TotalTime:") -> total = line.substringAfter(':').trim().toLongOrNull()
                line.startsWith("WaitTime:") -> wait = line.substringAfter(':').trim().toLongOrNull()
                line.startsWith("Warning:") -> warning = line.substringAfter(':').trim()
                line.startsWith("Error:") -> error = error ?: line.substringAfter(':').trim()
                line.startsWith("Status:") && !line.substringAfter(':').trim().equals("ok", true) -> error = error ?: line
            }
        }
        return AmStartResult(state, total, wait, warning, error)
    }
}

/** Parsers for the iOS simulator side (the app is a normal macOS process): launchctl, footprint, ps and the launch log. Pure. */
object IosPerfParsers {

    /** The pid of [bundleId] from `simctl spawn <udid> launchctl list`; null when it is not running. Matches the literal prefix so com.foo does not match com.foo.bar. */
    fun launchctlPid(output: String, bundleId: String): Int? {
        val prefix = "UIKitApplication:$bundleId["
        for (line in output.lines()) {
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size >= 3 && cols[2].startsWith(prefix)) return cols[0].toIntOrNull()
        }
        return null
    }

    private val footprintRegex = Regex("""\[(\d+)\]:.*Footprint:\s*(\d+) B""")

    /** The physical footprint in MB from `footprint -p <pid> -f bytes`; null when the process is gone. */
    fun footprintMb(output: String): Double? =
        footprintRegex.find(output)?.groupValues?.get(2)?.toLongOrNull()?.let { it / 1_048_576.0 }

    /**
     * Cumulative CPU time in seconds from `ps -o pid=,rss=,time=,etime= -p <pid>` (third column, `M:SS.hh`, `H:MM:SS.hh` or `D-HH:MM:SS`);
     * null when ps printed nothing (the process is gone).
     */
    fun psCpuSeconds(output: String): Double? {
        val cols = output.trim().lines().firstOrNull()?.trim()?.split(Regex("\\s+")) ?: return null
        return cols.getOrNull(2)?.let(::parseCpuTime)
    }

    fun parseCpuTime(text: String): Double? {
        var rest = text
        var days = 0.0
        if (rest.contains('-')) {
            days = rest.substringBefore('-').toDoubleOrNull() ?: return null
            rest = rest.substringAfter('-')
        }
        val parts = rest.split(':')
        if (parts.isEmpty() || parts.size > 3) return null
        var seconds = 0.0
        for (p in parts) seconds = seconds * 60 + (p.toDoubleOrNull() ?: return null)
        return seconds + days * 86400
    }

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val pidInLine = Regex("""\[(\d+):[0-9a-f]+]""")
    private val launchPid = Regex("""Launch successful for '[^']*', pid: (\d+)""")

    /** Host local time in the format `log show --start` takes. */
    fun logStartArgument(time: LocalDateTime): String = time.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

    /** The predicate that selects the launch request, its result and the app's UIKit lifecycle lines. */
    fun logPredicate(bundleId: String): String =
        "eventMessage CONTAINS \"Deactivation reason\" OR eventMessage CONTAINS \"Requesting launch of $bundleId\" OR eventMessage CONTAINS \"Launch successful for\""

    /**
     * Start time in ms from the simulator's unified log: the last "Requesting launch of <bundle>" line to the last
     * "Deactivation reason ... -> 0;" line of the app process (UIApplication became active). Null when either is missing.
     * [pid] is the app's pid from the launch call; when null the pid of the "Launch successful" line is used. This reads undocumented UIKit
     * debug text and includes the system launch animation.
     */
    fun launchMillis(log: String, bundleId: String, pid: Int?): Long? {
        val lines = log.lines().filter { it.length > 24 && it[0].isDigit() && it[4] == '-' }
        val request = lines.lastOrNull { it.contains("Requesting launch of $bundleId ") } ?: return null
        val appPid = pid ?: lines.lastOrNull { it.contains("Launch successful for '$bundleId'") }
            ?.let { launchPid.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: return null
        val active = lines.lastOrNull {
            it.contains("Deactivation reason") && it.contains("-> 0;") && pidInLine.find(it)?.groupValues?.get(1)?.toIntOrNull() == appPid
        } ?: return null
        val start = time(request) ?: return null
        val end = time(active) ?: return null
        val ms = ChronoUnit.MILLIS.between(start, end)
        return if (ms >= 0) ms else null
    }

    private fun time(line: String): LocalDateTime? = runCatching { LocalDateTime.parse(line.substring(0, 23), stamp) }.getOrNull()

    /** `com.foo: 12345` printed by `simctl launch`. */
    fun launchOutputPid(output: String): Int? = output.trim().substringAfterLast(':', "").trim().toIntOrNull()
}
