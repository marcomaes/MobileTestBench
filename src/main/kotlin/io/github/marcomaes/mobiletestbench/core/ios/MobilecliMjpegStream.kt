package io.github.marcomaes.mobiletestbench.core.ios

import io.github.marcomaes.mobiletestbench.core.session.FrameListener
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

/** `mobilecli screencapture -f mjpeg` as a frame source, shared by the iOS and Android sessions. */
class MobilecliMjpegStream(private val mobilecli: MobilecliClient, private val id: String) {
    private val running = AtomicBoolean(false)
    @Volatile private var process: Process? = null
    private var thread: Thread? = null

    /** [onEnd] runs once on the stream thread, never after [close]: the frames delivered and the error message (null when the stream just ended). */
    fun start(listener: FrameListener, onEnd: (frames: Int, error: String?) -> Unit) {
        running.set(true)
        thread = Thread({
            var frames = 0
            var error: String? = null
            try {
                val started = mobilecli.startScreenStream(id)
                process = started
                // Drain stderr so a chatty process cannot block on a full pipe.
                Thread { runCatching { started.errorStream.transferTo(OutputStream.nullOutputStream()) } }
                    .apply { isDaemon = true; start() }
                started.inputStream.use { stream ->
                    MjpegReader(stream).readFrames({ !running.get() }) { jpeg ->
                        ImageIO.read(ByteArrayInputStream(jpeg))?.let {
                            frames++
                            listener.onFrame(it)
                        }
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            if (running.get()) onEnd(frames, error)
        }, "MobileTestBench-mobilecli-$id").apply {
            isDaemon = true
            start()
        }
    }

    fun close() {
        running.set(false)
        process?.destroy()
        process = null
        thread?.interrupt()
        thread = null
    }
}
