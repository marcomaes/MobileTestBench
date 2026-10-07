package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import io.github.marcomaes.mobiletestbench.core.inspector.WdaSourceParser
import io.github.marcomaes.mobiletestbench.core.recording.Assertion
import io.github.marcomaes.mobiletestbench.core.recording.Checks
import io.github.marcomaes.mobiletestbench.core.recording.CountIs
import io.github.marcomaes.mobiletestbench.core.recording.ScriptPlayer
import io.github.marcomaes.mobiletestbench.core.recording.ScriptRecorder
import io.github.marcomaes.mobiletestbench.core.recording.TextClearing
import io.github.marcomaes.mobiletestbench.core.recording.WaitState
import io.github.marcomaes.mobiletestbench.core.recording.WaitUntil
import io.github.marcomaes.mobiletestbench.core.session.DeviceSession
import io.github.marcomaes.mobiletestbench.core.session.FrameListener
import io.github.marcomaes.mobiletestbench.core.session.UnsupportedOperationOnDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitsAndFillTest {

    private fun screen(vararg children: String): UiNode = WdaSourceParser.parse(
        """<XCUIElementTypeApplication type="XCUIElementTypeApplication" name="Demo" label="Demo" enabled="true" visible="true" x="0" y="0" width="400" height="800">
             <XCUIElementTypeWindow type="XCUIElementTypeWindow" enabled="true" visible="true" x="0" y="0" width="400" height="800">
               ${children.joinToString("\n")}
             </XCUIElementTypeWindow></XCUIElementTypeApplication>""",
    )

    private fun button(name: String, y: Int, visible: Boolean = true, enabled: Boolean = true) =
        """<XCUIElementTypeButton type="XCUIElementTypeButton" name="$name" label="$name" enabled="$enabled" visible="$visible" x="20" y="$y" width="200" height="40"/>"""

    private fun field(value: String?) =
        """<XCUIElementTypeTextField type="XCUIElementTypeTextField" name="email-input" label="Email" ${if (value != null) "value=\"$value\"" else ""} enabled="true" visible="true" x="20" y="200" width="300" height="44"/>"""

    private class FakeSession(
        platform: Platform = Platform.IOS,
        private val onKeys: (List<String>) -> Unit = {},
        private val screens: () -> UiNode,
    ) : DeviceSession {
        val actions = mutableListOf<String>()
        override val device = Device("d", "D", platform, DeviceKind.SIMULATOR)
        override val transport = "fake"
        override val supportsInput = true
        override fun start(listener: FrameListener) {}
        override fun logicalSize() = Size(400, 800)
        override fun tap(x: Int, y: Int) { actions += "tap $x,$y" }
        override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) {}
        override fun press(key: DeviceKey) {}
        override fun typeText(text: String) { actions += "type $text" }
        override fun pressKeys(keys: List<String>) { actions += "keys ${keys.joinToString(" ")}"; onKeys(keys) }
        override fun hierarchy() = screens()
        override fun close() {}
    }

    private fun targetAt(root: UiNode, y: Int) = ScriptRecorder(Platform.IOS).pick(root, 100, y)!!.target

    private fun run(session: DeviceSession, step: ScriptRecorder.Step, timeoutMs: Long = 80): Pair<Boolean, String?> {
        var message: String? = null
        val ok = ScriptPlayer(session, elementTimeoutMs = timeoutMs, pollMs = 10)
            .run(listOf(step), { _, s, m -> if (s == ScriptPlayer.State.FAILED) message = m })
        return ok to message
    }

    @Test
    fun `generated code for hidden, waits and count`() {
        val root = screen(button("Item", 100), button("Item", 200), button("Item", 300))
        val picked = ScriptRecorder(Platform.IOS).pick(root, 100, 120)!!
        assertEquals(3, picked.matchCount)
        val r = ScriptRecorder(Platform.IOS)
        r.add(picked.target, Assertion.Hidden)
        r.add(picked.target, WaitUntil(WaitState.VISIBLE))
        r.add(picked.target, WaitUntil(WaitState.DISABLED))
        r.add(picked.target, CountIs(3))
        assertEquals(
            listOf(
                "await expect(screen.getByRole('button', { name: 'Item' }).nth(0)).toBeHidden();",
                "await screen.getByRole('button', { name: 'Item' }).nth(0).waitFor({ state: 'visible' });",
                "await screen.getByRole('button', { name: 'Item' }).nth(0).waitFor({ state: 'disabled' });",
                "await expect(screen.getByRole('button', { name: 'Item' })).toHaveCount(3);",
            ),
            r.code().lines(),
        )
    }

    @Test
    fun `menu offers hidden and waits always and count only for repeated elements`() {
        val root = screen(button("Item", 100), button("Item", 200), button("Solo", 300))
        val rec = ScriptRecorder(Platform.IOS)
        val repeated = Checks.optionsFor(rec.pick(root, 100, 120)!!.let { it.node }, rec.pick(root, 100, 120)!!.matchCount).map { it.label }
        assertTrue("Becomes hidden" in repeated)
        assertEquals(listOf("Wait until visible", "Wait until hidden", "Wait until enabled", "Wait until disabled"), repeated.filter { it.startsWith("Wait") })
        assertEquals("Count of matching elements = 2", repeated.last())
        val solo = rec.pick(root, 100, 320)!!
        assertEquals(1, solo.matchCount)
        assertTrue(Checks.optionsFor(solo.node, solo.matchCount).none { it is CountIs })
    }

    @Test
    fun `hidden passes for a missing element and fails with visible otherwise`() {
        val with = screen(button("Go", 100))
        val step = ScriptRecorder.Expect(targetAt(with, 120), Assertion.Hidden)
        assertTrue(run(FakeSession { screen() }, step).first)
        assertTrue(run(FakeSession { screen(button("Go", 100, visible = false)) }, step).first)
        val (ok, message) = run(FakeSession { with }, step)
        assertFalse(ok)
        assertTrue(message!!.contains("becomes hidden") && message.contains("was visible"))
        // Other assertions still need the element.
        assertFalse(run(FakeSession { screen() }, ScriptRecorder.Expect(targetAt(with, 120), Assertion.Visible)).first)
    }

    @Test
    fun `wait states follow mobilewright checkState`() {
        val visibleEnabled = screen(button("Go", 100)).walk().first { it.type == "Button" }
        val disabled = screen(button("Go", 100, enabled = false)).walk().first { it.type == "Button" }
        val invisible = screen(button("Go", 100, visible = false)).walk().first { it.type == "Button" }
        assertTrue(WaitState.VISIBLE.holds(visibleEnabled)); assertFalse(WaitState.VISIBLE.holds(null)); assertFalse(WaitState.VISIBLE.holds(invisible))
        assertTrue(WaitState.HIDDEN.holds(null)); assertTrue(WaitState.HIDDEN.holds(invisible)); assertFalse(WaitState.HIDDEN.holds(visibleEnabled))
        assertTrue(WaitState.ENABLED.holds(visibleEnabled)); assertFalse(WaitState.ENABLED.holds(null)); assertFalse(WaitState.ENABLED.holds(disabled))
        assertTrue(WaitState.DISABLED.holds(disabled)); assertFalse(WaitState.DISABLED.holds(null)); assertFalse(WaitState.DISABLED.holds(visibleEnabled))
    }

    @Test
    fun `waitFor polls until the state holds or times out`() {
        val with = screen(button("Go", 100))
        val target = targetAt(with, 120)
        var calls = 0
        val appearing = FakeSession { if (++calls < 4) screen() else with }
        assertTrue(run(appearing, ScriptRecorder.WaitFor(target, WaitState.VISIBLE), 2000).first)
        calls = 0
        val vanishing = FakeSession { if (++calls < 4) with else screen() }
        assertTrue(run(vanishing, ScriptRecorder.WaitFor(target, WaitState.HIDDEN), 2000).first)
        val (ok, message) = run(FakeSession { screen() }, ScriptRecorder.WaitFor(target, WaitState.DISABLED))
        assertFalse(ok)
        assertTrue(message!!.contains("to be disabled") && message.contains("element not found"))
    }

    @Test
    fun `count assertion counts all matches and retries`() {
        val three = screen(button("Item", 100), button("Item", 200), button("Item", 300))
        val target = ScriptRecorder(Platform.IOS).pick(three, 100, 120)!!.target
        assertTrue(run(FakeSession { three }, ScriptRecorder.ExpectCount(target, 3)).first)
        var calls = 0
        val growing = FakeSession { if (++calls < 3) screen(button("Item", 100), button("Item", 200)) else three }
        assertTrue(run(growing, ScriptRecorder.ExpectCount(target, 3), 2000).first)
        val (ok, message) = run(FakeSession { three }, ScriptRecorder.ExpectCount(target, 2))
        assertFalse(ok)
        assertTrue(message!!.contains("2 elements") && message.contains("was 3"))
    }

    @Test
    fun `clear keys differ per platform`() {
        assertEquals(listOf("ctrl+a", "backspace"), TextClearing.keysFor(Platform.ANDROID))
        assertEquals(listOf("cmd+a", "backspace"), TextClearing.keysFor(Platform.IOS))
    }

    @Test
    fun `fill clears the field in one key call before typing`() {
        var value: String? = "old text"
        val session = FakeSession(onKeys = { value = null }) { screen(field(value)) }
        val target = targetAt(screen(field("x")), 220)
        assertTrue(run(session, ScriptRecorder.Fill(target, "new")).first)
        assertEquals(listOf("tap 170,222", "keys cmd+a backspace", "type new"), session.actions)
    }

    @Test
    fun `fill uses ctrl+a on android devices`() {
        val session = FakeSession(Platform.ANDROID, onKeys = {}) { screen(field(null)) }
        assertTrue(run(session, ScriptRecorder.Fill(targetAt(screen(field("x")), 220), "new")).first)
        assertEquals("keys ctrl+a backspace", session.actions[1])
    }

    @Test
    fun `fill fails and does not type when the field keeps its text`() {
        val session = FakeSession { screen(field("old text")) }
        val (ok, message) = run(session, ScriptRecorder.Fill(targetAt(screen(field("x")), 220), "new"))
        assertFalse(ok)
        assertTrue(message!!.contains("Failed to clear element"))
        assertTrue(session.actions.none { it.startsWith("type") })
    }

    @Test
    fun `fill fails clearly when the connection cannot press keys`() {
        val session = object : DeviceSession by FakeSession(screens = { screen(field(null)) }) {
            override fun pressKeys(keys: List<String>) = throw UnsupportedOperationOnDevice("no keys here")
        }
        val (ok, message) = run(session, ScriptRecorder.Fill(targetAt(screen(field("x")), 220), "new"))
        assertFalse(ok)
        assertTrue(message!!.contains("no keys here"))
    }

    private fun androidField(text: String, hint: String?) = UiNode(
        Platform.ANDROID, "android.widget.EditText",
        listOfNotNull("text" to text, hint?.let { "hint" to it }, "class" to "android.widget.EditText").toMap(), null,
    )

    @Test
    fun `android text equal to the hint counts as an empty field`() {
        // uiautomator reports the hint in text when the field is empty
        val session = FakeSession(Platform.ANDROID) { screen(field(null)) }
        val player = ScriptPlayer(session, elementTimeoutMs = 80, pollMs = 10)
        val method = ScriptPlayer::class.java.getDeclaredMethod("isEmptyField", UiNode::class.java).apply { isAccessible = true }
        assertTrue(method.invoke(player, androidField("Text Field", "Text Field")) as Boolean)
        assertTrue(method.invoke(player, androidField("", null)) as Boolean)
        assertFalse(method.invoke(player, androidField("old", "Text Field")) as Boolean)
    }
}
