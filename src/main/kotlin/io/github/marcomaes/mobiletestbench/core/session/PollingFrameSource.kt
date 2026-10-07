package io.github.marcomaes.mobiletestbench.core.session

import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fallback stream: grabs a screenshot in a loop. Slow (a few FPS) but works for
 * every Android device and every booted iOS simulator without extra tooling.
 */
class PollingFrameSource(
    private val name: String,
    private val minIntervalMs: Long,
    private val grab: () -> BufferedImage?,
) : AutoCloseable {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @Volatile
    var paused: Boolean = false

    fun start(listener: FrameListener) {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ loop(listener) }, "MobileTestBench-$name").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop(listener: FrameListener) {
        var consecutiveErrors = 0
        while (running.get()) {
            val started = System.currentTimeMillis()
            if (!paused) {
                try {
                    grab()?.let(listener::onFrame)
                    consecutiveErrors = 0
                } catch (e: InterruptedException) {
                    return
                } catch (e: Exception) {
                    consecutiveErrors++
                    if (consecutiveErrors == 3) listener.onError("Screenshot failed: ${e.message}", e)
                }
            }
            val wait = minIntervalMs - (System.currentTimeMillis() - started)
            val backoff = if (consecutiveErrors > 0) 1000L else 0L
            try {
                Thread.sleep(maxOf(wait, 0L) + backoff)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    override fun close() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }
}
