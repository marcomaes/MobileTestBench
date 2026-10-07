package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.inspector.AccessibilityAudit
import io.github.marcomaes.mobiletestbench.core.inspector.AccessibilityIssue.Rule
import io.github.marcomaes.mobiletestbench.core.inspector.ContrastStatus
import io.github.marcomaes.mobiletestbench.core.inspector.MobilecliAndroidRawParser
import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import java.awt.Color
import java.awt.image.BufferedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityAndroidTest {
    private val dpi = 420
    private val screen = Size(1080, 2424)

    private fun root() = UiNode(Platform.ANDROID, "hierarchy", emptyMap(), null)

    private fun node(cls: String, x: Int, y: Int, w: Int, h: Int, vararg attrs: Pair<String, String>, parent: UiNode): UiNode =
        UiNode(Platform.ANDROID, cls, mapOf("visible" to "true", "enabled" to "true") + attrs, Rect(x, y, w, h)).also(parent::addChild)

    private fun button(parent: UiNode, x: Int, y: Int, w: Int, h: Int, cls: String = "android.widget.Button") =
        node(cls, x, y, w, h, "clickable" to "true", "text" to "Go", parent = parent)

    private fun rules(r: UiNode, density: Int? = dpi) = AccessibilityAudit.run(r, density, logical = screen, contrast = false).map { it.rule }

    @Test
    fun `a 40 dp button on 420 dpi is below the Material recommendation but not below the minimum`() {
        val r = root(); button(r, 100, 500, 105, 105)
        val issues = AccessibilityAudit.run(r, dpi, logical = screen, contrast = false)
        assertEquals(listOf(Rule.TARGET_BELOW_RECOMMENDED), issues.map { it.rule })
        assertTrue(issues[0].message, "40 x 40 dp (105 x 105 px at 420 dpi)" in issues[0].message && "48" in issues[0].message)
    }

    @Test
    fun `a 48 dp button passes and 56 dp too`() {
        val r = root(); button(r, 100, 500, 126, 126); button(r, 100, 900, 996, 147)
        assertTrue(rules(r).isEmpty())
    }

    @Test
    fun `without density the android size rules are skipped`() {
        val r = root(); button(r, 100, 500, 20, 20)
        assertTrue(rules(r, null).isEmpty())
    }

    @Test
    fun `a 20 dp button crowded by another target fails the AA minimum, in dp not pixels`() {
        val r = root()
        button(r, 100, 500, 53, 53)   // 20 dp, centre x 126.5
        button(r, 140, 500, 126, 126) // 13.5 px = 5 dp from the centre, inside the 12 dp radius
        assertEquals(Rule.TARGET_BELOW_MINIMUM, rules(r).first())
    }

    @Test
    fun `the 24 dp spacing exception works on android`() {
        val r = root()
        button(r, 100, 500, 53, 53)    // 20 dp, centre at 126 px
        // the other target is 80 dp (210 px) away from the circle edge: 24 px would be wrong, 63 px (24 dp) is the radius
        button(r, 100 + 53 + 90, 500, 126, 126) // 90 px = 34 dp gap, more than the 12 dp radius
        val result = rules(r)
        assertTrue(Rule.TARGET_BELOW_MINIMUM !in result)
        assertEquals(listOf(Rule.TARGET_BELOW_RECOMMENDED), result.take(1))
    }

    @Test
    fun `a neighbour 20 px away is inside the 12 dp circle radius`() {
        val r = root()
        button(r, 100, 500, 20, 53)     // centre x 110
        button(r, 130, 500, 126, 126)   // 20 px = 7.6 dp from the centre: inside 12 dp, outside a 12 px circle
        assertEquals(Rule.TARGET_BELOW_MINIMUM, rules(r).first())
    }

    @Test
    fun `children of a clickable container are part of that target`() {
        val r = root()
        val row = node("android.widget.LinearLayout", 0, 500, 1080, 147, "clickable" to "true", parent = r)
        node("android.widget.CheckedTextView", 0, 500, 40, 40, "checkable" to "true", "text" to "x", parent = row)
        node("android.widget.ImageButton", 0, 500, 30, 30, "clickable" to "true", "content-desc" to "i", parent = row)
        assertTrue(rules(r).isEmpty())
    }

    @Test
    fun `switches checkboxes and spinners are exempt from the 48 dp rule but not the 24 dp rule`() {
        val r = root()
        node("android.widget.Switch", 100, 500, 122, 71, "clickable" to "true", "checkable" to "true", "text" to "t", parent = r) // 46 x 27 dp
        node("android.widget.CheckBox", 100, 900, 105, 105, "clickable" to "true", "checkable" to "true", "text" to "t", parent = r)
        assertTrue(rules(r).isEmpty())
        node("android.widget.CheckBox", 100, 1300, 40, 40, "clickable" to "true", "checkable" to "true", "text" to "t", parent = r) // 15 dp, alone
        assertTrue("alone, so the spacing exception applies", rules(r).isEmpty())
        // the 24 dp rule still applies to small-drawn controls once another target crowds them
        val crowded = root()
        node("android.widget.CheckBox", 100, 500, 40, 40, "clickable" to "true", "checkable" to "true", "text" to "t", parent = crowded)
        button(crowded, 145, 500, 126, 126)
        assertEquals(listOf(Rule.TARGET_BELOW_MINIMUM), rules(crowded))
        // a short spinner is exempt from the 48 dp rule, but crowded it fails the 24 dp rule
        val spin = root()
        node("android.widget.Spinner", 100, 500, 300, 40, "clickable" to "true", "text" to "s", parent = spin) // 114 x 15 dp
        assertTrue("alone", rules(spin).isEmpty())
        button(spin, 190, 545, 126, 126)
        assertEquals(listOf(Rule.TARGET_BELOW_MINIMUM), rules(spin))
    }

    @Test
    fun `clickable text views disabled controls and system ui are not size checked`() {
        val r = root()
        node("android.widget.TextView", 100, 500, 300, 40, "clickable" to "true", "text" to "link", parent = r)
        node("android.widget.Button", 100, 900, 50, 50, "clickable" to "true", "enabled" to "false", "text" to "x", parent = r)
        node("android.widget.ImageButton", 100, 1300, 50, 50, "clickable" to "true", "resource-id" to "com.android.systemui:id/back", "content-desc" to "Back", parent = r)
        assertTrue(rules(r).isEmpty())
    }

    @Test
    fun `controls cut by the bottom screen edge are not judged`() {
        val r = root()
        button(r, 0, 2418, 400, 6)
        assertTrue(rules(r).isEmpty())
        // the same size in the middle of the screen is judged
        val r2 = root(); button(r2, 0, 1000, 400, 6)
        assertTrue(rules(r2).isNotEmpty())
    }

    @Test
    fun `the real controls dump gives the expected size findings`() {
        val json = javaClass.getResourceAsStream("/fixtures/mobilecli_android_controls_dump.json")!!.readBytes().toString(Charsets.UTF_8)
        val root = MobilecliAndroidRawParser.parse(json)
        val issues = AccessibilityAudit.run(root, dpi, logical = screen, contrast = false)
        val sizes = issues.filter { it.rule != Rule.MISSING_NAME }
        // The stepper buttons are 40 x 40 dp.
        assertTrue(sizes.any { it.rule == Rule.TARGET_BELOW_RECOMMENDED && "40 x 40 dp" in it.message })
        // Nothing the Playground draws is below 24 dp except possibly crowded; the 56 dp rows and the switch are fine.
        assertTrue(sizes.none { it.node.type == "Switch" || it.node.type == "CheckedTextView" || it.node.type == "Spinner" })
        assertTrue(sizes.none { it.node.bounds!!.width > 900 })
    }

    // region contrast

    private fun frame(textColor: Int, bg: Int, w: Int = 1080, h: Int = 2424, area: Rect = Rect(40, 1000, 400, 60)): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(bg); g.fillRect(0, 0, w, h)
        g.color = Color(textColor)
        var x = area.x + 6
        while (x < area.x + area.width - 10) { g.fillRect(x, area.y + 20, 5, 20); x += 24 }
        g.dispose()
        return img
    }

    private fun textRoot(enabled: String = "true"): UiNode {
        val r = root()
        node("android.widget.TextView", 40, 1000, 400, 60, "text" to "Hello", "enabled" to enabled, parent = r)
        return r
    }

    @Test
    fun `low contrast text gets a hint with ratio and both colors`() {
        val result = AccessibilityAudit.analyse(textRoot(), dpi, { frame(0x9E9E9E, 0xFFFFFF) }, screen)
        val issue = result.issues.single()
        assertEquals(Rule.LOW_CONTRAST_HINT, issue.rule)
        assertEquals(ContrastStatus.ON, result.contrast)
        assertTrue(issue.message, "2.68:1" in issue.message && "#9E9E9E" in issue.message && "#FFFFFF" in issue.message && "hint" in issue.message)
        assertNotNull(issue.contrast)
    }

    @Test
    fun `good contrast and the borderline 4_61 text are not reported`() {
        assertTrue(AccessibilityAudit.run(textRoot(), dpi, { frame(0x202020, 0xFFFFFF) }, screen).isEmpty())
        assertTrue(AccessibilityAudit.run(textRoot(), dpi, { frame(0x757575, 0xFFFFFF) }, screen).isEmpty())
    }

    @Test
    fun `disabled text and elements without text are exempt`() {
        assertTrue(AccessibilityAudit.run(textRoot("false"), dpi, { frame(0x9E9E9E, 0xFFFFFF) }, screen).isEmpty())
        val r = root(); node("android.widget.ImageView", 40, 1000, 400, 60, "content-desc" to "Navigate up", parent = r)
        assertTrue(AccessibilityAudit.run(r, dpi, { frame(0x9E9E9E, 0xFFFFFF) }, screen).isEmpty())
    }

    @Test
    fun `contrast needs a frame, can be switched off and reports its status`() {
        val none = AccessibilityAudit.analyse(textRoot(), dpi, { null }, screen)
        assertEquals(ContrastStatus.NO_FRAME, none.contrast)
        assertEquals("Density 420 dpi (2.63x) - no frame: contrast skipped", none.statusLine)
        assertEquals(ContrastStatus.NO_FRAME, AccessibilityAudit.analyse(textRoot(), dpi, null, screen).contrast)
        val off = AccessibilityAudit.analyse(textRoot(), null, { frame(0x9E9E9E, 0xFFFFFF) }, screen, contrast = false)
        assertEquals(ContrastStatus.OFF, off.contrast)
        assertTrue(off.issues.isEmpty())
        assertEquals("Density unknown: Android size rules skipped - contrast hints off", off.statusLine)
    }

    @Test
    fun `ios text is sampled in points on a retina frame`() {
        val r = UiNode(Platform.IOS, "XCUIElementTypeApplication", emptyMap(), null)
        UiNode(Platform.IOS, "XCUIElementTypeStaticText", mapOf("label" to "Hello", "visible" to "true"), Rect(10, 100, 200, 30)).also(r::addChild)
        val img = frame(0xAAAAAA, 0xFFFFFF, 1206, 2622, Rect(30, 300, 600, 90))
        val result = AccessibilityAudit.analyse(r, null, { img }, Size(402, 874))
        assertEquals(listOf(Rule.LOW_CONTRAST_HINT), result.issues.map { it.rule })
        assertTrue(result.statusLine.startsWith("Sizes in points"))
    }

    // endregion
}
