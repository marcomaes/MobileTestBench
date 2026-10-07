package io.github.marcomaes.mobiletestbench.core.inspector

import io.github.marcomaes.mobiletestbench.core.Rect
import io.github.marcomaes.mobiletestbench.core.Size
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Pixel to dp conversion for Android: 1 dp is 1/160 inch, so dp = px * 160 / dpi. */
object DpConverter {
    fun toDp(px: Int, densityDpi: Int): Double = px * 160.0 / densityDpi

    /** "40", or "26.7" when the value is not (nearly) a whole number. */
    fun format(dp: Double): String =
        if (abs(dp - Math.round(dp)) < 0.05) Math.round(dp).toString() else "%.1f".format(java.util.Locale.ROOT, dp)
}

/** WCAG 2.2 contrast math (SC 1.4.3). Colors are 0xRRGGBB ints. */
object Contrast {
    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(rgb: Int): Double =
        0.2126 * channel((rgb shr 16) and 0xFF) + 0.7152 * channel((rgb shr 8) and 0xFF) + 0.0722 * channel(rgb and 0xFF)

    /** (L1 + 0.05) / (L2 + 0.05), lighter over darker: 1.0 to 21.0. */
    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    fun hex(rgb: Int): String = "#%06X".format(rgb and 0xFFFFFF)
}

/** The estimated text and background colors of a region and their contrast ratio. */
data class ContrastEstimate(val ratio: Double, val foreground: Int, val background: Int)

/**
 * Estimates the text color and the background color of a region of a screen image with a quantised histogram:
 * pixels are grouped by their top 5 bits per channel, the background is the biggest group and the text color is
 * the group farthest from it in contrast. Groups are only used to find the pixels: the colors reported are the exact
 * mean of the pixels in the group (a group center can be 4 levels off, which moves a 4.6:1 text to 4.5).
 * This is a heuristic: photos and gradients, tiny regions and text that is almost the background color give null.
 */
object ContrastSampler {
    const val MAX_SAMPLES = 40_000
    const val MIN_WIDTH = 12
    const val MIN_HEIGHT = 8
    const val MIN_PIXELS = 24
    private const val SHARE_FOR_PHOTO_CHECK = 0.02
    private const val MAX_BIG_GROUPS = 12
    private const val MIN_BACKGROUND_SHARE = 0.3
    private const val MIN_FOREGROUND_SHARE = 0.005

    /** Maps [bounds] (in the logical space [logical]) onto [frame] like ScreenshotAnnotator does and estimates it. */
    fun estimate(frame: BufferedImage, logical: Size, bounds: Rect): ContrastEstimate? {
        if (logical.isEmpty) return null
        // A frame with another aspect ratio than the logical space (rotation in progress): positions would be wrong.
        val frameAspect = frame.width.toDouble() / frame.height
        val logicalAspect = logical.width.toDouble() / logical.height
        if (abs(frameAspect / logicalAspect - 1.0) > 0.02) return null
        val sx = frame.width.toDouble() / logical.width
        val sy = frame.height.toDouble() / logical.height
        val x0 = max(0, (bounds.x * sx).toInt())
        val y0 = max(0, (bounds.y * sy).toInt())
        val x1 = min(frame.width, ((bounds.x + bounds.width) * sx).toInt())
        val y1 = min(frame.height, ((bounds.y + bounds.height) * sy).toInt())
        val w = x1 - x0
        val h = y1 - y0
        if (w < MIN_WIDTH || h < MIN_HEIGHT || w.toLong() * h < MIN_PIXELS) return null
        val step = max(1, ceil(sqrt(w.toDouble() * h / MAX_SAMPLES)).toInt())
        val pixels = IntArray(((w + step - 1) / step) * ((h + step - 1) / step))
        var n = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                pixels[n++] = frame.getRGB(x, y) and 0xFFFFFF
                x += step
            }
            y += step
        }
        return analyse(pixels.copyOf(n))
    }

    fun analyse(rgbs: IntArray): ContrastEstimate? {
        if (rgbs.size < MIN_PIXELS) return null
        val groups = HashMap<Int, Group>()
        for (p in rgbs) {
            val key = ((p shr 19) and 0x1F shl 10) or ((p shr 11) and 0x1F shl 5) or ((p shr 3) and 0x1F)
            groups.getOrPut(key) { Group() }.add(p)
        }
        val total = rgbs.size
        // A photo or a gradient has many sizeable color groups: no reliable text color.
        if (groups.values.count { it.count >= total * SHARE_FOR_PHOTO_CHECK } > MAX_BIG_GROUPS) return null
        val background = groups.values.maxByOrNull { it.count } ?: return null
        if (background.count < total * MIN_BACKGROUND_SHARE) return null
        val bg = background.mean()
        val minCount = max(3.0, total * MIN_FOREGROUND_SHARE)
        val candidates = groups.values.filter { it !== background && it.count >= minCount }
            .map { it to Contrast.ratio(bg, it.mean()) }
        val farthest = candidates.maxOfOrNull { it.second } ?: return null
        // Most frequent among the (nearly) farthest: one stray anti-aliased pixel must not decide.
        val chosen = candidates.filter { it.second >= 1 + (farthest - 1) * 0.9 }.maxByOrNull { it.first.count } ?: return null
        val fg = chosen.first.mean()
        return ContrastEstimate(Contrast.ratio(bg, fg), fg, bg)
    }

    private class Group {
        var count = 0
        private var r = 0L
        private var g = 0L
        private var b = 0L

        fun add(p: Int) {
            count++
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }

        fun mean(): Int = (((r + count / 2) / count).toInt() shl 16) or (((g + count / 2) / count).toInt() shl 8) or ((b + count / 2) / count).toInt()
    }
}
