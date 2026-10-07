package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.inspector.AccessibilityAudit
import io.github.marcomaes.mobiletestbench.core.inspector.AccessibilityIssue.Rule
import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import io.github.marcomaes.mobiletestbench.core.inspector.WdaSourceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityAuditTest {

    private fun android(attrs: Map<String, String>, vararg children: UiNode): UiNode {
        val root = UiNode(Platform.ANDROID, "hierarchy", emptyMap(), null)
        root.addChild(UiNode(Platform.ANDROID, "android.widget.ImageButton", attrs, Rect(0, 0, 40, 40)).also { n -> children.forEach(n::addChild) })
        return root
    }

    private fun ios(type: String, attrs: Map<String, String>, w: Int, h: Int): UiNode {
        val root = UiNode(Platform.IOS, "XCUIElementTypeApplication", emptyMap(), null)
        root.addChild(UiNode(Platform.IOS, type, attrs, Rect(0, 0, w, h)))
        return root
    }

    @Test
    fun `clickable android element without text or description is flagged`() {
        val issues = AccessibilityAudit.run(android(mapOf("clickable" to "true")))
        assertEquals(listOf(Rule.MISSING_NAME), issues.map { it.rule })
    }

    @Test
    fun `android element is named by its own description or by a text child`() {
        assertTrue(AccessibilityAudit.run(android(mapOf("clickable" to "true", "content-desc" to "Cart"))).isEmpty())
        val child = UiNode(Platform.ANDROID, "android.widget.TextView", mapOf("text" to "Buy"), Rect(0, 0, 10, 10))
        assertTrue(AccessibilityAudit.run(android(mapOf("clickable" to "true"), child)).isEmpty())
    }

    @Test
    fun `non interactive android elements are ignored`() {
        assertTrue(AccessibilityAudit.run(android(mapOf("clickable" to "false"))).isEmpty())
    }

    @Test
    fun `small ios touch targets are flagged, android sizes are not`() {
        val small = AccessibilityAudit.run(ios("XCUIElementTypeButton", mapOf("label" to "Go", "visible" to "true"), 30, 44))
        assertEquals(listOf(Rule.TARGET_BELOW_RECOMMENDED), small.map { it.rule })
        assertTrue(AccessibilityAudit.run(ios("XCUIElementTypeButton", mapOf("label" to "Go"), 44, 44)).isEmpty())
        assertTrue(AccessibilityAudit.run(android(mapOf("clickable" to "true", "content-desc" to "x"))).isEmpty())
    }

    private fun iosNode(type: String, w: Int, h: Int, label: String = "x") =
        UiNode(Platform.IOS, "XCUIElementType$type", mapOf("label" to label, "visible" to "true"), Rect(0, 0, w, h))

    @Test
    fun `elements inside a cell or another control are part of that target`() {
        val root = UiNode(Platform.IOS, "XCUIElementTypeApplication", emptyMap(), null)
        val cell = iosNode("Cell", 350, 56).also(root::addChild)
        cell.addChild(iosNode("Button", 307, 20, "Record Video"))
        cell.addChild(iosNode("Button", 10, 14, "chevron"))
        val button = iosNode("Button", 200, 44).also(root::addChild)
        button.addChild(iosNode("Button", 10, 10))
        assertTrue(AccessibilityAudit.run(root).isEmpty())
    }

    @Test
    fun `system switches sliders and text fields are not size checked`() {
        val root = UiNode(Platform.IOS, "XCUIElementTypeApplication", emptyMap(), null)
        root.addChild(iosNode("Switch", 51, 31))
        root.addChild(iosNode("Slider", 200, 28))
        root.addChild(iosNode("TextField", 300, 34))
        assertTrue(AccessibilityAudit.run(root).isEmpty())
    }

    @Test
    fun `inline text links are not size checked`() {
        val root = UiNode(Platform.IOS, "XCUIElementTypeApplication", emptyMap(), null)
        root.addChild(iosNode("Link", 330, 16, "About Camera and ARKit & Privacy..."))
        assertTrue(AccessibilityAudit.run(root).isEmpty())
    }

    @Test
    fun `a 32 pt button meets the AA minimum but not the 44 pt recommendation`() {
        val root = UiNode(Platform.IOS, "XCUIElementTypeApplication", emptyMap(), null)
        root.addChild(iosNode("Button", 32, 32))
        assertEquals(listOf(Rule.TARGET_BELOW_RECOMMENDED), AccessibilityAudit.run(root).map { it.rule })
    }

    private fun placed(type: String, x: Int, y: Int, w: Int, h: Int): UiNode =
        UiNode(Platform.IOS, "XCUIElementType$type", mapOf("label" to "x", "visible" to "true"), Rect(x, y, w, h))

    @Test
    fun `an undersized button far from other targets passes through the spacing exception`() {
        val root = UiNode(Platform.IOS, "XCUIElementTypeApplication", emptyMap(), null)
        root.addChild(placed("Button", 0, 0, 20, 20))
        root.addChild(placed("Button", 200, 0, 100, 44))
        val rules = AccessibilityAudit.run(root).map { it.rule }
        assertTrue(Rule.TARGET_BELOW_MINIMUM !in rules)
        assertEquals(listOf(Rule.TARGET_BELOW_RECOMMENDED), rules)
    }

    @Test
    fun `undersized buttons crowding each other fail the AA minimum`() {
        val root = UiNode(Platform.IOS, "XCUIElementTypeApplication", emptyMap(), null)
        root.addChild(placed("Button", 0, 0, 20, 20))
        root.addChild(placed("Button", 20, 0, 20, 20))
        assertEquals(listOf(Rule.TARGET_BELOW_MINIMUM, Rule.TARGET_BELOW_MINIMUM), AccessibilityAudit.run(root).map { it.rule })
    }

    @Test
    fun `an undersized button next to a large target fails the AA minimum`() {
        val root = UiNode(Platform.IOS, "XCUIElementTypeApplication", emptyMap(), null)
        root.addChild(placed("Button", 0, 0, 20, 20))
        root.addChild(placed("Button", 20, 0, 100, 44))
        assertTrue(Rule.TARGET_BELOW_MINIMUM in AccessibilityAudit.run(root).map { it.rule })
    }

    @Test
    fun `hidden elements are not audited`() {
        val hidden = ios("XCUIElementTypeButton", mapOf("visible" to "false"), 10, 10)
        assertTrue(AccessibilityAudit.run(hidden).isEmpty())
    }

    @Test
    fun `the ios login fixture has no issues`() {
        val fixture = javaClass.getResourceAsStream("/fixtures/ios_login.xml")!!.readBytes().toString(Charsets.UTF_8)
        assertTrue(AccessibilityAudit.run(WdaSourceParser.parse(fixture)).isEmpty())
    }
}
