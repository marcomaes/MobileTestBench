package io.github.marcomaes.mobiletestbench.core.recording

import io.github.marcomaes.mobiletestbench.core.inspector.UiNode

/**
 * Keeps a recent hierarchy ready while recording, so a tap does not have to wait for a dump.
 *
 * A background thread captures the hierarchy once the device has been quiet for [settleMs] after the
 * last input that changed the screen, and again every [idleMs] while nothing happens (the screen may
 * change by itself). A snapshot taken after the latest such input is "fresh": it shows the screen the
 * user is looking at. Dumps can be slow (mobilecli: 0.1 to 5 s, sometimes much more), so [awaitFresh]
 * waits only a short time for a fresh one and then settles for the newest older snapshot, flagged as stale.
 *
 * Call [inputDone] from the thread that sends input, after each input that can change the screen
 * (tap, swipe, Enter, Home, Back; typing keeps the layout and needs no call); [awaitFresh] from the same thread.
 */
class HierarchySnapshots(
    private val capture: () -> UiNode,
    private val settleMs: Long = 300,
    private val idleMs: Long = 1000,
    private val retryMs: Long = 300,
) : AutoCloseable {

    private class Snapshot(val epoch: Int, val result: Result<UiNode>)

    /** [fresh] is false when the hierarchy was captured before the latest input. */
    class Captured(val root: UiNode, val fresh: Boolean)

    private val lock = Object()
    private var epoch = 0
    private var lastInputDoneAt = 0L
    private var snapshot: Snapshot? = null
    private var lastGood: UiNode? = null
    private var lastFailure: Throwable? = null
    private var closed = false
    private var thread: Thread? = null

    fun start() {
        synchronized(lock) {
            if (thread != null) return
            thread = Thread(::loop, "MobileTestBench-hierarchy-snapshots").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** The input just sent changes the screen: older snapshots are no longer valid. */
    fun inputDone() = synchronized(lock) {
        epoch++
        lastInputDoneAt = now()
        lock.notifyAll()
    }

    /** The newest successful capture, whatever its age, without waiting. Null before the first one. */
    fun latest(): UiNode? = synchronized(lock) { lastGood }

    /**
     * The hierarchy for a tap: a fresh one if it arrives within [freshWaitMs], otherwise the newest older one
     * (stale). With none at all it keeps waiting up to [maxWaitMs] and then throws, naming the last capture error.
     */
    fun awaitFresh(freshWaitMs: Long, maxWaitMs: Long): Captured = synchronized(lock) {
        val started = now()
        while (true) {
            val s = snapshot
            if (s != null && s.epoch == epoch && s.result.isSuccess) return Captured(s.result.getOrThrow(), fresh = true)
            check(!closed) { "Recording stopped" }
            val waited = now() - started
            val stale = lastGood
            if (waited >= freshWaitMs && stale != null) return Captured(stale, fresh = false)
            if (waited >= maxWaitMs) {
                throw IllegalStateException(lastFailure?.message ?: "No hierarchy within ${maxWaitMs / 1000} s", lastFailure)
            }
            lock.wait(minOf(if (stale != null) freshWaitMs - waited else maxWaitMs - waited, maxWaitMs - waited).coerceAtLeast(1))
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private fun loop() {
        while (true) {
            val startEpoch: Int
            synchronized(lock) {
                while (!closed) {
                    val wait = lastInputDoneAt + settleMs - now()
                    if (wait <= 0) break
                    lock.wait(wait)
                }
                if (closed) return
                startEpoch = epoch
            }
            val result = runCatching { capture() }
            synchronized(lock) {
                snapshot = Snapshot(startEpoch, result)
                result.onSuccess { lastGood = it; lastFailure = null }.onFailure { lastFailure = it }
                lock.notifyAll()
                val until = now() + if (result.isSuccess) idleMs else retryMs
                while (!closed && epoch == startEpoch) {
                    val remaining = until - now()
                    if (remaining <= 0) break
                    lock.wait(remaining)
                }
                if (closed) return
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
        thread?.interrupt()
    }

    private fun now() = System.nanoTime() / 1_000_000
}
