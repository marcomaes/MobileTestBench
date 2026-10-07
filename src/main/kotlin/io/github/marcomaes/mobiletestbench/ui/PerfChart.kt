package io.github.marcomaes.mobiletestbench.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import io.github.marcomaes.mobiletestbench.core.perf.PerfPoint
import io.github.marcomaes.mobiletestbench.core.perf.Stats
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import javax.swing.JPanel
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/** One line of a [PerfChart]. */
class ChartSeries(val name: String, val color: Color, val value: (PerfPoint) -> Double?)

/**
 * A line chart drawn with plain Swing: a title row with current / min / avg / max readouts per series, and a canvas with the
 * last `window` seconds, grid, axis labels, gaps where a value is missing and a hover crosshair. [fixedMax] pins the top of the y axis (for percentages).
 */
class PerfChart(
    private val title: String,
    private val unit: String,
    private val series: List<ChartSeries>,
    private val fixedMax: Double? = null,
    private val decimals: Int = 1,
) : JPanel(BorderLayout()) {

    private val readout = JBLabel(" ")
    private var visiblePoints: List<PerfPoint> = emptyList()
    private var windowSeconds = 60
    private var tEnd = 0.0
    private var hoverX = -1
    private var note: String? = null

    private val canvas = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            paintChart(g.create() as Graphics2D)
        }
    }

    init {
        border = JBUI.Borders.empty(2, 6)
        add(readout, BorderLayout.NORTH)
        add(canvas, BorderLayout.CENTER)
        canvas.preferredSize = Dimension(300, JBUI.scale(90))
        canvas.minimumSize = Dimension(100, JBUI.scale(60))
        canvas.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) { hoverX = e.x; canvas.repaint() }
        })
        canvas.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) { hoverX = -1; canvas.repaint() }
        })
    }

    /** Shows [points] (oldest first); only the last [window] seconds are drawn and counted in the readouts. */
    fun setData(points: List<PerfPoint>, window: Int, extra: String? = null) {
        windowSeconds = window
        tEnd = points.lastOrNull()?.seconds ?: 0.0
        visiblePoints = points.filter { it.seconds >= tEnd - window }
        note = extra
        readout.text = buildReadout()
        canvas.repaint()
    }

    private fun buildReadout(): String {
        val parts = series.map { s ->
            val st = Stats.of(visiblePoints.map(s.value))
            val dot = "<font color='${hex(s.color)}'>&#9632;</font> "
            if (st.count == 0) "$dot${s.name}: no data"
            else "$dot${s.name}: now ${Stats.format(st.current, unit, decimals)} / min ${Stats.format(st.min, unit, decimals)} / " +
                "avg ${Stats.format(st.avg, unit, decimals)} / max ${Stats.format(st.max, unit, decimals)}"
        }
        return "<html><b>$title</b> (last ${windowSeconds}s)&nbsp; ${parts.joinToString("&nbsp;&nbsp; ")}${note?.let { "&nbsp;&nbsp; <i>$it</i>" } ?: ""}</html>"
    }

    private fun hex(c: Color) = String.format("#%02x%02x%02x", c.red, c.green, c.blue)

    private fun niceMax(v: Double): Double {
        if (v <= 0.0) return 1.0
        val p = 10.0.pow(kotlin.math.floor(log10(v)))
        val n = v / p
        val step = when { n <= 1.0 -> 1.0; n <= 2.0 -> 2.0; n <= 5.0 -> 5.0; else -> 10.0 }
        return step * p
    }

    private fun paintChart(g: Graphics2D) {
        try {
            paintChartBody(g)
        } finally {
            g.dispose()
        }
    }

    private fun paintChartBody(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val left = JBUI.scale(46)
        val right = JBUI.scale(8)
        val top = JBUI.scale(6)
        val bottom = JBUI.scale(16)
        val w = canvas.width - left - right
        val h = canvas.height - top - bottom
        if (w <= 10 || h <= 10) return
        val axis = JBColor(0x8C8C8C, 0x808080)
        val grid = JBColor(Color(0, 0, 0, 25), Color(255, 255, 255, 30))
        g.font = g.font.deriveFont(JBUI.scaleFontSize(10f))
        val fm = g.fontMetrics

        val all = series.flatMap { s -> visiblePoints.mapNotNull(s.value) }
        val yMax = fixedMax ?: niceMax((all.maxOrNull() ?: 0.0) * 1.1)
        val tStart = tEnd - windowSeconds
        fun x(t: Double) = left + ((t - tStart) / windowSeconds * w).toFloat()
        fun y(v: Double) = top + h - (v / yMax * h).toFloat().coerceIn(0f, h.toFloat())

        for (i in 0..4) {
            val v = yMax * i / 4
            val yy = y(v)
            g.color = grid
            g.drawLine(left, yy.toInt(), left + w, yy.toInt())
            g.color = axis
            val label = Stats.format(v, "", if (yMax >= 10) 0 else 1)
            g.drawString(label, left - fm.stringWidth(label) - 4, yy.toInt() + fm.ascent / 2 - 1)
        }
        g.color = axis
        g.drawString("-${windowSeconds}s", left, canvas.height - 3)
        g.drawString("now", left + w - fm.stringWidth("now"), canvas.height - 3)
        g.drawString(unit.trim(), 2, top + fm.ascent)

        if (visiblePoints.isEmpty()) {
            g.drawString("No samples yet", left + 8, top + h / 2)
            return
        }
        g.stroke = BasicStroke(JBUIScale.scale(1.5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        for (s in series) {
            g.color = s.color
            var path: Path2D.Float? = null
            var count = 0
            fun flush() {
                path?.let { if (count == 1) g.fillOval(path!!.currentPoint.x.toInt() - 2, path!!.currentPoint.y.toInt() - 2, 4, 4) else g.draw(it) }
                path = null
                count = 0
            }
            for (p in visiblePoints) {
                val v = s.value(p)
                if (v == null) { flush(); continue }
                val px = x(p.seconds)
                val py = y(v)
                if (path == null) path = Path2D.Float().apply { moveTo(px, py) } else path!!.lineTo(px, py)
                count++
            }
            flush()
        }

        if (hoverX in left..(left + w)) {
            val t = tStart + (hoverX - left).toDouble() / w * windowSeconds
            val near = visiblePoints.minByOrNull { abs(it.seconds - t) } ?: return
            val nx = x(near.seconds).toInt()
            g.stroke = BasicStroke(1f)
            g.color = axis
            g.drawLine(nx, top, nx, top + h)
            val lines = listOf("%.0fs ago".format(tEnd - near.seconds)) + series.map { s -> "${s.name}: ${Stats.format(s.value(near), unit, decimals)}" }
            val bw = lines.maxOf { fm.stringWidth(it) } + 10
            val bh = lines.size * fm.height + 6
            val bx = if (nx + bw + 8 > left + w) nx - bw - 6 else nx + 6
            g.color = JBColor(Color(255, 255, 255, 235), Color(60, 63, 65, 235))
            g.fillRoundRect(bx, top + 2, bw, bh, 6, 6)
            g.color = axis
            g.drawRoundRect(bx, top + 2, bw, bh, 6, 6)
            g.color = JBColor.foreground()
            lines.forEachIndexed { i, l -> g.drawString(l, bx + 5, top + 2 + 3 + fm.ascent + i * fm.height) }
        }
    }
}
