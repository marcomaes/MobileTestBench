package io.github.marcomaes.mobiletestbench.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.marcomaes.mobiletestbench.core.Rect
import io.github.marcomaes.mobiletestbench.core.ScreenGeometry
import io.github.marcomaes.mobiletestbench.core.Size
import io.github.marcomaes.mobiletestbench.core.coords.CoordinateMapper
import io.github.marcomaes.mobiletestbench.core.coords.CoordinateText
import io.github.marcomaes.mobiletestbench.core.coords.ElementInfo
import io.github.marcomaes.mobiletestbench.core.coords.GridSteps
import io.github.marcomaes.mobiletestbench.core.coords.HoverPoint
import io.github.marcomaes.mobiletestbench.core.input.Gestures
import io.github.marcomaes.mobiletestbench.settings.MobileTestBenchSettings
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.abs

/**
 * Draws the latest device frame (letterboxed) plus inspector highlights, and turns mouse
 * and keyboard input into device coordinates. Frames may arrive on any thread.
 */
class ScreenView : JComponent() {

    interface Listener {
        fun onTap(x: Int, y: Int)
        fun onSwipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long)

        /** The mouse was held still for [Gestures.LONG_PRESS_MS] while the button was down. */
        fun onLongPress(x: Int, y: Int, durationMs: Long)

        /** The second click of a double click; the first one was already sent as [onTap]. */
        fun onDoubleTap(x: Int, y: Int)

        /** Option/Alt drag: press, hold, then move. */
        fun onHoldDrag(fromX: Int, fromY: Int, toX: Int, toY: Int, holdMs: Long, durationMs: Long)
        fun onType(text: String)
        fun onSpecialKey(keyCode: Int)
        fun onInspectHover(x: Int, y: Int)
        fun onInspectClick(x: Int, y: Int)

        /** A click, key or wheel turn that would have reached the device, but the stream is frozen. */
        fun onFrozenInput()
    }

    var listener: Listener? = null

    /** Supplies the logical (input/hierarchy) coordinate size, usually DeviceSession::logicalSize. */
    var logicalSizeProvider: () -> Size? = { null }

    var inspectMode: Boolean = false
        set(value) {
            field = value
            cursor = if (value) Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR) else Cursor.getDefaultCursor()
            if (!value) hoverBounds = null
            repaint()
        }

    /** Called with the point under the mouse (null when it leaves the frame); feeds the hover line under the mirror. */
    var hoverListener: (HoverPoint?) -> Unit = {}

    /** The deepest element at a logical point from the last known hierarchy, for the context menu. Never fetches anything. */
    var elementProvider: (Int, Int) -> ElementInfo? = { _, _ -> null }

    /** Draws a light grid with edge labels and a crosshair at the cursor. */
    var gridVisible: Boolean = false
        set(value) {
            field = value
            repaint()
        }

    private var hoverPoint: HoverPoint? = null
    private var hoverPanel: java.awt.Point? = null

    /** The last tap in logical coordinates, marked with a ring until [tapMarkTimer] clears it. */
    private var tapMark: Pair<Int, Int>? = null

    private val tapMarkTimer = Timer(TAP_MARK_MS) {
        tapMark = null
        repaint()
    }.apply { isRepeats = false }

    private fun markTap(x: Int, y: Int) {
        tapMark = x to y
        tapMarkTimer.restart()
        repaint()
    }

    var placeholder: String = "Select a device and press Connect"
        set(value) {
            field = value
            repaint()
        }

    @Volatile private var frame: BufferedImage? = null

    /** While true the shown frame stays and new frames are dropped (not queued); input does not reach the device. */
    @Volatile var frozen: Boolean = false
        set(value) {
            field = value
            repaint()
        }

    var hoverBounds: Rect? = null
        set(value) {
            field = value
            repaint()
        }

    var selectedBounds: Rect? = null
        set(value) {
            field = value
            repaint()
        }

    /** Elements with an accessibility issue, shown while the Accessibility tab is open. */
    var issueBounds: List<Rect> = emptyList()
        set(value) {
            field = value
            repaint()
        }

    /** The element selected in the WebView tab (already mapped to device coordinates). */
    var webBounds: Rect? = null
        set(value) {
            field = value
            repaint()
        }

    /** Elements matched by the locator the user is trying in the inspector. */
    var matchBounds: List<Rect> = emptyList()
        set(value) {
            field = value
            repaint()
        }

    /** Changed regions of a visual comparison (logical coordinates), shown while the Visual tab is open. */
    var diffBounds: List<Rect> = emptyList()
        set(value) {
            field = value
            repaint()
        }

    private var pressPoint: Pair<Int, Int>? = null
    private var pressTime = 0L
    private var pressPanel: java.awt.Point? = null
    private var pressAlt = false

    /** Set when the long press timer fired for the current press: the release must then do nothing. */
    private var longPressFired = false

    /** Where the long press ring is drawn, until [cueTimer] clears it. */
    private var cue: java.awt.Point? = null

    private val cueTimer = Timer(CUE_MS) {
        cue = null
        repaint()
    }.apply { isRepeats = false }

    private fun longPressMs(): Long = Gestures.longPressMs(
        try { MobileTestBenchSettings.getInstance().state.longPressMs } catch (_: Throwable) { Gestures.LONG_PRESS_MS.toInt() },
    )

    private val longPressTimer = Timer(Gestures.LONG_PRESS_MS.toInt()) { fireLongPress() }.apply { isRepeats = false }

    /** Inspect mode keeps its meaning (a click selects), except with Option/Alt, which interacts with the device. */
    private fun interactive(e: MouseEvent) = !inspectMode || e.isAltDown

    private fun fireLongPress() {
        val point = pressPoint ?: return
        if (frozen) return
        longPressFired = true
        markTap(point.first, point.second)
        cue = pressPanel
        cueTimer.restart()
        repaint()
        listener?.onLongPress(point.first, point.second, longPressMs())
    }

    init {
        isFocusable = true
        isOpaque = true
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                requestFocusInWindow()
                // Right click (and Ctrl+click on macOS) opens the copy menu and is never a tap; see showContextMenu.
                if (isContextClick(e)) {
                    pressPoint = null
                    longPressTimer.stop()
                    // Show only where the platform fires the popup trigger on press (macOS, Linux); on Windows
                    // the trigger fires on release, so a plain right press is swallowed to avoid a second menu.
                    if (e.isPopupTrigger) showContextMenu(e)
                    return
                }
                pressPanel = e.point
                pressPoint = toLogical(e)
                pressTime = System.currentTimeMillis()
                pressAlt = e.isAltDown
                longPressFired = false
                longPressTimer.stop()
                if (pressPoint != null && interactive(e) && !frozen) {
                    longPressTimer.initialDelay = longPressMs().toInt()
                    longPressTimer.restart()
                }
            }

            override fun mouseDragged(e: MouseEvent) {
                updateHover(e)
                val startPanel = pressPanel ?: return
                // Moving before the timer fires makes it a swipe, not a long press.
                if (Gestures.movedBeyondSlop((e.x - startPanel.x).toDouble(), (e.y - startPanel.y).toDouble(), JBUI.scale(8).toDouble())) longPressTimer.stop()
            }

            override fun mouseReleased(e: MouseEvent) {
                longPressTimer.stop()
                if (isContextClick(e)) {
                    if (e.isPopupTrigger) showContextMenu(e) // Windows fires the popup trigger on release
                    pressPoint = null
                    return
                }
                val start = pressPoint ?: return
                val startPanel = pressPanel ?: return
                pressPoint = null
                val end = toLogical(e) ?: return
                // Option/Alt keeps the screen interactive while inspecting. Use the state at press time, which
                // is what decided whether the long press timer ran, so a release cannot contradict the press.
                if (frozen) {
                    // Frozen: a click in inspect mode selects from the frozen hierarchy, anything else is only a hint.
                    if (inspectMode && !pressAlt) listener?.onInspectClick(end.first, end.second) else listener?.onFrozenInput()
                    return
                }
                if (inspectMode && !pressAlt) {
                    if (!longPressFired) listener?.onInspectClick(end.first, end.second)
                    return
                }
                val moved = Gestures.movedBeyondSlop((e.x - startPanel.x).toDouble(), (e.y - startPanel.y).toDouble(), JBUI.scale(8).toDouble())
                when (Gestures.onRelease(longPressFired, moved, pressAlt, e.clickCount)) {
                    Gestures.Release.CONSUMED -> Unit
                    Gestures.Release.TAP -> { markTap(start.first, start.second); listener?.onTap(start.first, start.second) }
                    Gestures.Release.DOUBLE_TAP -> { markTap(start.first, start.second); listener?.onDoubleTap(start.first, start.second) }
                    Gestures.Release.SWIPE -> {
                        val duration = (System.currentTimeMillis() - pressTime).coerceIn(80, 2000)
                        listener?.onSwipe(start.first, start.second, end.first, end.second, duration)
                    }
                    Gestures.Release.HOLD_DRAG -> {
                        val duration = (System.currentTimeMillis() - pressTime).coerceIn(80, 2000)
                        listener?.onHoldDrag(start.first, start.second, end.first, end.second, Gestures.HOLD_DRAG_MS, duration)
                    }
                }
            }

            override fun mouseMoved(e: MouseEvent) {
                updateHover(e)
                if (!inspectMode) return
                toLogical(e)?.let { listener?.onInspectHover(it.first, it.second) }
            }

            override fun mouseExited(e: MouseEvent) {
                clearHover()
                if (inspectMode) hoverBounds = null
            }

            override fun mouseWheelMoved(e: MouseWheelEvent) {
                if (frozen) return listener?.onFrozenInput() ?: Unit
                if (inspectMode && !e.isAltDown) return
                val logical = logicalSizeProvider() ?: return
                val point = toLogical(e) ?: return
                if (abs(e.preciseWheelRotation) < 0.5) return
                // Scroll down = swipe up, a fifth of the screen per notch.
                val distance = (logical.height / 5) * if (e.preciseWheelRotation > 0) -1 else 1
                val toY = (point.second + distance).coerceIn(1, logical.height - 1)
                listener?.onSwipe(point.first, point.second, point.first, toY, 200)
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)

        addKeyListener(object : KeyAdapter() {
            override fun keyTyped(e: KeyEvent) {
                val c = e.keyChar
                if (c == KeyEvent.CHAR_UNDEFINED || c.isISOControl()) return
                if (frozen) { listener?.onFrozenInput(); e.consume(); return }
                listener?.onType(c.toString())
                e.consume()
            }

            override fun keyPressed(e: KeyEvent) {
                if (frozen) return
                when (e.keyCode) {
                    KeyEvent.VK_ENTER, KeyEvent.VK_BACK_SPACE, KeyEvent.VK_ESCAPE -> {
                        listener?.onSpecialKey(e.keyCode)
                        e.consume()
                    }
                }
            }
        })
    }

    override fun removeNotify() {
        longPressTimer.stop()
        cueTimer.stop()
        tapMarkTimer.stop()
        super.removeNotify()
    }

    fun setFrame(image: BufferedImage?) {
        if (frozen && image != null) return
        frame = image
        if (image == null) clearHover()
        repaint()
    }

    private fun isContextClick(e: MouseEvent) = e.isPopupTrigger || SwingUtilities.isRightMouseButton(e)

    private fun hoverAt(e: MouseEvent): HoverPoint? {
        val img = frame ?: return null
        val logical = logicalSizeProvider() ?: return null
        val geometry = geometry() ?: return null
        return CoordinateMapper.map(geometry, e.x, e.y, logical, Size(img.width, img.height))
    }

    private fun updateHover(e: MouseEvent) {
        val point = hoverAt(e)
        val changed = point != hoverPoint || (gridVisible && hoverPanel != e.point)
        hoverPoint = point
        hoverPanel = if (point == null) null else e.point
        if (point != hoverPointReported) {
            hoverPointReported = point
            hoverListener(point)
        }
        if (changed) repaint()
    }

    private var hoverPointReported: HoverPoint? = null

    private fun clearHover() {
        if (hoverPoint == null && hoverPointReported == null) return
        hoverPoint = null
        hoverPanel = null
        hoverPointReported = null
        hoverListener(null)
        repaint()
    }

    /**
     * Copy menu on right click. It only reads the last known state, so it is offered in every mode (inspect,
     * recording, assert, frozen) and never reaches the device; the click is not a tap and does not select in inspect mode.
     */
    private fun showContextMenu(e: MouseEvent) {
        val point = hoverAt(e) ?: return
        val menu = JPopupMenu()
        fun copyItem(label: String, text: String) {
            menu.add(JMenuItem("Copy $label").apply {
                addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(text)) }
            })
        }
        copyItem("\"${CoordinateText.xy(point)}\"", CoordinateText.xy(point))
        copyItem("\"${CoordinateText.percent(point)}\"", CoordinateText.percent(point))
        elementProvider(point.x, point.y)?.let { element ->
            menu.addSeparator()
            copyItem("element size \"${CoordinateText.size(element)}\"", CoordinateText.size(element))
            copyItem("element bounds \"${CoordinateText.bounds(element)}\"", CoordinateText.bounds(element))
        }
        menu.show(this, e.x, e.y)
    }

    fun currentFrame(): BufferedImage? = frame

    private fun geometry(): ScreenGeometry? {
        val img = frame ?: return null
        return ScreenGeometry(Size(width, height), Size(img.width, img.height), JBUI.scale(8))
    }

    private fun toLogical(e: MouseEvent): Pair<Int, Int>? {
        val logical = logicalSizeProvider() ?: return null
        return geometry()?.panelToLogical(e.x, e.y, logical)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = UIUtil.getPanelBackground()
            g2.fillRect(0, 0, width, height)
            val img = frame
            val geometry = geometry()
            if (img == null || geometry == null) {
                g2.color = UIUtil.getInactiveTextColor()
                g2.font = UIUtil.getLabelFont()
                val fm = g2.fontMetrics
                // Wrap so a long message (a how-to, an error) stays readable in a narrow tool window.
                val lines = mutableListOf<String>()
                for (paragraph in placeholder.split('\n')) {
                    var line = ""
                    for (word in paragraph.split(' ')) {
                        val next = if (line.isEmpty()) word else "$line $word"
                        if (line.isNotEmpty() && fm.stringWidth(next) > width - 32) { lines += line; line = word } else line = next
                    }
                    lines += line
                }
                var y = height / 2 - (lines.size - 1) * fm.height / 2
                for (line in lines) {
                    g2.drawString(line, (width - fm.stringWidth(line)) / 2, y)
                    y += fm.height
                }
                return
            }
            val r = geometry.imageRect
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.drawImage(img, r.x, r.y, r.width, r.height, null)

            val logical = logicalSizeProvider() ?: return
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            issueBounds.forEach { drawHighlight(g2, geometry.logicalToPanel(it, logical), ISSUE, fill = false) }
            matchBounds.forEach { drawHighlight(g2, geometry.logicalToPanel(it, logical), MATCH, fill = true) }
            diffBounds.forEach { drawHighlight(g2, geometry.logicalToPanel(it, logical), DIFF, fill = true) }
            selectedBounds?.let { drawHighlight(g2, geometry.logicalToPanel(it, logical), SELECTED, fill = true) }
            webBounds?.let { drawHighlight(g2, geometry.logicalToPanel(it, logical), WEB, fill = true) }
            hoverBounds?.let { drawHighlight(g2, geometry.logicalToPanel(it, logical), HOVER, fill = false) }
            cue?.let {
                val radius = JBUI.scale(22)
                g2.color = Color(CUE.red, CUE.green, CUE.blue, 60)
                g2.fillOval(it.x - radius, it.y - radius, radius * 2, radius * 2)
                g2.color = CUE
                g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
                g2.drawOval(it.x - radius, it.y - radius, radius * 2, radius * 2)
            }
            if (gridVisible) drawGrid(g2, geometry, logical)
            tapMark?.let { drawTapMark(g2, geometry, logical, it) }
            if (frozen) drawFrozenBadge(g2, r)
        } finally {
            g2.dispose()
        }
    }

    private fun drawGrid(g2: Graphics2D, geometry: ScreenGeometry, logical: Size) {
        val r = geometry.imageRect
        val step = GridSteps.choose(logical)
        val old = g2.clip
        g2.clipRect(r.x, r.y, r.width, r.height)
        g2.font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D - 2f)
        val fm = g2.fontMetrics
        g2.stroke = BasicStroke(1f)
        for (x in GridSteps.lines(logical.width, step)) {
            val px = r.x + (x.toDouble() * r.width / logical.width).toInt()
            g2.color = GRID
            g2.drawLine(px, r.y, px, r.y + r.height)
            drawLabel(g2, x.toString(), px + 2, r.y + fm.ascent + 1)
        }
        for (y in GridSteps.lines(logical.height, step)) {
            val py = r.y + (y.toDouble() * r.height / logical.height).toInt()
            g2.color = GRID
            g2.drawLine(r.x, py, r.x + r.width, py)
            drawLabel(g2, y.toString(), r.x + 2, py - 2)
        }
        val point = hoverPoint
        val at = hoverPanel
        if (point != null && at != null) {
            g2.color = CROSSHAIR
            g2.drawLine(r.x, at.y, r.x + r.width, at.y)
            g2.drawLine(at.x, r.y, at.x, r.y + r.height)
            val text = CoordinateText.crosshair(point)
            val w = fm.stringWidth(text) + 6
            val tx = if (at.x + 10 + w > r.x + r.width) at.x - 10 - w else at.x + 10
            val ty = if (at.y - 10 - fm.height < r.y) at.y + 10 + fm.height else at.y - 10
            drawLabel(g2, text, tx, ty)
        }
        g2.clip = old
    }

    /** Text with a translucent backing so it stays readable over any screen content. */
    private fun drawLabel(g2: Graphics2D, text: String, x: Int, baseline: Int) {
        val fm = g2.fontMetrics
        g2.color = LABEL_BG
        g2.fillRect(x - 1, baseline - fm.ascent, fm.stringWidth(text) + 2, fm.height)
        g2.color = LABEL_FG
        g2.drawString(text, x, baseline)
    }

    private fun drawTapMark(g2: Graphics2D, geometry: ScreenGeometry, logical: Size, at: Pair<Int, Int>) {
        val (px, py) = geometry.logicalToPanelPoint(at.first, at.second, logical)
        val radius = JBUI.scale(14)
        g2.color = TAP
        g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
        g2.drawOval(px - radius, py - radius, radius * 2, radius * 2)
        g2.fillOval(px - 2, py - 2, 4, 4)
        g2.font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D - 1f)
        drawLabel(g2, "${at.first}, ${at.second}", px + radius + 3, py - radius / 2)
    }

    private fun drawFrozenBadge(g2: Graphics2D, image: Rect) {
        g2.font = UIUtil.getLabelFont().deriveFont(java.awt.Font.BOLD)
        val fm = g2.fontMetrics
        val padX = JBUI.scale(8)
        val padY = JBUI.scale(3)
        val w = fm.stringWidth(FROZEN_TEXT) + padX * 2
        val h = fm.height + padY * 2
        val x = image.x + image.width - w - JBUI.scale(8)
        val y = image.y + JBUI.scale(8)
        g2.color = FROZEN
        g2.fillRoundRect(x, y, w, h, JBUI.scale(8), JBUI.scale(8))
        g2.color = Color.WHITE
        g2.drawString(FROZEN_TEXT, x + padX, y + padY + fm.ascent)
    }

    private fun drawHighlight(g2: Graphics2D, r: Rect, color: Color, fill: Boolean) {
        if (fill) {
            g2.color = Color(color.red, color.green, color.blue, 50)
            g2.fillRect(r.x, r.y, r.width, r.height)
        }
        g2.color = color
        g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
        g2.drawRect(r.x, r.y, r.width, r.height)
    }

    companion object {
        private const val CUE_MS = 450
        private const val TAP_MARK_MS = 900
        private val GRID = JBColor(Color(0, 0, 0, 55), Color(255, 255, 255, 60))
        private val CROSSHAIR = JBColor(Color(0xD9, 0x30, 0x30, 190), Color(0xFF, 0x7A, 0x7A, 200))
        private val TAP = JBColor(Color(0xD9, 0x30, 0x30), Color(0xFF, 0x6B, 0x6B))
        private val LABEL_BG = Color(0, 0, 0, 140)
        private val LABEL_FG = Color(255, 255, 255)
        private const val FROZEN_TEXT = "Frozen"
        private val FROZEN = JBColor(Color(0x2F6FDE), Color(0x3D5FA8))
        private val CUE = JBColor(Color(0x3574F0), Color(0x548AF7))
        private val SELECTED = JBColor(Color(0x3574F0), Color(0x548AF7))
        private val DIFF = JBColor(Color(0xE0245E), Color(0xFF5C8A))
        private val ISSUE = JBColor(Color(0xC7222D), Color(0xE55765))
        private val WEB = JBColor(Color(0xD97706), Color(0xF0A030))
        private val MATCH = JBColor(Color(0x208A3C), Color(0x5FB865))
        private val HOVER = JBColor(Color(0xE08855), Color(0xF2A26E))
    }
}
