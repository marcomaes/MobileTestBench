package io.github.marcomaes.mobiletestbench.core

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/** Draws on a copy of a screen frame, e.g. to point at the element a bug report is about. */
object ScreenshotAnnotator {

    /**
     * A copy of [frame] with [bounds] outlined and tinted. [bounds] is in the device's logical space ([logical]),
     * the frame may have another resolution (retina), so the rectangle is scaled.
     */
    fun outline(frame: BufferedImage, logical: Size, bounds: Rect, color: Color = Color(0x3574F0)): BufferedImage {
        val copy = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        val g = copy.createGraphics()
        try {
            g.drawImage(frame, 0, 0, null)
            if (logical.isEmpty) return copy
            val sx = frame.width.toDouble() / logical.width
            val sy = frame.height.toDouble() / logical.height
            val x = (bounds.x * sx).toInt()
            val y = (bounds.y * sy).toInt()
            val w = (bounds.width * sx).toInt().coerceAtLeast(1)
            val h = (bounds.height * sy).toInt().coerceAtLeast(1)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(color.red, color.green, color.blue, 50)
            g.fillRect(x, y, w, h)
            g.color = color
            g.stroke = BasicStroke((frame.width / 250f).coerceAtLeast(2f))
            g.drawRect(x, y, w, h)
        } finally {
            g.dispose()
        }
        return copy
    }
}
