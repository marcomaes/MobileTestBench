package io.github.marcomaes.mobiletestbench.core

import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage

/** Screen orientation as mobilecli reports and accepts it (`device orientation get|set`). */
enum class Orientation(val value: String) {
    PORTRAIT("portrait"),
    LANDSCAPE("landscape");

    val other: Orientation get() = if (this == PORTRAIT) LANDSCAPE else PORTRAIT

    companion object {
        fun fromValue(value: String?): Orientation? = entries.firstOrNull { it.value == value?.trim() }

        /** Arguments of `mobilecli device orientation get` (the device is appended by [MobilecliJson.commandLine]). */
        fun getArgs(): List<String> = listOf("device", "orientation", "get")

        /** Arguments of `mobilecli device orientation set <value>`; mobilecli accepts lower case only. */
        fun setArgs(orientation: Orientation): List<String> = listOf("device", "orientation", "set", orientation.value)

        /** Reads `{"status":"ok","data":{"orientation":"landscape"}}` (the reply of both get and set). Throws [MobilecliException] on an error or an unknown value. */
        fun parse(stdout: String): Orientation {
            val data = MobilecliJson.data(stdout)
            val raw = data?.takeIf { it.isJsonObject }?.asJsonObject?.get("orientation")?.takeIf { it.isJsonPrimitive }?.asString
            return fromValue(raw) ?: throw MobilecliException("Unexpected orientation reply: ${raw ?: stdout.trim().take(100)}")
        }
    }
}

/** Fixes frames whose pixel layout does not follow the rotation. */
object FrameOrientation {

    /**
     * iOS simulators keep delivering the portrait framebuffer after a rotation, with the content drawn rotated 90 degrees clockwise.
     * When the frame is portrait but the logical size is landscape, this turns it 90 degrees counter-clockwise so it is upright
     * and has the same aspect as the logical space. Any other combination is returned unchanged.
     */
    fun upright(frame: BufferedImage, logical: Size?): BufferedImage {
        if (logical == null || logical.isEmpty || !logical.isLandscape || frame.width >= frame.height) return frame
        val out = BufferedImage(frame.height, frame.width, if (frame.type == 0) BufferedImage.TYPE_INT_RGB else frame.type)
        val g = out.createGraphics()
        try {
            val t = AffineTransform()
            t.translate(0.0, frame.width.toDouble())
            t.rotate(-Math.PI / 2)
            g.drawImage(frame, t, null)
        } finally {
            g.dispose()
        }
        return out
    }
}
