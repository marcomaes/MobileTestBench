package io.github.marcomaes.mobiletestbench.core.perf

import java.util.Locale

/** One reading of the app by a backend. [pid] null means the app is not running. */
data class Reading(
    val pid: Int?,
    /** Clock for the CPU and frame deltas in seconds (device clock on Android, host clock on iOS). */
    val clockSeconds: Double,
    /** Memory in MB that needs no slow call (iOS footprint); null on Android (PSS comes from its own slower call). */
    val memoryMb: Double? = null,
    /** Resident set size in MB (Android only). */
    val rssMb: Double? = null,
    /** Cumulative CPU time of the process in seconds. */
    val cpuSeconds: Double? = null,
    val totalFrames: Long? = null,
    val jankyFrames: Long? = null,
)

/** One chart point. [seconds] is the time since sampling started; null values are gaps (app not running, no baseline yet, no frames rendered). */
data class PerfPoint(
    val seconds: Double,
    val memoryMb: Double? = null,
    val rssMb: Double? = null,
    val cpuPercent: Double? = null,
    val fps: Double? = null,
    val jankPercent: Double? = null,
)

/** CPU time deltas to percent of one core (can exceed 100 on several cores). The first reading of a pid only sets the baseline. */
class CpuTracker {
    private var pid: Int? = null
    private var lastCpu = 0.0
    private var lastClock = 0.0

    fun update(pid: Int?, cpuSeconds: Double?, clockSeconds: Double): Double? {
        if (pid == null || cpuSeconds == null) { this.pid = null; return null }
        val previous = this.pid
        val dCpu = cpuSeconds - lastCpu
        val dClock = clockSeconds - lastClock
        this.pid = pid
        lastCpu = cpuSeconds
        lastClock = clockSeconds
        if (previous != pid || dClock <= 0.0 || dCpu < 0.0) return null
        return dCpu / dClock * 100.0
    }
}

/** Frame statistics of one interval. [jankPercent] is null when no frame was rendered (an idle app). */
data class FrameStats(val fps: Double, val jankPercent: Double?)

/** Cumulative gfxinfo counters to the frames per second and janky percentage of each interval. A counter that goes down means a restart or a reset: re-baseline. */
class FrameDelta {
    private var pid: Int? = null
    private var lastTotal = 0L
    private var lastJanky = 0L
    private var lastClock = 0.0

    fun update(pid: Int?, total: Long?, janky: Long?, clockSeconds: Double): FrameStats? {
        if (pid == null || total == null || janky == null) { this.pid = null; return null }
        val previous = this.pid
        val dTotal = total - lastTotal
        val dJanky = janky - lastJanky
        val dClock = clockSeconds - lastClock
        this.pid = pid
        lastTotal = total
        lastJanky = janky
        lastClock = clockSeconds
        if (previous != pid || dClock <= 0.0 || dTotal < 0 || dJanky < 0) return null
        return FrameStats(dTotal / dClock, if (dTotal == 0L) null else dJanky * 100.0 / dTotal)
    }
}

/** Turns readings into [PerfPoint]s. Not thread safe: used by the one sampling thread. */
class PerfSampler {
    private val cpu = CpuTracker()
    private val frames = FrameDelta()

    /** [pssMb] is the last known PSS (Android), held between the slower dumpsys calls. */
    fun point(reading: Reading, pssMb: Double?, elapsedSeconds: Double): PerfPoint {
        if (reading.pid == null) {
            cpu.update(null, null, reading.clockSeconds)
            frames.update(null, null, null, reading.clockSeconds)
            return PerfPoint(elapsedSeconds)
        }
        val c = cpu.update(reading.pid, reading.cpuSeconds, reading.clockSeconds)
        val f = frames.update(reading.pid, reading.totalFrames, reading.jankyFrames, reading.clockSeconds)
        return PerfPoint(elapsedSeconds, reading.memoryMb ?: pssMb, reading.rssMb, c, f?.fps, f?.jankPercent)
    }
}

/** A fixed size ring buffer: the newest [capacity] items. */
class RingSeries<T>(val capacity: Int) {
    private val items = ArrayDeque<T>()
    val size: Int get() = items.size

    fun add(item: T) {
        items.addLast(item)
        while (items.size > capacity) items.removeFirst()
    }

    fun toList(): List<T> = items.toList()
    fun clear() = items.clear()
}

/** Readouts of a series; gaps (null) are ignored. */
data class Stats(val current: Double?, val min: Double?, val avg: Double?, val max: Double?, val count: Int) {
    companion object {
        fun of(values: List<Double?>): Stats {
            val real = values.filterNotNull()
            if (real.isEmpty()) return Stats(null, null, null, null, 0)
            // "now" is the newest value of the series, also when it is older than a trailing gap.
            return Stats(real.last(), real.min(), real.average(), real.max(), real.size)
        }

        fun format(value: Double?, unit: String, decimals: Int = 1): String =
            if (value == null) "-" else String.format(Locale.ROOT, "%.${decimals}f", value) + unit
    }
}

/** The samples as CSV (comma separated, dot decimals, empty cell for a gap). */
object PerfCsv {
    const val HEADER = "seconds,memory_mb,rss_mb,cpu_percent,fps,janky_percent"

    fun toCsv(points: List<PerfPoint>): String = buildString {
        append(HEADER).append('\n')
        for (p in points) {
            append(listOf(p.seconds, p.memoryMb, p.rssMb, p.cpuPercent, p.fps, p.jankPercent).joinToString(",") {
                if (it == null) "" else String.format(Locale.ROOT, "%.3f", it)
            }).append('\n')
        }
    }
}

enum class StartKind(val label: String) { COLD("Cold"), WARM("Warm") }

/** One measured start. [millis] null: the run failed, see [note]. */
data class StartRun(val index: Int, val kind: StartKind, val millis: Long?, val note: String = "")

data class StartSummary(val count: Int, val avg: Long, val min: Long, val max: Long) {
    fun text(): String = "Average: $avg ms (min $min, max $max, $count run${if (count == 1) "" else "s"})"

    companion object {
        fun of(runs: List<StartRun>): StartSummary? {
            val ms = runs.mapNotNull { it.millis }
            if (ms.isEmpty()) return null
            return StartSummary(ms.size, Math.round(ms.average()), ms.min(), ms.max())
        }
    }
}

/** A remembered measurement with where it was taken. */
data class StartEntry(val app: String, val device: String, val kind: StartKind, val millis: Long, val atMillis: Long)

/** The last [limit] measurements, newest first. Kept in memory for the session. */
class StartHistory(private val limit: Int = 20) {
    private val items = ArrayDeque<StartEntry>()

    fun add(entry: StartEntry) {
        items.addFirst(entry)
        while (items.size > limit) items.removeLast()
    }

    fun entries(): List<StartEntry> = items.toList()
}

/** A start measured by a backend: [millis] plus a plain note on how it was measured or what was odd. */
data class StartResult(val millis: Long?, val note: String = "")

/** The platform part of a start measurement: one cold or warm start of [app]. Blocks; throws with a readable message on failure. */
interface StartBackend {
    fun measure(app: String, kind: StartKind): StartResult
}

/** Runs N measurements in a row; stops at the first failure (a failed cold start has killed the app already) or when cancelled. */
object StartRunner {
    fun run(
        backend: StartBackend,
        app: String,
        kind: StartKind,
        runs: Int,
        cancelled: () -> Boolean,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onRun: (StartRun) -> Unit = {},
    ): List<StartRun> {
        val results = mutableListOf<StartRun>()
        for (i in 1..runs.coerceIn(1, 10)) {
            if (cancelled()) break
            progress(i - 1, runs)
            val run = try {
                val r = backend.measure(app, kind)
                StartRun(i, kind, r.millis, r.note)
            } catch (e: Exception) {
                StartRun(i, kind, null, e.message?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: e.javaClass.simpleName)
            }
            results += run
            onRun(run)
            if (run.millis == null) break
        }
        return results
    }
}
