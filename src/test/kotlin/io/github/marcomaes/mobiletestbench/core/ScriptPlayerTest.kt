package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import io.github.marcomaes.mobiletestbench.core.inspector.WdaSourceParser
import io.github.marcomaes.mobiletestbench.core.recording.ScriptPlayer
import io.github.marcomaes.mobiletestbench.core.recording.ScriptPlayer.State
import io.github.marcomaes.mobiletestbench.core.recording.ScriptRecorder
import io.github.marcomaes.mobiletestbench.core.session.DeviceSession
import io.github.marcomaes.mobiletestbench.core.session.FrameListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptPlayerTest {

    private val ios: UiNode = WdaSourceParser.parse(
        javaClass.getResourceAsStream("/fixtures/ios_login.xml")!!.readBytes().toString(Charsets.UTF_8),
    )

    private class FakeSession(private val size: Size? = Size(400, 800), private val screens: () -> UiNode) : DeviceSession {
        val actions = mutableListOf<String>()
        override val device = Device("sim", "Sim", Platform.IOS, DeviceKind.SIMULATOR)
        override val transport = "fake"
        override val supportsInput = true
        override fun start(listener: FrameListener) {}
        override fun logicalSize() = size
        override fun tap(x: Int, y: Int) { actions += "tap $x,$y" }
        override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) { actions += "swipe $fromX,$fromY,$toX,$toY" }
        override fun longPress(x: Int, y: Int, durationMs: Long) { actions += "longpress $x,$y,$durationMs" }
        override fun doubleTap(x: Int, y: Int) { actions += "doubletap $x,$y" }
        override fun press(key: DeviceKey) { actions += "press $key" }
        override fun typeText(text: String) { actions += "type $text" }
        override fun pressKeys(keys: List<String>) { actions += "keys ${keys.joinToString(" ")}" }
        override fun hierarchy() = screens()
        override fun close() {}
    }

    private fun recorded(block: ScriptRecorder.() -> Unit): List<ScriptRecorder.Step> =
        ScriptRecorder(Platform.IOS).apply(block).steps.toList()

    @Test
    fun `replays taps, fill, swipes and buttons and reports each step`() {
        val steps = recorded {
            tap(ios, 100, 220)          // email field: 20,200 350x44
            type("hi")
            tap(ios, 100, 320)          // Sign In button: 20,300 350x44
            swipe(200, 600, 200, 200)
            key(DeviceKey.HOME)
        }
        val session = FakeSession { ios }
        val events = mutableListOf<String>()
        val ok = ScriptPlayer(session, elementTimeoutMs = 200, pollMs = 10).run(steps, { i, state, _ -> events += "$i:$state" })
        assertTrue(ok)
        assertEquals(listOf("tap 195,222", "keys cmd+a backspace", "type hi", "tap 195,322", "swipe 200,560,200,240", "press HOME"), session.actions)
        assertEquals(listOf("0:RUNNING", "0:PASSED", "1:RUNNING", "1:PASSED", "2:RUNNING", "2:PASSED", "3:RUNNING", "3:PASSED"), events.take(8))
    }

    @Test
    fun `an element that never appears fails the step and stops the run`() {
        val steps = recorded { tap(ios, 100, 220); tap(ios, 100, 320) }
        val empty = UiNode(Platform.IOS, "XCUIElementTypeApplication", emptyMap(), null)
        val session = FakeSession { empty }
        val events = mutableListOf<Triple<Int, State, String?>>()
        val ok = ScriptPlayer(session, elementTimeoutMs = 60, pollMs = 10).run(steps, { i, s, m -> events += Triple(i, s, m) })
        assertFalse(ok)
        assertTrue(session.actions.isEmpty())
        val failed = events.single { it.second == State.FAILED }
        assertEquals(0, failed.first)
        assertTrue(failed.third!!.contains("email-input") && failed.third!!.contains("not found"))
        assertTrue(events.none { it.first == 1 })
    }

    @Test
    fun `waits for an element that appears later`() {
        val steps = recorded { tap(ios, 100, 220) }
        val empty = UiNode(Platform.IOS, "XCUIElementTypeApplication", emptyMap(), null)
        var calls = 0
        val session = FakeSession { if (++calls < 4) empty else ios }
        assertTrue(ScriptPlayer(session, elementTimeoutMs = 2000, pollMs = 10).run(steps, { _, _, _ -> }))
        assertEquals(listOf("tap 195,222"), session.actions)
    }

    @Test
    fun `comments are skipped and cancelling stops the run`() {
        val steps = recorded { type("x"); tap(ios, 100, 220) }
        val session = FakeSession { ios }
        val states = mutableListOf<State>()
        ScriptPlayer(session, elementTimeoutMs = 100, pollMs = 10).run(steps, { _, s, _ -> states += s }, cancelled = { states.size >= 1 })
        assertEquals(listOf(State.SKIPPED), states)
        assertTrue(session.actions.isEmpty())
    }

    @Test
    fun `a failing hierarchy dump is reported in the failure`() {
        val steps = recorded { tap(ios, 100, 220) }
        val session = FakeSession { error("no XML content") }
        var message: String? = null
        ScriptPlayer(session, elementTimeoutMs = 50, pollMs = 10).run(steps, { _, s, m -> if (s == State.FAILED) message = m })
        assertTrue(message!!.contains("no XML content"))
    }

    @Test
    fun `replays a long press with its duration and a double tap on the element center`() {
        val steps = recorded {
            longPress(ios, 100, 220, 900)
            doubleTap(ios, 100, 320)
            dragWithHold(1, 2, 3, 4, 700)
        }
        val session = FakeSession { ios }
        val states = mutableListOf<State>()
        assertTrue(ScriptPlayer(session, elementTimeoutMs = 100, pollMs = 10).run(steps, { _, s, _ -> states += s }))
        assertEquals(listOf("longpress 195,222,900", "doubletap 195,322"), session.actions)
        assertEquals(State.SKIPPED, states.last())
    }
}
