package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.inspector.MobilewrightLocators.Derived
import io.github.marcomaes.mobiletestbench.core.inspector.MobilewrightLocators.Kind
import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import io.github.marcomaes.mobiletestbench.core.inspector.WdaSourceParser
import io.github.marcomaes.mobiletestbench.core.pageobject.AppiumStrategy
import io.github.marcomaes.mobiletestbench.core.pageobject.PageLocator
import io.github.marcomaes.mobiletestbench.core.pageobject.PageObjectLanguage
import io.github.marcomaes.mobiletestbench.core.recording.AppiumSteps
import io.github.marcomaes.mobiletestbench.core.recording.Assertion
import io.github.marcomaes.mobiletestbench.core.recording.ReplayOptions
import io.github.marcomaes.mobiletestbench.core.recording.ScriptPlayer
import io.github.marcomaes.mobiletestbench.core.recording.ScriptPlayer.State
import io.github.marcomaes.mobiletestbench.core.recording.ScriptRecorder
import io.github.marcomaes.mobiletestbench.core.recording.ScriptRecorder.Target
import io.github.marcomaes.mobiletestbench.core.recording.WaitState
import io.github.marcomaes.mobiletestbench.core.session.DeviceSession
import io.github.marcomaes.mobiletestbench.core.session.FrameListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayControlsTest {

    private val ios: UiNode = WdaSourceParser.parse(
        javaClass.getResourceAsStream("/fixtures/ios_login.xml")!!.readBytes().toString(Charsets.UTF_8),
    )
    private val empty = UiNode(Platform.IOS, "XCUIElementTypeApplication", emptyMap(), null)

    private class Fake(private val screen: () -> UiNode) : DeviceSession {
        val actions = mutableListOf<String>()
        override val device = Device("sim", "Sim", Platform.IOS, DeviceKind.SIMULATOR)
        override val transport = "fake"
        override val supportsInput = true
        override fun start(listener: FrameListener) {}
        override fun logicalSize() = Size(400, 800)
        override fun tap(x: Int, y: Int) { actions += "tap $x,$y" }
        override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) { actions += "swipe" }
        override fun press(key: DeviceKey) { actions += "press $key" }
        override fun typeText(text: String) { actions += "type $text" }
        override fun pressKeys(keys: List<String>) { actions += "keys" }
        override fun hierarchy() = screen()
        override fun close() {}
    }

    private fun steps(block: ScriptRecorder.() -> Unit) = ScriptRecorder(Platform.IOS).apply(block).steps.toList()

    // ---- ReplayOptions

    @Test
    fun `settings are clamped and timeouts resolved`() {
        assertEquals(1000L, ReplayOptions.timeoutMs(0))
        assertEquals(300_000L, ReplayOptions.timeoutMs(999))
        assertEquals(10_000L, ReplayOptions.timeoutMs(10))
        assertEquals(0L, ReplayOptions.delayMs(-5))
        assertEquals(10_000L, ReplayOptions.delayMs(50_000))
        assertEquals(30_000L, ReplayOptions.resolveTimeoutMs(30_000, 10_000))
        assertEquals(10_000L, ReplayOptions.resolveTimeoutMs(null, 10_000))
        assertEquals(10_000L, ReplayOptions.resolveTimeoutMs(0, 10_000))
        assertEquals(30, ReplayOptions.parseStepSeconds(" 30 "))
        assertNull(ReplayOptions.parseStepSeconds("0"))
        assertNull(ReplayOptions.parseStepSeconds("abc"))
        assertNull(ReplayOptions.parseStepSeconds("3601"))
        assertEquals(2, ReplayOptions.wholeSeconds(1500))
        assertEquals("10 s", ReplayOptions.format(10_000))
        assertEquals("0.5 s", ReplayOptions.format(500))
    }

    @Test
    fun `replay ranges from the selection`() {
        assertEquals(2..5, ReplayOptions.fromSelected(listOf(4, 2), 6))
        assertEquals(2..4, ReplayOptions.selectedRange(listOf(4, 2), 6))
        assertEquals(3..3, ReplayOptions.selectedRange(listOf(3), 6))
        assertNull(ReplayOptions.fromSelected(emptyList(), 6))
        assertNull(ReplayOptions.selectedRange(listOf(9), 6))
        assertEquals(0..5, ReplayOptions.fromSelected(listOf(-1, 0), 6))
    }

    @Test
    fun `only waits and expects take a timeout`() {
        val t = Target(Derived(Kind.TEST_ID, "x"))
        val wait = ScriptRecorder.WaitFor(t, WaitState.VISIBLE)
        assertTrue(ReplayOptions.supportsTimeout(wait))
        assertTrue(ReplayOptions.supportsTimeout(ScriptRecorder.Expect(t, Assertion.Visible)))
        assertTrue(ReplayOptions.supportsTimeout(ScriptRecorder.ExpectCount(t, 2)))
        assertFalse(ReplayOptions.supportsTimeout(ScriptRecorder.Tap(t)))
        assertFalse(ReplayOptions.supportsTimeout(ScriptRecorder.ScrollUntilVisible(t, "up", 5)))
        assertEquals(5000L, ReplayOptions.timeoutOf(ReplayOptions.withTimeout(wait, 5000)))
        assertNull(ReplayOptions.timeoutOf(ReplayOptions.withTimeout(ReplayOptions.withTimeout(wait, 5000), null)))
        val tap = ScriptRecorder.Tap(t)
        assertEquals(tap, ReplayOptions.withTimeout(tap, 5000))
    }

    @Test
    fun `recorder sets and removes a step timeout`() {
        val r = ScriptRecorder(Platform.IOS)
        val t = Target(Derived(Kind.TEST_ID, "x"))
        r.add(t, Assertion.Visible)
        r.tap(ios, 100, 220)
        assertTrue(r.setTimeout(0, 30_000))
        assertFalse(r.setTimeout(1, 30_000))
        assertFalse(r.setTimeout(7, 30_000))
        assertEquals("await expect(screen.getByTestId('x')).toBeVisible({ timeout: 30000 });", r.steps[0].code())
        assertTrue(r.setTimeout(0, null))
        assertEquals("await expect(screen.getByTestId('x')).toBeVisible();", r.steps[0].code())
    }

    // ---- Mobilewright code

    @Test
    fun `mobilewright code carries the timeout option`() {
        val t = Target(Derived(Kind.TEST_ID, "x"))
        assertEquals("await screen.getByTestId('x').waitFor({ state: 'visible', timeout: 30000 });", ScriptRecorder.WaitFor(t, WaitState.VISIBLE, 30_000).code())
        assertEquals("await screen.getByTestId('x').waitFor({ state: 'hidden' });", ScriptRecorder.WaitFor(t, WaitState.HIDDEN).code())
        assertEquals("await expect(screen.getByTestId('x')).toHaveText('Hi', { timeout: 5000 });", ScriptRecorder.Expect(t, Assertion.HasText("Hi"), 5000).code())
        assertEquals("await expect(screen.getByTestId('x')).toHaveCount(3, { timeout: 5000 });", ScriptRecorder.ExpectCount(t, 3, 5000).code())
        assertEquals("await expect(screen.getByTestId('x')).toHaveCount(3);", ScriptRecorder.ExpectCount(t, 3).code())
    }

    // ---- Appium code

    private val byId = Target(Derived(Kind.TEST_ID, "login"), appium = PageLocator.Appium(AppiumStrategy.ID, "login"))
    private fun one(step: ScriptRecorder.Step, l: PageObjectLanguage) = AppiumSteps.lines(step, l, Platform.ANDROID).single()

    @Test
    fun `appium waits use a dedicated wait for a step with a timeout`() {
        val w = ScriptRecorder.WaitFor(byId, WaitState.VISIBLE, 30_000)
        assertEquals("""new WebDriverWait(driver, Duration.ofSeconds(30)).until(ExpectedConditions.visibilityOfElementLocated(By.id("login")));""", one(w, PageObjectLanguage.APPIUM_JAVA))
        assertEquals("""WebDriverWait(driver, Duration.ofSeconds(30)).until(ExpectedConditions.visibilityOfElementLocated(By.id("login")))""", one(w, PageObjectLanguage.APPIUM_KOTLIN))
        assertEquals("""WebDriverWait(driver, 30).until(EC.visibility_of_element_located((AppiumBy.ID, "login")))""", one(w, PageObjectLanguage.APPIUM_PYTHON))
        assertEquals("""new WebDriverWait(driver, TimeSpan.FromSeconds(30)).Until(d => d.FindElements(By.Id("login")).FirstOrDefault(e => e.Displayed));""", one(w, PageObjectLanguage.APPIUM_CSHARP))
        val e = ScriptRecorder.WaitFor(byId, WaitState.ENABLED, 5000)
        assertEquals("""WebDriverWait(driver, Duration.ofSeconds(5)).until { it.findElement(By.id("login")).isEnabled }""", one(e, PageObjectLanguage.APPIUM_KOTLIN))
    }

    @Test
    fun `appium expect with a timeout and the default wait stay apart`() {
        val ex = ScriptRecorder.Expect(byId, Assertion.HasText("Hi"), 20_000)
        assertEquals(
            """assertEquals("Hi", new WebDriverWait(driver, Duration.ofSeconds(20)).until(ExpectedConditions.visibilityOfElementLocated(By.id("login"))).getText());""",
            one(ex, PageObjectLanguage.APPIUM_JAVA),
        )
        val code = AppiumSteps.render(listOf(ex, ScriptRecorder.WaitFor(byId, WaitState.VISIBLE)), PageObjectLanguage.APPIUM_JAVA, Platform.ANDROID)
        assertTrue(code.lines().last().startsWith("wait.until("))
        assertTrue(code.lines().first().contains("WebDriverWait wait = "))
        // without any default wait the declaration is not needed
        val only = AppiumSteps.render(listOf(ex), PageObjectLanguage.APPIUM_JAVA, Platform.ANDROID)
        assertFalse(only.contains("WebDriverWait wait = "))
    }

    // ---- ScriptPlayer

    @Test
    fun `a step timeout overrides the default and a step without one uses it`() {
        val t = Target(Derived(Kind.TEST_ID, "missing"))
        val own = steps { }.let { listOf(ScriptRecorder.WaitFor(t, WaitState.VISIBLE, 60)) }
        val started = System.currentTimeMillis()
        var message: String? = null
        val ok = ScriptPlayer(Fake { ios }, elementTimeoutMs = 20_000, pollMs = 10).run(own, { _, s, m -> if (s == State.FAILED) message = m })
        assertFalse(ok)
        assertTrue(System.currentTimeMillis() - started < 5000)
        assertTrue(message!!.contains("0.1 s") || message!!.contains("0.0 s"))
        // no own timeout: the default decides
        val dflt = listOf(ScriptRecorder.Expect(t, Assertion.Visible))
        val t0 = System.currentTimeMillis()
        assertFalse(ScriptPlayer(Fake { ios }, elementTimeoutMs = 150, pollMs = 10).run(dflt, { _, _, _ -> }))
        assertTrue(System.currentTimeMillis() - t0 >= 140)
        // a longer own timeout beats a short default: the element appears after some polls
        var calls = 0
        val late = listOf(ScriptRecorder.WaitFor(Target(Derived(Kind.TEST_ID, "email-input")), WaitState.VISIBLE, 5000))
        assertTrue(ScriptPlayer(Fake { if (++calls < 6) empty else ios }, elementTimeoutMs = 20, pollMs = 10).run(late, { _, _, _ -> }))
    }

    private val tapSteps get() = steps {
        tap(ios, 100, 220)   // email
        tap(ios, 100, 320)   // sign in
        swipe(200, 600, 200, 200)
        key(DeviceKey.HOME)
    }

    @Test
    fun `replays only the range and reports absolute indices`() {
        val s = tapSteps
        val session = Fake { ios }
        val events = mutableListOf<String>()
        val ok = ScriptPlayer(session, pollMs = 10).run(s, { i, st, _ -> events += "$i:$st" }, range = 1..2)
        assertTrue(ok)
        assertEquals(listOf("tap 195,322", "swipe"), session.actions)
        assertEquals(listOf("1:RUNNING", "1:PASSED", "2:RUNNING", "2:PASSED"), events)
    }

    @Test
    fun `replay from a step stops at the first failure and ignores a range beyond the list`() {
        val s = tapSteps
        val failing = Fake { empty }
        val events = mutableListOf<String>()
        assertTrue(ScriptPlayer(failing, elementTimeoutMs = 30, pollMs = 10).run(s, { i, st, _ -> events += "$i:$st" }, range = 2..9))
        // step 2 is a swipe and does not need an element, step 3 is HOME: both pass; nothing out of range is reported
        assertEquals(listOf("2:RUNNING", "2:PASSED", "3:RUNNING", "3:PASSED"), events)
        val failFrom1 = mutableListOf<String>()
        assertFalse(ScriptPlayer(Fake { empty }, elementTimeoutMs = 30, pollMs = 10).run(s, { i, st, _ -> failFrom1 += "$i:$st" }, range = 1..3))
        assertEquals(listOf("1:RUNNING", "1:FAILED"), failFrom1)
        assertTrue(ScriptPlayer(Fake { ios }, pollMs = 10).run(s, { _, _, _ -> }, range = 7..9))
    }

    @Test
    fun `the delay is waited between steps that run but not before the first`() {
        val pauses = mutableListOf<Long>()
        val session = Fake { ios }
        val s = steps { tap(ios, 100, 220); type("x"); tap(ios, 100, 320); key(DeviceKey.HOME) } // type("x") after a tap becomes fill: 3 steps
        val ok = ScriptPlayer(session, pollMs = 10, stepDelayMs = 250, pause = { pauses += it }).run(s, { _, _, _ -> })
        assertTrue(ok)
        // 3 steps: 2 gaps of 250 ms each, in slices of at most 100 ms
        assertEquals(2 * 250L, pauses.sum())
        assertTrue(pauses.all { it <= 100 })
        val none = mutableListOf<Long>()
        ScriptPlayer(Fake { ios }, pollMs = 10, stepDelayMs = 0, pause = { none += it }).run(s, { _, _, _ -> })
        assertTrue(none.isEmpty())
    }

    @Test
    fun `cancelling during the delay stops the run`() {
        val s = tapSteps
        val session = Fake { ios }
        var cancel = false
        val events = mutableListOf<String>()
        val ok = ScriptPlayer(session, pollMs = 10, stepDelayMs = 300, pause = { cancel = true }).run(s, { i, st, _ -> events += "$i:$st" }, cancelled = { cancel })
        assertFalse(ok)
        assertEquals(listOf("tap 195,222"), session.actions)
        assertEquals(listOf("0:RUNNING", "0:PASSED"), events)
    }
}
