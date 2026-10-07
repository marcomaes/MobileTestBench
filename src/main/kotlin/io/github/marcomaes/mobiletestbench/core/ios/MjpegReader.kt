package io.github.marcomaes.mobiletestbench.core.ios

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Splits a multipart MJPEG stream (as served by WebDriverAgent on port 9100) into JPEG frames.
 * Frames are found by their JPEG start (FFD8) and end (FFD9) markers, so multipart headers
 * and boundaries are ignored. This keeps it robust against servers with sloppy headers.
 */
class MjpegReader(private val input: InputStream, private val maxFrameBytes: Int = 16 * 1024 * 1024) {

    private val buffer = ByteArray(64 * 1024)

    /** Blocks and calls [onFrame] for every complete JPEG until the stream ends or [shouldStop] returns true. */
    fun readFrames(shouldStop: () -> Boolean = { false }, onFrame: (ByteArray) -> Unit) {
        val frame = ByteArrayOutputStream(256 * 1024)
        var inFrame = false
        var previous = -1
        while (!shouldStop()) {
            val read = input.read(buffer)
            if (read < 0) return
            var segmentStart = 0
            for (i in 0 until read) {
                val b = buffer[i].toInt() and 0xFF
                if (!inFrame) {
                    if (previous == 0xFF && b == 0xD8) {
                        inFrame = true
                        frame.reset()
                        frame.write(0xFF)
                        frame.write(0xD8)
                        segmentStart = i + 1
                    }
                } else if (previous == 0xFF && b == 0xD9) {
                    frame.write(buffer, segmentStart, i + 1 - segmentStart)
                    onFrame(frame.toByteArray())
                    inFrame = false
                    previous = -1
                    continue
                }
                previous = b
            }
            if (inFrame) {
                frame.write(buffer, segmentStart, read - segmentStart)
                if (frame.size() > maxFrameBytes) {
                    inFrame = false
                    frame.reset()
                }
            }
        }
    }
}
