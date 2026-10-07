package io.github.marcomaes.mobiletestbench.core.coords

import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.Rect
import io.github.marcomaes.mobiletestbench.core.ScreenGeometry
import io.github.marcomaes.mobiletestbench.core.Size
import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import java.util.Locale

/**
 * A point on the mirror: the logical device coordinates (points on iOS, pixels on Android), the pixel of the
 * frame image under it and the position in percent of the logical size (the centre of the logical unit).
 */
data class HoverPoint(
    val x: Int,
    val y: Int,
    val frameX: Int,
    val frameY: Int,
    val percentX: Double,
    val percentY: Double,
    /** True when the frame has a different resolution than the logical space (iOS retina), so the frame pixels are worth showing. */
    val frameDiffers: Boolean,
)

object CoordinateMapper {
    /** Panel point to a [HoverPoint]; null outside the drawn frame or without a usable size. */
    fun map(geometry: ScreenGeometry, px: Int, py: Int, logical: Size, frame: Size): HoverPoint? {
        if (logical.isEmpty || frame.isEmpty) return null
        val (x, y) = geometry.panelToLogical(px, py, logical) ?: return null
        val (fx, fy) = geometry.panelToFrame(px, py) ?: return null
        return HoverPoint(x, y, fx, fy, percent(x, logical.width), percent(y, logical.height), logical != frame)
    }

    /** Position of logical unit [value] in an extent of [size], in percent, from the unit's centre. */
    fun percent(value: Int, size: Int): Double = if (size <= 0) 0.0 else (value + 0.5) * 100.0 / size
}

/** The element under the cursor, from the last known hierarchy. */
data class ElementInfo(val description: String, val bounds: Rect) {
    companion object {
        fun of(node: UiNode?): ElementInfo? {
            val bounds = node?.bounds ?: return null
            return ElementInfo(node.displayLabel(), bounds)
        }
    }
}

object CoordinateText {
    fun unit(platform: Platform?): String = if (platform == Platform.IOS) "pt" else "px"

    private fun one(v: Double): String = String.format(Locale.ROOT, "%.1f", v)

    /** "x, y", the form that pastes into a tap call. */
    fun xy(p: HoverPoint): String = "${p.x}, ${p.y}"

    /** "x%, y%" with one decimal. */
    fun percent(p: HoverPoint): String = "${one(p.percentX)}%, ${one(p.percentY)}%"

    fun size(e: ElementInfo): String = "${e.bounds.width} x ${e.bounds.height}"

    /** "x, y, w, h" in logical coordinates. */
    fun bounds(e: ElementInfo): String = "${e.bounds.x}, ${e.bounds.y}, ${e.bounds.width}, ${e.bounds.height}"

    /** The hover line under the mirror. [element] is null when no hierarchy is known or nothing is under the cursor. */
    fun infoLine(p: HoverPoint?, platform: Platform?, element: ElementInfo?, hierarchyKnown: Boolean): String {
        if (p == null) return "Move the mouse over the screen for coordinates"
        val parts = mutableListOf("x ${p.x}, y ${p.y} ${unit(platform)}")
        if (p.frameDiffers) parts += "frame ${p.frameX}, ${p.frameY} px"
        parts += percent(p)
        if (element != null) parts += "${element.description}  ${size(element)}"
        else if (!hierarchyKnown) parts += "no element info (open the inspector to load the hierarchy)"
        return parts.joinToString("  |  ")
    }

    /** Label next to the crosshair. */
    fun crosshair(p: HoverPoint): String = "${p.x}, ${p.y}"
}

object GridSteps {
    private val STEPS = listOf(10, 20, 25, 50, 100, 200, 250, 500, 1000, 2000)
    const val MAX_LINES = 12

    /** The smallest step that gives at most [MAX_LINES] lines along the longer side (about 8 to 12 on a phone). */
    fun choose(logical: Size): Int {
        val longest = maxOf(logical.width, logical.height)
        return STEPS.firstOrNull { lines(longest, it).size <= MAX_LINES } ?: STEPS.last()
    }

    /** Grid positions inside (0, extent): step, 2 * step, ... */
    fun lines(extent: Int, step: Int): List<Int> {
        if (extent <= 0 || step <= 0) return emptyList()
        return generateSequence(step) { it + step }.takeWhile { it < extent }.toList()
    }
}
