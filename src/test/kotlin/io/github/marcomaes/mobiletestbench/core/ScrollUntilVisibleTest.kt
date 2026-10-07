package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import io.github.marcomaes.mobiletestbench.core.inspector.WdaSourceParser
import io.github.marcomaes.mobiletestbench.core.recording.ScriptPlayer
import io.github.marcomaes.mobiletestbench.core.recording.ScriptRecorder
import io.github.marcomaes.mobiletestbench.core.recording.ScriptRecorder.ScrollUntilVisible
import io.github.marcomaes.mobiletestbench.core.recording.ScrollUntilVisible as Conversion
import io.github.marcomaes.mobiletestbench.core.session.DeviceSession
import io.github.marcomaes.mobiletestbench.core.session.FrameListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollUntilVisibleTest {

    private fun screen(buttonY: Int, visible: Boolean = true): UiNode = WdaSourceParser.parse(
        """<?xml version="1.0" encoding="UTF-8"?>
        <XCUIElementTypeApplication type="XCUIElementTypeApplication" name="App" x="0" y="0" width="400" height="800">
          <XCUIElementTypeButton type="XCUIElementTypeButton" name="Sign In" label="Sign In" enabled="true" visible="$visible" accessible="true" x="20" y="$buttonY" width="350" height="44" index="1"/>
        </XCUIElementTypeApplication>""",
    )

    private val signIn = screen(300)

    private fun recorder(block: ScriptRecorder.() -> Unit) = ScriptRecorder(Platform.IOS).apply(block)

    @Test
    fun `converts the swipes before an element step and writes a bounded loop`() {
        val r = recorder { swipe(200, 600, 200, 200); swipe(200, 600, 200, 200); tap(signIn, 100, 320) }
        assertNull(r.scrollUntilVisible(listOf(0, 1)))
        assertEquals(2, r.steps.size)
        val scroll = r.steps[0] as ScrollUntilVisible
        assertEquals("up", scroll.direction)
        assertEquals(10, scroll.maxSwipes)
        assertEquals(
            "for (let i = 0; i < 10 && !(await screen.getByRole('button', { name: 'Sign In' }).isVisible()); i++) { await screen.swipe('up'); }",
            scroll.code(),
        )
        assertTrue(r.steps[1] is ScriptRecorder.Tap)
    }

    @Test
    fun `rejects selections that cannot be converted and leaves the steps alone`() {
        val r = recorder { swipe(200, 600, 200, 200); tap(signIn, 100, 320); swipe(200, 200, 200, 600); swipe(200, 200, 200, 600); swipe(0, 300, 300, 300) }
        val before = r.steps.toList()
        assertEquals("Select the swipe steps first", r.scrollUntilVisible(emptyList()))
        assertEquals("Only swipe steps can be turned into a scroll", r.scrollUntilVisible(listOf(0, 1)))
        assertEquals("The selected swipes must be next to each other", r.scrollUntilVisible(listOf(0, 2)))
        assertEquals("The selected swipes go in different directions", r.scrollUntilVisible(listOf(3, 4)))
        assertEquals("There is no step after the swipes to scroll to", r.scrollUntilVisible(listOf(4)))
                assertEquals("The selection is out of date", r.scrollUntilVisible(listOf(9)))
        assertEquals(before, r.steps)
    }

    @Test
    fun `a step that is not about an element after the swipes is rejected`() {
        val steps = listOf(ScriptRecorder.Swipe("up"), ScriptRecorder.Swipe("up"), ScriptRecorder.Button("HOME"))
        assertTrue(Conversion.convert(steps, listOf(0, 1)) is Conversion.Result.Rejected)
    }

    @Test
    fun `many swipes raise the limit above the default`() {
        val steps = List(12) { ScriptRecorder.Swipe("down") } + ScriptRecorder.Tap(ScriptRecorder.targetFor(signIn.walk().first { it.attr("name") == "Sign In" }, signIn)!!)
        val result = Conversion.convert(steps, (0 until 12).toList()) as Conversion.Result.Converted
        assertEquals(12, (result.steps[0] as ScrollUntilVisible).maxSwipes)
    }

    @Test
    fun `on screen means visible with its center inside the screen`() {
        val size = Size(400, 800)
        fun button(n: UiNode) = n.walk().first { it.attr("name") == "Sign In" }
        assertTrue(Conversion.isOnScreen(button(screen(300)), size))
        assertFalse(Conversion.isOnScreen(button(screen(900)), size))
        assertFalse(Conversion.isOnScreen(button(screen(300, visible = false)), size))
        assertFalse(Conversion.isOnScreen(null, size))
        assertFalse(Conversion.isOnScreen(button(screen(790)), size)) // top edge on screen, center (812) below it
        assertTrue(Conversion.isOnScreen(button(screen(770)), size))
    }

    private class FakeSession(private val screens: (swipes: Int) -> UiNode) : DeviceSession {
        val actions = mutableListOf<String>()
        override val device = Device("sim", "Sim", Platform.IOS, DeviceKind.SIMULATOR)
        override val transport = "fake"
        override val supportsInput = true
        override fun start(listener: FrameListener) {}
        override fun logicalSize() = Size(400, 800)
        override fun tap(x: Int, y: Int) { actions += "tap $x,$y" }
        override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) { actions += "swipe $fromX,$fromY,$toX,$toY" }
        override fun longPress(x: Int, y: Int, durationMs: Long) {}
        override fun doubleTap(x: Int, y: Int) {}
        override fun press(key: DeviceKey) {}
        override fun typeText(text: String) {}
        override fun pressKeys(keys: List<String>) {}
        override fun hierarchy() = screens(actions.count { it.startsWith("swipe") })
        override fun close() {}
    }

    private fun converted(maxSwipes: Int = 10): List<ScriptRecorder.Step> {
        val r = recorder { swipe(200, 600, 200, 200); tap(signIn, 100, 320) }
        r.scrollUntilVisible(listOf(0))
        val scroll = r.steps[0] as ScrollUntilVisible
        return listOf(scroll.copy(maxSwipes = maxSwipes), r.steps[1])
    }

    @Test
    fun `replay swipes until the element is on screen and then taps it`() {
        val session = FakeSession { swipes -> screen(1000 - swipes * 300) } // 1000, 700 (on screen after the first swipe)
        val ok = ScriptPlayer(session, elementTimeoutMs = 200, pollMs = 5).run(converted(), { _, _, _ -> })
        assertTrue(ok)
        assertEquals(listOf("swipe 200,560,200,240", "tap 195,722"), session.actions)
    }

    @Test
    fun `replay does not swipe when the element is already visible`() {
        val session = FakeSession { screen(300) }
        assertTrue(ScriptPlayer(session, elementTimeoutMs = 200, pollMs = 5).run(converted(), { _, _, _ -> }))
        assertEquals(listOf("tap 195,322"), session.actions)
    }

    @Test
    fun `replay fails with a clear message after the maximum number of swipes`() {
        val session = FakeSession { screen(5000) }
        var message: String? = null
        val ok = ScriptPlayer(session, elementTimeoutMs = 50, pollMs = 5).run(converted(maxSwipes = 3), { _, s, m -> if (s == ScriptPlayer.State.FAILED) message = m })
        assertFalse(ok)
        assertEquals(3, session.actions.count { it.startsWith("swipe") })
        assertTrue(message!!, message!!.contains("did not become visible after 3 swipes up") && message!!.contains("not on the screen"))
    }

    @Test
    fun `steps that expect the element to be hidden are not scroll targets`() {
        val target = ScriptRecorder.targetFor(signIn.walk().first { it.attr("name") == "Sign In" }, signIn)!!
        val swipe = ScriptRecorder.Swipe("up")
        fun rejected(next: ScriptRecorder.Step) = Conversion.convert(listOf(swipe, next), listOf(0)) is Conversion.Result.Rejected
        assertTrue(rejected(ScriptRecorder.Expect(target, io.github.marcomaes.mobiletestbench.core.recording.Assertion.Hidden)))
        assertTrue(rejected(ScriptRecorder.WaitFor(target, io.github.marcomaes.mobiletestbench.core.recording.WaitState.HIDDEN)))
        assertFalse(rejected(ScriptRecorder.WaitFor(target, io.github.marcomaes.mobiletestbench.core.recording.WaitState.VISIBLE)))
    }

    @Test
    fun `replay does not swipe while the hierarchy cannot be read`() {
        val session = object : DeviceSession by FakeSession({ screen(5000) }) {
            val swipes = mutableListOf<String>()
            override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) { swipes += "swipe" }
            override fun hierarchy(): UiNode = throw IllegalStateException("agent stuck")
        }
        var message: String? = null
        val ok = ScriptPlayer(session, elementTimeoutMs = 50, pollMs = 5).run(converted(), { _, s, m -> if (s == ScriptPlayer.State.FAILED) message = m })
        assertFalse(ok)
        assertTrue(session.swipes.isEmpty())
        assertTrue(message!!, message!!.contains("did not become visible") && message!!.contains("agent stuck"))
    }
}

class ScrollOptionsTest {
    private val signIn = WdaSourceParser.parse(
        """<?xml version="1.0" encoding="UTF-8"?>
        <XCUIElementTypeApplication type="XCUIElementTypeApplication" name="App" x="0" y="0" width="400" height="800">
          <XCUIElementTypeButton type="XCUIElementTypeButton" name="Sign In" label="Sign In" enabled="true" visible="true" accessible="true" x="20" y="300" width="350" height="44" index="1"/>
        </XCUIElementTypeApplication>""",
    )

    private fun recorder() = ScriptRecorder(Platform.IOS).apply {
        swipe(200, 600, 200, 200); tap(signIn, 100, 320); scrollUntilVisible(listOf(0))
    }

    @Test
    fun `changes direction and limit and the code follows`() {
        val r = recorder()
        assertTrue(r.setScrollOptions(0, "down", 25))
        val code = r.steps[0].code()
        assertTrue(code, code.contains("i < 25") && code.contains("swipe('down')"))
    }

    @Test
    fun `rejects bad values and non scroll steps`() {
        val r = recorder()
        assertFalse(r.setScrollOptions(0, "sideways", 5))
        assertFalse(r.setScrollOptions(0, "up", 0))
        assertFalse(r.setScrollOptions(0, "up", 101))
        assertFalse(r.setScrollOptions(1, "up", 5))
        assertEquals(10, (r.steps[0] as ScriptRecorder.ScrollUntilVisible).maxSwipes)
    }

    @Test
    fun `parses limit`() {
        assertEquals(7, Conversion.parseLimit(" 7 "))
        assertNull(Conversion.parseLimit("0"))
        assertNull(Conversion.parseLimit("x"))
    }
}
