package io.github.marcomaes.mobiletestbench.core.visual

import io.github.marcomaes.mobiletestbench.core.Rect
import io.github.marcomaes.mobiletestbench.core.Size
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Knobs of [ImageDiff.compare]. */
data class DiffOptions(
    /** Largest per-channel (R, G, B) difference that still counts as equal, 0..255. */
    val tolerance: Int = 8,
    /** Forgive a pixel when it matches a neighbour (3x3) of the other image and the other way round: a text edge moved by 1px is not a change. */
    val antiAliasing: Boolean = true,
    /** Block size for merging: changes in the same or neighbouring blocks of this many pixels end up in one region (so roughly 1 to 2*gap-1 px apart merge). */
    val mergeGap: Int = 8,
    /** A region with fewer changed pixels than this is noise and dropped. */
    val minRegionPixels: Int = 4,
)

/** The outcome of a comparison of two same-sized images; regions are in image pixels. */
class DiffResult(
    val size: Size,
    /** Row-major mask of the changed pixels that belong to a kept region. */
    val mask: BooleanArray,
    val changedPixels: Long,
    val comparedPixels: Long,
    val regions: List<Rect>,
) {
    val percent: Double get() = if (comparedPixels == 0L) 0.0 else changedPixels * 100.0 / comparedPixels
    val matches: Boolean get() = regions.isEmpty()

    /** One line for the tab: "Match" or "3 regions, 1.25% changed". */
    fun verdict(): String =
        if (matches) "Match: no differences" else "${regions.size} ${if (regions.size == 1) "region" else "regions"}, ${"%.2f".format(java.util.Locale.ROOT, percent)}% of the pixels changed"
}

/** Pure per-pixel image comparison. No IDE classes. */
object ImageDiff {

    /** Null when the sizes differ: such images cannot be compared pixel by pixel. [ignore] rectangles (image pixels) are skipped. */
    fun compare(base: BufferedImage, current: BufferedImage, options: DiffOptions = DiffOptions(), ignore: List<Rect> = emptyList()): DiffResult? {
        if (base.width != current.width || base.height != current.height) return null
        val w = base.width
        val h = base.height
        val a = pixels(base)
        val b = pixels(current)
        val skip = BooleanArray(w * h)
        for (r in ignore) {
            for (y in max(0, r.y) until min(h, r.y + r.height)) for (x in max(0, r.x) until min(w, r.x + r.width)) skip[y * w + x] = true
        }
        val tol = options.tolerance.coerceIn(0, 255)
        val changed = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (skip[i] || close(a[i], b[i], tol)) continue
            if (options.antiAliasing && forgiven(a, b, w, h, x, y, tol)) continue
            changed[i] = true
        }
        val compared = skip.count { !it }.toLong()
        val (regions, kept) = cluster(changed, w, h, options.mergeGap, options.minRegionPixels)
        val count = kept.count { it }.toLong()
        return DiffResult(Size(w, h), kept, count, compared, regions)
    }

    /** The current image faded, with the changed pixels in red. */
    fun render(current: BufferedImage, result: DiffResult): BufferedImage {
        val w = result.size.width
        val h = result.size.height
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val src = pixels(current)
        for (i in src.indices) {
            out.setRGB(i % w, i / w, if (result.mask[i]) RED else fade(src[i]))
        }
        return out
    }

    /**
     * Merges nearby changed pixels: the image is cut in blocks of [gap] pixels, blocks that hold a changed pixel are joined with their
     * 8 neighbours, and each group gets the bounding box of its changed pixels. Groups under [minPixels] are dropped (and their pixels
     * removed from the returned mask).
     */
    fun cluster(changed: BooleanArray, w: Int, h: Int, gap: Int, minPixels: Int): Pair<List<Rect>, BooleanArray> {
        val block = max(1, gap)
        val bw = (w + block - 1) / block
        val bh = (h + block - 1) / block
        val has = BooleanArray(bw * bh)
        for (y in 0 until h) for (x in 0 until w) if (changed[y * w + x]) has[(y / block) * bw + x / block] = true
        val label = IntArray(bw * bh) { -1 }
        var groups = 0
        val stack = ArrayDeque<Int>()
        for (start in has.indices) {
            if (!has[start] || label[start] >= 0) continue
            label[start] = groups
            stack.add(start)
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                val cx = cur % bw
                val cy = cur / bw
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx !in 0 until bw || ny !in 0 until bh) continue
                    val n = ny * bw + nx
                    if (has[n] && label[n] < 0) { label[n] = groups; stack.add(n) }
                }
            }
            groups++
        }
        val minX = IntArray(groups) { Int.MAX_VALUE }
        val minY = IntArray(groups) { Int.MAX_VALUE }
        val maxX = IntArray(groups) { -1 }
        val maxY = IntArray(groups) { -1 }
        val count = IntArray(groups)
        for (y in 0 until h) for (x in 0 until w) {
            if (!changed[y * w + x]) continue
            val g = label[(y / block) * bw + x / block]
            minX[g] = min(minX[g], x); minY[g] = min(minY[g], y)
            maxX[g] = max(maxX[g], x); maxY[g] = max(maxY[g], y)
            count[g]++
        }
        val keep = BooleanArray(groups) { count[it] >= minPixels }
        val kept = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            if (changed[y * w + x] && keep[label[(y / block) * bw + x / block]]) kept[y * w + x] = true
        }
        val regions = (0 until groups).filter { keep[it] }.map { Rect(minX[it], minY[it], maxX[it] - minX[it] + 1, maxY[it] - minY[it] + 1) }
            .sortedWith(compareBy({ it.y }, { it.x }))
        return regions to kept
    }

    /** [rect] in the coordinate space of [from] expressed in [to] (frame pixels to logical coordinates or back). */
    fun scale(rect: Rect, from: Size, to: Size): Rect {
        if (from.isEmpty || to.isEmpty || from == to) return rect
        val sx = to.width.toDouble() / from.width
        val sy = to.height.toDouble() / from.height
        val x = (rect.x * sx).roundToInt()
        val y = (rect.y * sy).roundToInt()
        val r = ((rect.x + rect.width) * sx).roundToInt()
        val b = ((rect.y + rect.height) * sy).roundToInt()
        return Rect(x, y, max(1, r - x), max(1, b - y))
    }

    private fun pixels(img: BufferedImage): IntArray = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)

    private fun close(p: Int, q: Int, tol: Int): Boolean =
        abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)) <= tol &&
            abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)) <= tol &&
            abs((p and 0xFF) - (q and 0xFF)) <= tol

    /** True when a[x,y] matches some pixel around b[x,y] and b[x,y] matches some pixel around a[x,y]. */
    private fun forgiven(a: IntArray, b: IntArray, w: Int, h: Int, x: Int, y: Int, tol: Int): Boolean {
        val i = y * w + x
        var aInB = false
        var bInA = false
        for (dy in -1..1) for (dx in -1..1) {
            val nx = x + dx
            val ny = y + dy
            if (nx !in 0 until w || ny !in 0 until h) continue
            val n = ny * w + nx
            if (!aInB && close(a[i], b[n], tol)) aInB = true
            if (!bInA && close(b[i], a[n], tol)) bInA = true
        }
        return aInB && bInA
    }

    private const val RED = 0xFFFF0000.toInt()

    private fun fade(p: Int): Int {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        // 35% of the colour, 65% white.
        fun f(c: Int) = (c * 0.35 + 255 * 0.65).roundToInt()
        return (0xFF shl 24) or (f(r) shl 16) or (f(g) shl 8) or f(b)
    }
}
