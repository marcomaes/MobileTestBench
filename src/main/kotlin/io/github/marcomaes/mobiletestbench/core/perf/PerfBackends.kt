package io.github.marcomaes.mobiletestbench.core.perf

import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import java.time.Duration
import java.time.LocalDateTime

/** What the Performance tab reads and measures on one device. All calls block; run them off the EDT. */
interface PerfBackend : StartBackend {
    /** True when frame statistics exist (Android only). */
    val hasFrames: Boolean

    /** Label of the memory number plotted: what it is, for the chart title. */
    val memoryLabel: String

    /** One reading of [app] (package name or bundle id). A reading with a null pid means the app is not running. */
    fun sample(app: String): Reading

    /** A slow, accurate memory reading in MB (Android PSS, seconds per call); null when the backend has none or it failed. */
    fun pssMb(app: String): Double? = null
}

class PerfException(message: String) : RuntimeException(message)

/** Android through adb: one shell call per second (see [AndroidPerfParsers.probeScript]), PSS through a slower `dumpsys meminfo`, start time through `am start -W`. */
class AndroidPerf(
    private val adb: String,
    private val serial: String,
    private val runner: CommandRunner,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : PerfBackend {

    override val hasFrames = true
    override val memoryLabel = "PSS (updated every 5 s) and RSS"

    private fun shell(timeout: Duration, vararg args: String) = runner.run(listOf(adb, "-s", serial, "shell") + args, timeout)

    private fun requireSafe(app: String) {
        if (!AndroidPerfParsers.isSafeId(app)) throw PerfException("Not a valid package name: $app")
    }

    override fun sample(app: String): Reading {
        requireSafe(app)
        val result = shell(Duration.ofSeconds(15), AndroidPerfParsers.probeScript(app))
        if (!result.ok) throw PerfException("adb failed: ${result.stderr.trim().ifEmpty { result.text.trim() }}")
        val p = AndroidPerfParsers.parseProbe(result.text)
        val clock = p.deviceSeconds ?: (System.nanoTime() / 1e9)
        if (p.pid == null) return Reading(null, clock)
        return Reading(
            p.pid, clock,
            rssMb = p.rssKb?.let { it / 1024.0 },
            cpuSeconds = p.cpuTicks?.let { it / CLK_TCK },
            totalFrames = p.totalFrames, jankyFrames = p.jankyFrames,
        )
    }

    override fun pssMb(app: String): Double? {
        requireSafe(app)
        val result = shell(Duration.ofSeconds(25), "dumpsys", "meminfo", app)
        return AndroidPerfParsers.parsePssKb(result.text)?.let { it / 1024.0 }
    }

    private var resolved: Pair<String, String>? = null

    private fun component(app: String): String {
        resolved?.takeIf { it.first == app }?.let { return it.second }
        val out = shell(Duration.ofSeconds(15), "cmd", "package", "resolve-activity", "--brief", app).text
        val c = AndroidPerfParsers.parseResolvedActivity(out) ?: throw PerfException("No launchable activity found for $app (is it installed?)")
        resolved = app to c
        return c
    }

    private fun start(component: String): AmStartResult {
        // The launcher style intent: after HOME a plain `am start -n` only brings the task to the front and prints no TotalTime.
        val r = shell(
            Duration.ofSeconds(90), "am", "start", "-W", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
            "-f", "0x10200000", "-n", component,
        )
        val parsed = AndroidPerfParsers.parseAmStart(r.text)
        if (!r.ok || parsed.error != null) throw PerfException(parsed.error ?: r.text.trim().ifEmpty { r.stderr.trim() })
        return parsed
    }

    private fun running(app: String) = AndroidPerfParsers.firstPid(shell(Duration.ofSeconds(10), "pidof", app).text) != null

    override fun measure(app: String, kind: StartKind): StartResult {
        requireSafe(app)
        val comp = component(app)
        when (kind) {
            StartKind.COLD -> {
                shell(Duration.ofSeconds(15), "am", "force-stop", app)
                sleep(1500)
            }
            StartKind.WARM -> {
                if (!running(app)) { start(comp); sleep(3000) }
                shell(Duration.ofSeconds(10), "input", "keyevent", "KEYCODE_HOME")
                sleep(2000)
            }
        }
        val r = start(comp)
        val expected = if (kind == StartKind.COLD) "COLD" else "WARM"
        val state = r.launchState ?: "unknown"
        val total = r.totalMs?.takeIf { it > 0 }
        return when {
            total != null -> StartResult(total, "TotalTime, $state" + if (!state.startsWith(expected)) " (expected $expected)" else "")
            r.waitMs != null -> StartResult(r.waitMs, "WaitTime only (no TotalTime), $state" + (r.warning?.let { ": $it" } ?: ""))
            else -> throw PerfException("am start printed no timing" + (r.warning?.let { ": $it" } ?: ""))
        }
    }

    private companion object {
        /** USER_HZ, 100 on virtually every Android kernel (read from `getconf CLK_TCK` on an emulator only). */
        const val CLK_TCK = 100.0
    }
}

/**
 * iOS simulator. The app is a macOS process: pid from `launchctl list` inside the simulator, memory from `footprint` (physical footprint, not the much
 * larger rss), CPU from the delta of `ps` CPU time. No frame statistics exist without Instruments. Start time comes from the simulator log
 * (launch request to UIApplication active) and falls back to the duration of the launch call, which only times the spawn.
 */
class IosPerf(
    private val xcrun: String,
    private val udid: String,
    private val runner: CommandRunner,
    /** Presses the home button (a mobilecli call). */
    private val goHome: () -> Unit,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    private val clock: () -> Double = { System.nanoTime() / 1e9 },
) : PerfBackend {

    override val hasFrames = false
    override val memoryLabel = "Footprint"

    private fun simctl(timeout: Duration, vararg args: String) = runner.run(listOf(xcrun, "simctl") + args, timeout)

    private fun pidOf(app: String): Int? {
        val r = simctl(Duration.ofSeconds(15), "spawn", udid, "launchctl", "list")
        return IosPerfParsers.launchctlPid(r.text, app)
    }

    override fun sample(app: String): Reading {
        if (!AndroidPerfParsers.isSafeId(app)) throw PerfException("Not a valid bundle id: $app")
        val pid = pidOf(app)
        if (pid == null) return Reading(null, clock())
        val footprint = runner.run(listOf("footprint", "-p", pid.toString(), "-f", "bytes"), Duration.ofSeconds(10))
        val memory = IosPerfParsers.footprintMb(footprint.text) ?: run {
            // "Unable to find pid" means the app exited since launchctl listed it; anything else is a tool failure the user should see.
            val said = footprint.stderr.trim().ifEmpty { footprint.text.trim() }
            if (said.isEmpty() || said.contains("Unable to find")) return Reading(null, clock())
            throw PerfException("footprint failed: ${said.lineSequence().first()}")
        }
        val before = clock()
        val ps = runner.run(listOf("ps", "-o", "pid=,rss=,time=,etime=", "-p", pid.toString()), Duration.ofSeconds(10))
        val after = clock()
        val cpu = IosPerfParsers.psCpuSeconds(ps.text) ?: run {
            // ps prints nothing when the pid is gone; unparsable output is a real failure.
            if (ps.text.isBlank()) return Reading(null, after)
            throw PerfException("ps failed: ${ps.stderr.trim().ifEmpty { ps.text.trim() }.lineSequence().first()}")
        }
        return Reading(pid, (before + after) / 2, memoryMb = memory, cpuSeconds = cpu)
    }

    override fun measure(app: String, kind: StartKind): StartResult {
        if (!AndroidPerfParsers.isSafeId(app)) throw PerfException("Not a valid bundle id: $app")
        when (kind) {
            StartKind.COLD -> {
                // Terminating an app that is not running fails; that is fine here.
                simctl(Duration.ofSeconds(20), "terminate", udid, app)
                sleep(1500)
            }
            StartKind.WARM -> {
                if (pidOf(app) == null) { launch(app); sleep(3000) }
                goHome()
                sleep(2000)
            }
        }
        val begin = now().minusSeconds(1)
        val t0 = clock()
        val (pid, _) = launch(app)
        val callMs = Math.round((clock() - t0) * 1000)
        sleep(2500) // the unified log lags behind
        val log = runCatching {
            runner.run(
                listOf(xcrun, "simctl", "spawn", udid, "log", "show", "--start", IosPerfParsers.logStartArgument(begin), "--style", "compact",
                    "--predicate", IosPerfParsers.logPredicate(app)),
                Duration.ofSeconds(40),
            ).text
        }.getOrDefault("")
        val ms = IosPerfParsers.launchMillis(log, app, pid)
        return if (ms != null) StartResult(ms, "simulator log: launch request to active")
        else StartResult(callMs, "fallback: the launch call only, which times the spawn, not the start")
    }

    private fun launch(app: String): Pair<Int?, String> {
        val r = simctl(Duration.ofSeconds(60), "launch", udid, app)
        if (!r.ok) throw PerfException(r.stderr.trim().ifEmpty { r.text.trim() }.ifEmpty { "simctl launch failed" })
        return IosPerfParsers.launchOutputPid(r.text) to r.text
    }
}
