package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.android.AdbClient
import io.github.marcomaes.mobiletestbench.core.android.AndroidSession
import io.github.marcomaes.mobiletestbench.core.android.EmulatorLink
import io.github.marcomaes.mobiletestbench.core.exec.CommandResult
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import io.github.marcomaes.mobiletestbench.core.input.Gestures
import io.github.marcomaes.mobiletestbench.core.input.Gestures.Release
import io.github.marcomaes.mobiletestbench.core.inspector.WdaSourceParser
import io.github.marcomaes.mobiletestbench.core.ios.IosMobilecliSession
import io.github.marcomaes.mobiletestbench.core.ios.MobilecliClient
import io.github.marcomaes.mobiletestbench.core.ios.WdaPayloads
import io.github.marcomaes.mobiletestbench.core.recording.ScriptRecorder
import io.github.marcomaes.mobiletestbench.core.session.FrameListener
import io.github.marcomaes.mobiletestbench.core.session.UnsupportedOperationOnDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class GesturesTest {

    private val ios = WdaSourceParser.parse(
        javaClass.getResourceAsStream("/fixtures/ios_login.xml")!!.readBytes().toString(Charsets.UTF_8),
    )

    // region mouse rules

    @Test
    fun `a release after a long press does nothing`() {
        assertEquals(Release.CONSUMED, Gestures.onRelease(longPressFired = true, moved = false, alt = false, clickCount = 1))
        assertEquals(Release.CONSUMED, Gestures.onRelease(longPressFired = true, moved = true, alt = true, clickCount = 2))
    }

    @Test
    fun `click counts and movement pick the gesture`() {
        assertEquals(Release.TAP, Gestures.onRelease(false, moved = false, alt = false, clickCount = 1))
        assertEquals(Release.DOUBLE_TAP, Gestures.onRelease(false, moved = false, alt = false, clickCount = 2))
        assertEquals(Release.TAP, Gestures.onRelease(false, moved = false, alt = false, clickCount = 3))
        assertEquals(Release.SWIPE, Gestures.onRelease(false, moved = true, alt = false, clickCount = 2))
        assertEquals(Release.HOLD_DRAG, Gestures.onRelease(false, moved = true, alt = true, clickCount = 1))
        assertEquals(Release.TAP, Gestures.onRelease(false, moved = false, alt = true, clickCount = 1))
    }

    @Test
    fun `slop is a distance`() {
        assertFalse(Gestures.movedBeyondSlop(3.0, 4.0, 6.0))
        assertTrue(Gestures.movedBeyondSlop(6.0, 8.0, 10.0))
    }

    // endregion

    // region recorder

    @Test
    fun `long press and double tap are recorded on the same locator a tap would use`() {
        val r = ScriptRecorder(Platform.IOS)
        r.longPress(ios, 100, 220, 700)
        r.doubleTap(ios, 100, 320)
        assertEquals("await screen.getByTestId('email-input').longPress({ duration: 700 });", r.steps[0].code())
        assertTrue(r.steps[0].code().endsWith(".longPress({ duration: 700 });"))
        assertTrue(r.steps[1].code().endsWith(".doubleTap();"))
    }

    @Test
    fun `the tap sent before a double click is folded into the double tap`() {
        val r = ScriptRecorder(Platform.IOS)
        r.tap(ios, 100, 220)
        r.doubleTap(ios, 100, 220)
        assertEquals(1, r.steps.size)
        assertEquals("await screen.getByTestId('email-input').doubleTap();", r.code())
        r.tap(ios, 100, 320)
        r.doubleTap(ios, 100, 220)
        assertEquals(3, r.steps.size)
    }

    @Test
    fun `a gesture where nothing is found or the hierarchy is missing is a comment`() {
        val r = ScriptRecorder(Platform.IOS)
        r.longPress(ios, 9999, 9999, 700)
        r.tapWithoutHierarchy(5, 6, "no agent", what = "double tap")
        r.dragWithHold(1, 2, 3, 4, 700)
        assertEquals("// long press at (9999, 9999): no element found there", r.steps[0].code())
        assertEquals("// double tap at (5, 6): hierarchy unavailable (no agent)", r.steps[1].code())
        assertTrue(r.steps[2].code().startsWith("// drag with hold (700 ms) from (1, 2) to (3, 4)"))
    }

    // endregion

    // region sessions

    private class RecordingRunner : CommandRunner(emptyMap()) {
        val commands = mutableListOf<List<String>>()
        override fun run(command: List<String>, timeout: Duration): CommandResult {
            commands += command
            return CommandResult(0, ByteArray(0), "")
        }
    }

    private class LinkStub : EmulatorLink {
        val calls = mutableListOf<String>()
        override fun startStreaming(listener: FrameListener) {}
        override fun tap(x: Int, y: Int) { calls += "tap" }
        override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) { calls += "swipe" }
        override fun longPress(x: Int, y: Int, durationMs: Long) { calls += "longPress $x,$y,$durationMs" }
        override fun doubleTap(x: Int, y: Int) { calls += "doubleTap $x,$y" }
        override fun dragWithHold(fromX: Int, fromY: Int, toX: Int, toY: Int, holdMs: Long, durationMs: Long) { calls += "drag $fromX,$fromY,$toX,$toY,$holdMs,$durationMs" }
        override fun press(key: DeviceKey) {}
        override fun typeText(text: String) {}
        override fun close() {}
    }

    private val android = Device("emulator-5554", "Pixel", Platform.ANDROID, DeviceKind.EMULATOR)

    @Test
    fun `android over gRPC does all three gestures through the link`() {
        val link = LinkStub()
        val session = AndroidSession(android, AdbClient("adb", RecordingRunner()), link)
        assertTrue(session.supportsDoubleTap && session.supportsHoldDrag)
        session.longPress(1, 2, 700)
        session.doubleTap(3, 4)
        session.dragWithHold(1, 2, 3, 4, 700, 300)
        assertEquals(listOf("longPress 1,2,700", "doubleTap 3,4", "drag 1,2,3,4,700,300"), link.calls)
    }

    @Test
    fun `android over adb long presses with a stationary swipe and refuses the rest`() {
        val runner = RecordingRunner()
        val session = AndroidSession(android, AdbClient("adb", runner), null)
        session.longPress(540, 1000, 700)
        assertEquals(listOf("input", "swipe", "540", "1000", "540", "1000", "700"), runner.commands.single().takeLast(7))
        assertFalse(session.supportsDoubleTap || session.supportsHoldDrag)
        try {
            session.doubleTap(1, 2)
            error("expected UnsupportedOperationOnDevice")
        } catch (e: UnsupportedOperationOnDevice) {
            assertTrue(e.message!!.contains("gRPC"))
        }
        try {
            session.dragWithHold(1, 2, 3, 4)
            error("expected UnsupportedOperationOnDevice")
        } catch (e: UnsupportedOperationOnDevice) {
            assertTrue(e.message!!.contains("gRPC"))
        }
    }

    @Test
    fun `ios via mobilecli long presses with io longpress and has no double tap`() {
        val runner = RecordingRunner()
        val device = Device("F2368465", "iPhone", Platform.IOS, DeviceKind.SIMULATOR)
        val session = IosMobilecliSession(device, MobilecliClient("mobilecli", runner))
        session.longPress(200, 500, 700)
        assertEquals(listOf("mobilecli", "io", "longpress", "--device", "F2368465", "--duration", "700", "200,500"), runner.commands.single())
        assertFalse(session.supportsDoubleTap)
        assertFalse(session.supportsHoldDrag)
        try {
            session.doubleTap(1, 2)
            error("expected UnsupportedOperationOnDevice")
        } catch (e: UnsupportedOperationOnDevice) {
        }
    }

    @Test
    fun `ios simulator without WDA supports neither double tap nor hold drag and refuses them`() {
        val runner = RecordingRunner()
        val device = Device("F2368465", "iPhone", Platform.IOS, DeviceKind.SIMULATOR)
        val session = io.github.marcomaes.mobiletestbench.core.ios.IosSimulatorSession(
            device, io.github.marcomaes.mobiletestbench.core.ios.SimctlClient("xcrun", runner), null, null,
        )
        assertFalse(session.supportsDoubleTap)
        assertFalse(session.supportsHoldDrag)
        try {
            session.doubleTap(1, 2)
            error("expected UnsupportedOperationOnDevice")
        } catch (e: UnsupportedOperationOnDevice) {
            assertTrue(e.message!!.contains("WebDriverAgent"))
        }
    }

    @Test
    fun `the saved mobilecli longpress output is a success envelope`() {
        val text = javaClass.getResourceAsStream("/fixtures/mobilecli-longpress-ok.json")!!.readBytes().toString(Charsets.UTF_8)
        assertFalse(MobilecliJson.isError(text))
    }

    @Test
    fun `the saved rpc error for two presses in one gesture is an error`() {
        val text = javaClass.getResourceAsStream("/fixtures/mobilecli-rpc-gesture-two-presses-error.json")!!.readBytes().toString(Charsets.UTF_8)
        assertTrue(text.contains("error"))
    }

    // endregion

    // region WDA payloads

    private fun actionTypes(payload: Map<String, Any>): List<String> {
        @Suppress("UNCHECKED_CAST")
        val pointer = (payload["actions"] as List<Map<String, Any>>).single()
        @Suppress("UNCHECKED_CAST")
        return (pointer["actions"] as List<Map<String, Any>>).map { a -> (a["type"] as String) + (a["duration"]?.let { ":$it" } ?: "") }
    }

    @Test
    fun `wda long press holds between down and up`() {
        assertEquals(listOf("pointerMove:0", "pointerDown", "pause:700", "pointerUp"), actionTypes(WdaPayloads.longPress(1, 2, 700)))
    }

    @Test
    fun `wda double tap is two touches in one action list`() {
        assertEquals(
            listOf("pointerMove:0", "pointerDown", "pause:40", "pointerUp", "pause:60", "pointerDown", "pause:40", "pointerUp"),
            actionTypes(WdaPayloads.doubleTap(1, 2)),
        )
    }

    @Test
    fun `wda drag with hold pauses before it moves`() {
        assertEquals(
            listOf("pointerMove:0", "pointerDown", "pause:800", "pointerMove:300", "pointerUp"),
            actionTypes(WdaPayloads.dragWithHold(1, 2, 3, 4, 800, 300)),
        )
    }

    // endregion

    @org.junit.Test
    fun longPressSettingIsClamped() {
        assertEquals(700L, Gestures.longPressMs(700))
        assertEquals(300L, Gestures.longPressMs(0))
        assertEquals(300L, Gestures.longPressMs(-5))
        assertEquals(3000L, Gestures.longPressMs(99999))
        assertEquals(450L, Gestures.longPressMs(450))
    }
}
