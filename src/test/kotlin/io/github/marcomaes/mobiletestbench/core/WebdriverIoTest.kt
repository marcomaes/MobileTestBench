package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.inspector.LocatorSuggestion
import io.github.marcomaes.mobiletestbench.core.inspector.MobilewrightLocators.Derived
import io.github.marcomaes.mobiletestbench.core.inspector.MobilewrightLocators.Kind
import io.github.marcomaes.mobiletestbench.core.pageobject.AppiumStrategy
import io.github.marcomaes.mobiletestbench.core.pageobject.LocatorCode
import io.github.marcomaes.mobiletestbench.core.pageobject.LocatorLanguage
import io.github.marcomaes.mobiletestbench.core.pageobject.PageLocator
import io.github.marcomaes.mobiletestbench.core.pageobject.WebdriverIo
import io.github.marcomaes.mobiletestbench.core.recording.Assertion
import io.github.marcomaes.mobiletestbench.core.recording.RecorderOutput
import io.github.marcomaes.mobiletestbench.core.recording.ScriptRecorder
import io.github.marcomaes.mobiletestbench.core.recording.ScriptRecorder.Target
import io.github.marcomaes.mobiletestbench.core.recording.WaitState
import io.github.marcomaes.mobiletestbench.core.recording.WebdriverIoSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebdriverIoTest {

    private fun row(strategy: String, value: String) = LocatorSuggestion("Appium", strategy, value, 1)
    private fun l(s: AppiumStrategy, v: String) = PageLocator.Appium(s, v)
    private fun target(s: AppiumStrategy = AppiumStrategy.ACCESSIBILITY_ID, v: String = "login") =
        Target(Derived(Kind.TEST_ID, v), appium = l(s, v))

    private fun lines(step: ScriptRecorder.Step, p: Platform = Platform.ANDROID) = WebdriverIoSteps.lines(step, p)
    private fun one(step: ScriptRecorder.Step, p: Platform = Platform.ANDROID) = lines(step, p).single()

    // ---- locators

    @Test
    fun `selectors per strategy`() = assertEquals(
        listOf(
            "\$('id=com.example:id/email')",
            "\$('~Log in')",
            "\$('android=new UiSelector().text(\"x\")')",
            "\$('-ios predicate string:name == \\'a\\'')",
            "\$('-ios class chain:**/XCUIElementTypeButton')",
            "\$('//Card//Button')",
        ),
        listOf(
            l(AppiumStrategy.ID, "com.example:id/email"),
            l(AppiumStrategy.ACCESSIBILITY_ID, "Log in"),
            l(AppiumStrategy.ANDROID_UIAUTOMATOR, "new UiSelector().text(\"x\")"),
            l(AppiumStrategy.IOS_PREDICATE, "name == 'a'"),
            l(AppiumStrategy.IOS_CLASS_CHAIN, "**/XCUIElementTypeButton"),
            l(AppiumStrategy.XPATH, "//Card//Button"),
        ).map { WebdriverIo.element(it) },
    )

    @Test
    fun `quote escapes backslash quote and newline`() = assertEquals("'a\\\\b\\'c\\nd'", WebdriverIo.quote("a\\b'c\nd"))

    @Test
    fun `locator language renders appium rows only`() {
        val language = LocatorLanguage.APPIUM_WEBDRIVERIO
        assertEquals("\$('~login')", LocatorCode.render(row("accessibility id", "login"), language))
        assertEquals("\$('//a')", LocatorCode.render(row("xpath (in parent)", "//a"), language))
        assertNull(LocatorCode.render(LocatorSuggestion("Mobilewright", "testId", "screen.getByTestId('x')", 1), language))
    }

    @Test
    fun `locator language note and menu label`() {
        val mw = LocatorSuggestion("Mobilewright", "testId", "screen.getByTestId('x')", 1)
        val text = LocatorCode.textFor(mw, LocatorLanguage.APPIUM_WEBDRIVERIO)
        assertEquals(mw.expression, text.text)
        assertTrue(text.note!!.contains("Appium JavaScript (WebdriverIO)"))
        assertEquals("Copy Appium JavaScript (WebdriverIO) id", LocatorCode.menuLabel(row("id", "a"), LocatorLanguage.APPIUM_WEBDRIVERIO))
    }

    @Test
    fun `old persisted names still load`() {
        assertEquals(LocatorLanguage.APPIUM_PYTHON, LocatorLanguage.fromName("APPIUM_PYTHON"))
        assertEquals(LocatorLanguage.APPIUM_WEBDRIVERIO, LocatorLanguage.fromName("APPIUM_WEBDRIVERIO"))
        assertEquals(RecorderOutput.APPIUM_CSHARP, RecorderOutput.fromName("APPIUM_CSHARP"))
        assertEquals(RecorderOutput.APPIUM_WEBDRIVERIO, RecorderOutput.fromName("APPIUM_WEBDRIVERIO"))
        assertEquals(RecorderOutput.MOBILEWRIGHT, RecorderOutput.fromName("unknown"))
    }

    // ---- steps

    @Test
    fun `tap and fill`() {
        assertEquals("await \$('~login').click();", one(ScriptRecorder.Tap(target())))
        assertEquals("await \$('~login').setValue('a\\'b');", one(ScriptRecorder.Fill(target(), "a'b")))
    }

    @Test
    fun `unverified tap gets a note`() =
        assertTrue(one(ScriptRecorder.Tap(target(), unverified = true)).endsWith("// check: matched against the screen before the previous action"))

    @Test
    fun `step without appium locator is a comment`() {
        val none = Target(Derived(Kind.TEXT, "Hi"))
        assertTrue(one(ScriptRecorder.Tap(none)).startsWith("// tap "))
        assertTrue(one(ScriptRecorder.Expect(none, Assertion.Visible)).startsWith("// expect "))
    }

    @Test
    fun `long press and double tap per platform`() {
        assertEquals(
            "await driver.execute('mobile: longClickGesture', { elementId: (await \$('~login')).elementId, duration: 700 });",
            one(ScriptRecorder.LongPress(target(), 700)),
        )
        assertEquals(
            "await driver.execute('mobile: touchAndHold', { elementId: (await \$('~login')).elementId, duration: 0.7 });",
            one(ScriptRecorder.LongPress(target(), 700), Platform.IOS),
        )
        assertEquals(
            "await driver.execute('mobile: doubleClickGesture', { elementId: (await \$('~login')).elementId });",
            one(ScriptRecorder.DoubleTap(target())),
        )
        assertEquals(
            "await driver.execute('mobile: doubleTap', { elementId: (await \$('~login')).elementId });",
            one(ScriptRecorder.DoubleTap(target()), Platform.IOS),
        )
    }

    @Test
    fun `swipe per platform`() {
        assertEquals(
            "await driver.execute('mobile: swipeGesture', { left: 0, top: 0, width: (await driver.getWindowSize()).width, height: (await driver.getWindowSize()).height, direction: 'up', percent: 0.75 });",
            one(ScriptRecorder.Swipe("up")),
        )
        assertEquals("await driver.execute('mobile: swipe', { direction: 'up' });", one(ScriptRecorder.Swipe("up"), Platform.IOS))
    }

    @Test
    fun `scroll until visible loops`() {
        val out = lines(ScriptRecorder.ScrollUntilVisible(target(), "up", 5), Platform.IOS)
        assertEquals(
            listOf(
                "for (let i = 0; i < 5 && !(await \$('~login').isDisplayed()); i++) {",
                "    await driver.execute('mobile: swipe', { direction: 'up' });",
                "}",
            ),
            out,
        )
    }

    @Test
    fun `assertions`() {
        fun e(a: Assertion, t: Long? = null, p: Platform = Platform.ANDROID) = one(ScriptRecorder.Expect(target(), a, t), p)
        assertEquals("await expect(\$('~login')).toBeDisplayed();", e(Assertion.Visible))
        assertEquals("await expect(\$('~login')).toBeDisplayed({ wait: 5000 });", e(Assertion.Visible, 5000))
        assertEquals("await expect(\$('~login')).not.toBeDisplayed();", e(Assertion.Hidden))
        assertEquals("await expect(\$('~login')).toHaveText('Hi');", e(Assertion.HasText("Hi")))
        assertEquals("await expect(\$('~login')).toHaveText('Hi', { wait: 2000 });", e(Assertion.HasText("Hi"), 2000))
        assertEquals("await expect(\$('~login')).toHaveText(expect.stringContaining('Hi'));", e(Assertion.ContainsText("Hi")))
        assertEquals("await expect(\$('~login')).toHaveText('v');", e(Assertion.HasValue("v")))
        assertEquals("await expect(\$('~login')).toHaveAttribute('value', 'v');", e(Assertion.HasValue("v"), p = Platform.IOS))
        assertEquals("await expect(\$('~login')).toBeEnabled();", e(Assertion.Enabled(true)))
        assertEquals("await expect(\$('~login')).not.toBeEnabled({ wait: 1000 });", e(Assertion.Enabled(false), 1000))
        assertEquals("await expect(\$('~login')).toHaveAttribute('checked', 'true');", e(Assertion.Checked))
        assertEquals("await expect(\$('~login')).toHaveAttribute('value', '1');", e(Assertion.Checked, p = Platform.IOS))
    }

    @Test
    fun `waits`() {
        fun w(s: WaitState, t: Long? = null) = one(ScriptRecorder.WaitFor(target(), s, t))
        assertEquals("await \$('~login').waitForDisplayed({ timeout: 10000 });", w(WaitState.VISIBLE))
        assertEquals("await \$('~login').waitForDisplayed({ reverse: true, timeout: 3000 });", w(WaitState.HIDDEN, 3000))
        assertEquals("await \$('~login').waitForEnabled({ timeout: 10000 });", w(WaitState.ENABLED))
        assertEquals("await \$('~login').waitForEnabled({ reverse: true, timeout: 10000 });", w(WaitState.DISABLED))
    }

    @Test
    fun `buttons back count and notes`() {
        assertEquals("await driver.execute('mobile: pressKey', { keycode: 3 });", one(ScriptRecorder.Button("HOME")))
        assertEquals("await driver.execute('mobile: pressButton', { name: 'home' });", one(ScriptRecorder.Button("HOME"), Platform.IOS))
        assertEquals("// press button VOLUME_UP: no Appium form", one(ScriptRecorder.Button("VOLUME_UP")))
        assertEquals("await driver.back();", one(ScriptRecorder.GoBack))
        assertTrue(one(ScriptRecorder.ExpectCount(target(), 3)).startsWith("// expect count 3 of "))
        assertEquals("// typed 'x': tap the field first so it can be recorded as fill", one(ScriptRecorder.UnfocusedText("x")))
        assertEquals("// hello", one(ScriptRecorder.Note("hello")))
    }

    // ---- render and output

    @Test
    fun `render adds the import comment only when there is code`() {
        val out = WebdriverIoSteps.render(listOf(ScriptRecorder.Tap(target())), Platform.ANDROID)
        assertEquals("// import { \$, driver, expect } from '@wdio/globals';\nawait \$('~login').click();", out)
        assertEquals("// hello", WebdriverIoSteps.render(listOf(ScriptRecorder.Note("hello")), Platform.ANDROID))
    }

    @Test
    fun `recorder output webdriverio`() {
        val out = RecorderOutput.APPIUM_WEBDRIVERIO
        val step = ScriptRecorder.Tap(target())
        assertEquals("await \$('~login').click();", out.summary(step, Platform.ANDROID))
        assertTrue(out.render(listOf(step), Platform.ANDROID).endsWith("await \$('~login').click();"))
    }
}
