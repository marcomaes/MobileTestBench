package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import io.github.marcomaes.mobiletestbench.core.inspector.WdaSourceParser
import io.github.marcomaes.mobiletestbench.core.recording.Assertion
import io.github.marcomaes.mobiletestbench.core.recording.ScriptPlayer
import io.github.marcomaes.mobiletestbench.core.recording.ScriptRecorder
import io.github.marcomaes.mobiletestbench.core.session.DeviceSession
import io.github.marcomaes.mobiletestbench.core.session.FrameListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssertionTest {

    private val ios: UiNode = WdaSourceParser.parse(
        javaClass.getResourceAsStream("/fixtures/ios_login.xml")!!.readBytes().toString(Charsets.UTF_8),
    )

    private fun node(platform: Platform, type: String, attrs: Map<String, String>) =
        UiNode(platform, type, attrs + ("visible" to "true"), Rect(0, 0, 100, 40))

    @Test
    fun `options depend on the element`() {
        val label = node(Platform.IOS, "XCUIElementTypeStaticText", mapOf("label" to "Welcome back, Marco"))
        assertEquals(
            listOf("Is visible", "Has text 'Welcome back, Marco'", "Contains text 'Welcome back'", "Is enabled"),
            Assertion.optionsFor(label).map { it.label },
        )
        val field = node(Platform.ANDROID, "android.widget.EditText", mapOf("text" to "marco@example.com", "enabled" to "true"))
        assertEquals(listOf("Is visible", "Has value 'marco@example.com'", "Is enabled"), Assertion.optionsFor(field).map { it.label })
        val toggle = node(Platform.IOS, "XCUIElementTypeSwitch", mapOf("value" to "1", "label" to "Grid", "enabled" to "false"))
        assertEquals(listOf("Is visible", "Has text 'Grid'", "Is disabled", "Is checked"), Assertion.optionsFor(toggle).map { it.label })
    }

    @Test
    fun `assertions are written as mobilewright matchers`() {
        assertEquals(".toBeVisible()", Assertion.Visible.matcher())
        assertEquals(".toHaveText('it\\'s')", Assertion.HasText("it's").matcher())
        assertEquals(".toContainText('a')", Assertion.ContainsText("a").matcher())
        assertEquals(".toHaveValue('x')", Assertion.HasValue("x").matcher())
        assertEquals(".toBeEnabled()", Assertion.Enabled(true).matcher())
        assertEquals(".toBeDisabled()", Assertion.Enabled(false).matcher())
        assertEquals(".toBeChecked()", Assertion.Checked.matcher())
    }

    @Test
    fun `the recorder picks the element and records an expect step`() {
        val r = ScriptRecorder(Platform.IOS)
        val picked = r.pick(ios, 100, 220)!! // email text field
        r.expect(picked.target, Assertion.Visible)
        assertEquals("await expect(screen.getByTestId('email-input')).toBeVisible();", r.code())
        assertNotNull(Assertion.optionsFor(picked.node))
        assertEquals(null, r.pick(ios, 5000, 5000))
    }

    private class FakeSession(private val screens: () -> UiNode) : DeviceSession {
        override val device = Device("sim", "Sim", Platform.IOS, DeviceKind.SIMULATOR)
        override val transport = "fake"
        override val supportsInput = true
        override fun start(listener: FrameListener) {}
        override fun logicalSize() = Size(400, 800)
        override fun tap(x: Int, y: Int) {}
        override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) {}
        override fun press(key: DeviceKey) {}
        override fun typeText(text: String) {}
        override fun hierarchy() = screens()
        override fun close() {}
    }

    private fun expectStep(assertion: Assertion): ScriptRecorder.Step {
        val target = ScriptRecorder(Platform.IOS).pick(ios, 100, 220)!!.target
        return ScriptRecorder.Expect(target, assertion)
    }

    @Test
    fun `replay passes when the assertion holds, retries, and fails with what it found`() {
        val player = ScriptPlayer(FakeSession { ios }, elementTimeoutMs = 80, pollMs = 10)
        assertTrue(player.run(listOf(expectStep(Assertion.Visible)), { _, _, _ -> }))
        assertTrue(player.run(listOf(expectStep(Assertion.HasText("Email"))), { _, _, _ -> }))

        var message: String? = null
        assertFalse(player.run(listOf(expectStep(Assertion.HasText("Wrong"))), { _, s, m -> if (s == ScriptPlayer.State.FAILED) message = m }))
        assertTrue(message!!.contains("has text 'Wrong'") && message!!.contains("was 'Email'"))

        var calls = 0
        val late = UiNode(Platform.IOS, "XCUIElementTypeApplication", emptyMap(), null)
        val retrying = ScriptPlayer(FakeSession { if (++calls < 3) late else ios }, elementTimeoutMs = 2000, pollMs = 10)
        assertTrue(retrying.run(listOf(expectStep(Assertion.Visible)), { _, _, _ -> }))
    }
}
