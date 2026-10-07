package io.github.marcomaes.mobiletestbench.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.Scrollable

/**
 * A label whose `<html>` text wraps to the width the layout gives it. A plain HTML label has one very wide preferred
 * line, so in a grid or a narrow panel it is either clipped or squeezed to a sliver (one word per line). Text that does not
 * start with `<html>` is shown as it is. Use it for notes and status messages that can be long.
 */
class WrappingLabel(initial: String = "") : JBLabel() {

    private var inner: String? = null
    private var wrappedAt = 0

    init {
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = rewrapToWidth()
        })
        text = initial
    }

    override fun setText(text: String?) {
        val html = text?.takeIf { it.startsWith("<html>") && it.endsWith("</html>") }
        if (html == null) {
            inner = null
            super.setText(text)
            return
        }
        inner = html.removePrefix("<html>").removeSuffix("</html>")
        wrappedAt = 0
        // Until the layout has given a width, assume a readable line length.
        applyWidth(if (width > 0) width else DEFAULT_WIDTH)
    }

    /** A label's minimum size is its preferred size, so text wrapped at a wider width would keep the layout from shrinking below it and get clipped. */
    override fun getMinimumSize(): java.awt.Dimension = super.getMinimumSize().also { if (inner != null) it.width = JBUI.scale(MIN_WIDTH) }

    private fun rewrapToWidth() {
        if (inner != null && width > MIN_WIDTH && width != wrappedAt) applyWidth(width)
    }

    private fun applyWidth(available: Int) {
        val body = inner ?: return
        val px = (available - JBUI.scale(4)).coerceAtLeast(JBUI.scale(MIN_WIDTH))
        if (px == wrappedAt) return
        wrappedAt = px
        super.setText("<html><body style='width:${px}px'>$body</body></html>")
        revalidate()
    }

    private companion object {
        const val DEFAULT_WIDTH = 420
        const val MIN_WIDTH = 60
    }
}

/** Content for a scroll pane that follows the viewport width, so wrapped text wraps instead of widening the panel. */
class WidthTrackingPanel(content: java.awt.Component) : javax.swing.JPanel(java.awt.BorderLayout()), Scrollable {
    init {
        add(content, java.awt.BorderLayout.NORTH)
    }

    override fun getPreferredScrollableViewportSize() = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
    override fun getScrollableBlockIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int) = visibleRect.height.coerceAtLeast(JBUI.scale(16))
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
}
