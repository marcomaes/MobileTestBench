package io.github.marcomaes.mobiletestbench.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import javax.swing.JLabel
import javax.swing.text.JTextComponent

/**
 * Lays out every Device Tools tab at several panel widths and fails when a long text is squeezed into a sliver. That
 * happened once: a long note in a narrow grid column left the controls of the whole tab invisible. The look and feel of
 * the test environment is not the IDE's, but squeezing comes from the layout logic, which is the same.
 */
class ToolsLayoutTest : BasePlatformTestCase() {

    fun testNoTabSqueezesItsTexts() {
        val panel = DeviceToolsPanel(project)
        try {
            val problems = mutableListOf<String>()
            for (width in listOf(900, 500, 350)) {
                for (title in panel.tabTitles) {
                    val content = panel.contentOf(title) ?: continue
                    content.setSize(width, 260)
                    layoutAll(content)
                    walk(content) { c ->
                        val raw = (c as? JLabel)?.text ?: (c as? JTextComponent)?.text
                        if (raw != null && c.isVisible) {
                            val plain = raw.replace(Regex("<[^>]*>"), "").trim()
                            if (plain.length > 40 && c.width < 120) problems += "$title @$width: squeezed ${c.javaClass.simpleName} ${c.width}x${c.height} '${plain.take(40)}'"
                            if (plain.length > 12 && c.width in 1..30) problems += "$title @$width: tiny ${c.javaClass.simpleName} ${c.width}x${c.height} '${plain.take(20)}'"
                        }
                    }
                }
            }
            assertTrue(problems.joinToString("\n"), problems.isEmpty())
        } finally {
            panel.dispose()
        }
    }

    fun testFormTabsScrollAndTheOthersDoNot() {
        val panel = DeviceToolsPanel(project)
        try {
            for (title in listOf("Display", "Network", "Input", "Notifications", "Biometrics", "Location")) {
                assertTrue("$title should be a scrolling form", panel.contentOf(title) is javax.swing.JScrollPane)
            }
            for (title in listOf("Log", "Files", "Performance")) {
                assertFalse("$title manages its own scrolling", panel.contentOf(title) is javax.swing.JScrollPane)
            }
        } finally {
            panel.dispose()
        }
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        c.components.forEach { if (it is Container) layoutAll(it) }
    }

    private fun walk(c: Component, f: (Component) -> Unit) {
        f(c)
        if (c is Container) c.components.forEach { walk(it, f) }
    }
}
