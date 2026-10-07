package io.github.marcomaes.mobiletestbench.core

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Maps between panel coordinates (where the frame is drawn, letterboxed) and the device's
 * logical coordinate space (pixels on Android, points on iOS). Frames may have a different
 * resolution than the logical space (e.g. iOS retina frames), so everything goes through
 * normalized 0..1 coordinates.
 */
class ScreenGeometry(private val panel: Size, private val frame: Size, private val padding: Int = 8) {

    val scale: Double = if (frame.isEmpty) 0.0 else
        min((panel.width - 2.0 * padding) / frame.width, (panel.height - 2.0 * padding) / frame.height).coerceAtLeast(0.0)

    /** Where the frame is drawn inside the panel. */
    val imageRect: Rect = run {
        val w = (frame.width * scale).roundToInt()
        val h = (frame.height * scale).roundToInt()
        Rect((panel.width - w) / 2, (panel.height - h) / 2, w, h)
    }

    /** Panel point to logical coordinates, null when outside the image. */
    fun panelToLogical(px: Int, py: Int, logical: Size): Pair<Int, Int>? {
        if (imageRect.width <= 0 || imageRect.height <= 0 || !imageRect.contains(px, py)) return null
        val nx = (px - imageRect.x).toDouble() / imageRect.width
        val ny = (py - imageRect.y).toDouble() / imageRect.height
        val x = floor(nx * logical.width).toInt().coerceIn(0, logical.width - 1)
        val y = floor(ny * logical.height).toInt().coerceIn(0, logical.height - 1)
        return x to y
    }

    /** Panel point to pixel coordinates of the frame itself (not the logical space), null when outside the image. */
    fun panelToFrame(px: Int, py: Int): Pair<Int, Int>? {
        if (imageRect.width <= 0 || imageRect.height <= 0 || !imageRect.contains(px, py)) return null
        val nx = (px - imageRect.x).toDouble() / imageRect.width
        val ny = (py - imageRect.y).toDouble() / imageRect.height
        return floor(nx * frame.width).toInt().coerceIn(0, frame.width - 1) to floor(ny * frame.height).toInt().coerceIn(0, frame.height - 1)
    }

    /** The panel point at the centre of the logical point (x, y), where its marker is drawn. */
    fun logicalToPanelPoint(x: Int, y: Int, logical: Size): Pair<Int, Int> {
        if (logical.isEmpty) return imageRect.x to imageRect.y
        return (imageRect.x + (x + 0.5) * imageRect.width / logical.width).toInt() to
            (imageRect.y + (y + 0.5) * imageRect.height / logical.height).toInt()
    }

    /** Logical rectangle (e.g. element bounds) to panel rectangle for highlighting. */
    fun logicalToPanel(rect: Rect, logical: Size): Rect {
        if (logical.isEmpty) return Rect(0, 0, 0, 0)
        val sx = imageRect.width.toDouble() / logical.width
        val sy = imageRect.height.toDouble() / logical.height
        return Rect(
            imageRect.x + (rect.x * sx).roundToInt(),
            imageRect.y + (rect.y * sy).roundToInt(),
            (rect.width * sx).roundToInt(),
            (rect.height * sy).roundToInt(),
        )
    }
}
