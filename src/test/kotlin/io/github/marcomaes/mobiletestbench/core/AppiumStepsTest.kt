package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.inspector.MobilewrightLocators.Derived
import io.github.marcomaes.mobiletestbench.core.inspector.MobilewrightLocators.Kind
import io.github.marcomaes.mobiletestbench.core.inspector.WdaSourceParser
import io.github.marcomaes.mobiletestbench.core.pageobject.AppiumStrategy
import io.github.marcomaes.mobiletestbench.core.pageobject.PageLocator
import io.github.marcomaes.mobiletestbench.core.pageobject.PageObjectLanguage
import io.github.marcomaes.mobiletestbench.core.recording.Assertion
import io.github.marcomaes.mobiletestbench.core.recording.AppiumSteps
import io.github.marcomaes.mobiletestbench.core.recording.RecorderOutput
import io.github.marcomaes.mobiletestbench.core.recording.ScriptRecorder
import io.github.marcomaes.mobiletestbench.core.recording.ScriptRecorder.Target
import io.github.marcomaes.mobiletestbench.core.recording.WaitState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppiumStepsTest {

    private val java = PageObjectLanguage.APPIUM_JAVA
    private val kotlin = PageObjectLanguage.APPIUM_KOTLIN
    private val python = PageObjectLanguage.APPIUM_PYTHON
    private val csharp = PageObjectLanguage.APPIUM_CSHARP

    private val byId = Target(Derived(Kind.TEST_ID, "login"), appium = PageLocator.Appium(AppiumStrategy.ID, "login"))
    private val byAcc = Target(Derived(Kind.TEST_ID, "login"), appium = PageLocator.Appium(AppiumStrategy.ACCESSIBILITY_ID, "login"))
    private val none = Target(Derived(Kind.TEXT, "Hi"))

    private fun lines(step: ScriptRecorder.Step, l: PageObjectLanguage, p: Platform = Platform.ANDROID) = AppiumSteps.lines(step, l, p)
    private fun one(step: ScriptRecorder.Step, l: PageObjectLanguage, p: Platform = Platform.ANDROID) = lines(step, l, p).single()

    // ---- Java, one test per step type

    @Test
    fun `java tap`() = assertEquals("""driver.findElement(By.id("login")).click();""", one(ScriptRecorder.Tap(byId), java))

    @Test
    fun `java fill clears first then sends keys`() = assertEquals(
        listOf("""driver.findElement(By.id("login")).clear();""", """driver.findElement(By.id("login")).sendKeys("a\"b");"""),
        lines(ScriptRecorder.Fill(byId, "a\"b"), java),
    )

    @Test
    fun `java long press android and ios`() {
        assertEquals(
            """((JavascriptExecutor) driver).executeScript("mobile: longClickGesture", Map.of("elementId", ((RemoteWebElement) driver.findElement(By.id("login"))).getId(), "duration", 700));""",
            one(ScriptRecorder.LongPress(byId, 700), java),
        )
        assertEquals(
            """((JavascriptExecutor) driver).executeScript("mobile: touchAndHold", Map.of("elementId", ((RemoteWebElement) driver.findElement(By.id("login"))).getId(), "duration", 0.7));""",
            one(ScriptRecorder.LongPress(byId, 700), java, Platform.IOS),
        )
    }

    @Test
    fun `java double tap android and ios`() {
        assertEquals(
            """((JavascriptExecutor) driver).executeScript("mobile: doubleClickGesture", Map.of("elementId", ((RemoteWebElement) driver.findElement(By.id("login"))).getId()));""",
            one(ScriptRecorder.DoubleTap(byId), java),
        )
        assertEquals(
            """((JavascriptExecutor) driver).executeScript("mobile: doubleTap", Map.of("elementId", ((RemoteWebElement) driver.findElement(By.id("login"))).getId()));""",
            one(ScriptRecorder.DoubleTap(byId), java, Platform.IOS),
        )
    }

    @Test
    fun `java swipe android and ios`() {
        assertEquals(
            """((JavascriptExecutor) driver).executeScript("mobile: swipeGesture", Map.of("left", 0, "top", 0, "width", driver.manage().window().getSize().getWidth(), "height", driver.manage().window().getSize().getHeight(), "direction", "up", "percent", 0.75));""",
            one(ScriptRecorder.Swipe("up"), java),
        )
        assertEquals(
            """((JavascriptExecutor) driver).executeScript("mobile: swipe", Map.of("direction", "left"));""",
            one(ScriptRecorder.Swipe("left"), java, Platform.IOS),
        )
    }

    @Test
    fun `java scroll until visible is a bounded loop`() {
        val l = lines(ScriptRecorder.ScrollUntilVisible(byAcc, "up", 10), java, Platform.IOS)
        assertEquals("""for (int i = 0; i < 10 && driver.findElements(AppiumBy.accessibilityId("login")).stream().noneMatch(WebElement::isDisplayed); i++) {""", l[0])
        assertEquals("""    ((JavascriptExecutor) driver).executeScript("mobile: swipe", Map.of("direction", "up"));""", l[1])
        assertEquals("}", l[2])
    }

    @Test
    fun `java visible and hidden use expected conditions`() {
        assertEquals("""wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("login")));""", one(ScriptRecorder.Expect(byId, Assertion.Visible), java))
        assertEquals("""wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("login")));""", one(ScriptRecorder.Expect(byId, Assertion.Hidden), java))
    }

    @Test
    fun `java text contains value enabled checked assertions`() {
        val el = """wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("login")))"""
        assertEquals("""assertEquals("Hi", $el.getText());""", one(ScriptRecorder.Expect(byId, Assertion.HasText("Hi")), java))
        assertEquals("""assertTrue($el.getText().contains("Hi"));""", one(ScriptRecorder.Expect(byId, Assertion.ContainsText("Hi")), java))
        assertEquals("""assertEquals("x", $el.getText());""", one(ScriptRecorder.Expect(byId, Assertion.HasValue("x")), java))
        assertEquals("""assertEquals("x", $el.getAttribute("value"));""", one(ScriptRecorder.Expect(byId, Assertion.HasValue("x")), java, Platform.IOS))
        assertEquals("""assertTrue($el.isEnabled());""", one(ScriptRecorder.Expect(byId, Assertion.Enabled(true)), java))
        assertEquals("""assertFalse($el.isEnabled());""", one(ScriptRecorder.Expect(byId, Assertion.Enabled(false)), java))
        assertEquals("""assertEquals("true", $el.getAttribute("checked"));""", one(ScriptRecorder.Expect(byId, Assertion.Checked), java))
        assertEquals("""assertEquals("1", $el.getAttribute("value"));""", one(ScriptRecorder.Expect(byId, Assertion.Checked), java, Platform.IOS))
    }

    @Test
    fun `java waits`() {
        assertEquals("""wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("login")));""", one(ScriptRecorder.WaitFor(byId, WaitState.VISIBLE), java))
        assertEquals("""wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("login")));""", one(ScriptRecorder.WaitFor(byId, WaitState.HIDDEN), java))
        assertEquals("""wait.until(d -> d.findElement(By.id("login")).isEnabled());""", one(ScriptRecorder.WaitFor(byId, WaitState.ENABLED), java))
        assertEquals("""wait.until(d -> !d.findElement(By.id("login")).isEnabled());""", one(ScriptRecorder.WaitFor(byId, WaitState.DISABLED), java))
    }

    @Test
    fun `java buttons and back`() {
        assertEquals("""((JavascriptExecutor) driver).executeScript("mobile: pressKey", Map.of("keycode", 3));""", one(ScriptRecorder.Button("HOME"), java))
        assertEquals("""((JavascriptExecutor) driver).executeScript("mobile: pressButton", Map.of("name", "home"));""", one(ScriptRecorder.Button("HOME"), java, Platform.IOS))
        assertEquals("driver.navigate().back();", one(ScriptRecorder.GoBack, java))
    }

    @Test
    fun `notes and unfocused text are comments`() {
        assertEquals("// pressed ENTER", one(ScriptRecorder.Note("pressed ENTER"), java))
        assertEquals("""// typed "abc": tap the field first so it can be recorded as fill""", one(ScriptRecorder.UnfocusedText("abc"), java))
        assertEquals("# pressed ENTER", one(ScriptRecorder.Note("pressed ENTER"), python))
    }

    @Test
    fun `a drag note does not blame mobilewright`() {
        val note = ScriptRecorder.Note("drag with hold (500 ms) from (1, 2) to (3, 4): Mobilewright has no drag API, not replayable")
        assertEquals("// drag with hold (500 ms) from (1, 2) to (3, 4): no Appium form recorded (a drag needs an element to drag)", one(note, java))
    }

    @Test
    fun `a step without a unique appium locator is a comment, never coordinates`() {
        val c = one(ScriptRecorder.Tap(none), java)
        assertTrue(c, c.startsWith("// tap screen.getByText('Hi'): no unique Appium locator"))
        for (step in listOf(
            ScriptRecorder.LongPress(none, 700), ScriptRecorder.DoubleTap(none), ScriptRecorder.Fill(none, "x"),
            ScriptRecorder.Expect(none, Assertion.Visible), ScriptRecorder.WaitFor(none, WaitState.VISIBLE),
            ScriptRecorder.ScrollUntilVisible(none, "up", 5),
        )) {
            val out = lines(step, java).single()
            assertTrue(out, out.startsWith("// ") && out.contains("no unique Appium locator"))
        }
    }

    @Test
    fun `count assertions have no appium form`() {
        assertTrue(one(ScriptRecorder.ExpectCount(byId, 3), java).startsWith("// expect count 3 of"))
    }

    @Test
    fun `an unverified step keeps its warning`() {
        assertEquals(
            """driver.findElement(By.id("login")).click();  // check: matched against the screen before the previous action""",
            one(ScriptRecorder.Tap(byId, unverified = true), java),
        )
        assertTrue(one(ScriptRecorder.Tap(byId, unverified = true), python).contains("  # check:"))
    }

    // ---- strategies

    @Test
    fun `every strategy is rendered through the shared locator code`() {
        fun tap(s: AppiumStrategy, v: String) = one(ScriptRecorder.Tap(Target(Derived(Kind.TEXT, "x"), appium = PageLocator.Appium(s, v))), java)
        assertEquals("""driver.findElement(AppiumBy.androidUIAutomator("new UiSelector().text(\"a\")")).click();""", tap(AppiumStrategy.ANDROID_UIAUTOMATOR, "new UiSelector().text(\"a\")"))
        assertEquals("""driver.findElement(AppiumBy.iOSNsPredicateString("label == 'a'")).click();""", tap(AppiumStrategy.IOS_PREDICATE, "label == 'a'"))
        assertEquals("""driver.findElement(By.xpath("//a")).click();""", tap(AppiumStrategy.XPATH, "//a"))
    }

    // ---- Kotlin

    @Test
    fun `kotlin steps`() {
        assertEquals("""driver.findElement(By.id("login")).click()""", one(ScriptRecorder.Tap(byId), kotlin))
        assertEquals(
            listOf("""driver.findElement(By.id("login")).clear()""", """driver.findElement(By.id("login")).sendKeys("a${'$'}b")"""),
            lines(ScriptRecorder.Fill(byId, "a\$b"), kotlin).map { it.replace("\\${'$'}", "${'$'}") },
        )
        assertEquals(
            """(driver as JavascriptExecutor).executeScript("mobile: longClickGesture", mapOf("elementId" to (driver.findElement(By.id("login")) as RemoteWebElement).id, "duration" to 700))""",
            one(ScriptRecorder.LongPress(byId, 700), kotlin),
        )
        assertEquals(
            """assertEquals("Hi", wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("login"))).text)""",
            one(ScriptRecorder.Expect(byId, Assertion.HasText("Hi")), kotlin),
        )
        assertEquals("""wait.until { it.findElement(By.id("login")).isEnabled }""", one(ScriptRecorder.WaitFor(byId, WaitState.ENABLED), kotlin))
        assertEquals("driver.navigate().back()", one(ScriptRecorder.GoBack, kotlin))
        val loop = lines(ScriptRecorder.ScrollUntilVisible(byId, "down", 4), kotlin, Platform.IOS)
        assertEquals("for (i in 0 until 4) {", loop[0])
        assertEquals("""    if (driver.findElements(By.id("login")).any { it.isDisplayed }) break""", loop[1])
        assertEquals("}", loop.last())
        assertTrue(one(ScriptRecorder.Swipe("up"), kotlin).contains("driver.manage().window().size.width"))
    }

    // ---- Python

    @Test
    fun `python steps`() {
        assertEquals("""driver.find_element(AppiumBy.ID, "login").click()""", one(ScriptRecorder.Tap(byId), python))
        assertEquals(
            listOf("""driver.find_element(AppiumBy.ID, "login").clear()""", """driver.find_element(AppiumBy.ID, "login").send_keys("x")"""),
            lines(ScriptRecorder.Fill(byId, "x"), python),
        )
        assertEquals("""driver.execute_script("mobile: doubleClickGesture", {"elementId": driver.find_element(AppiumBy.ID, "login").id})""", one(ScriptRecorder.DoubleTap(byId), python))
        assertEquals("""assert wait.until(EC.visibility_of_element_located((AppiumBy.ID, "login"))).text == "Hi"""", one(ScriptRecorder.Expect(byId, Assertion.HasText("Hi")), python))
        assertEquals("""assert "Hi" in wait.until(EC.visibility_of_element_located((AppiumBy.ID, "login"))).text""", one(ScriptRecorder.Expect(byId, Assertion.ContainsText("Hi")), python))
        assertEquals("""assert not wait.until(EC.visibility_of_element_located((AppiumBy.ID, "login"))).is_enabled()""", one(ScriptRecorder.Expect(byId, Assertion.Enabled(false)), python))
        assertEquals("""wait.until(EC.invisibility_of_element_located((AppiumBy.ID, "login")))""", one(ScriptRecorder.WaitFor(byId, WaitState.HIDDEN), python))
        assertEquals("""wait.until(lambda d: d.find_element(AppiumBy.ID, "login").is_enabled())""", one(ScriptRecorder.WaitFor(byId, WaitState.ENABLED), python))
        assertEquals("driver.back()", one(ScriptRecorder.GoBack, python))
        val loop = lines(ScriptRecorder.ScrollUntilVisible(byId, "up", 3), python, Platform.IOS)
        assertEquals(
            listOf(
                "for _ in range(3):",
                """    if any(e.is_displayed() for e in driver.find_elements(AppiumBy.ID, "login")): break""",
                """    driver.execute_script("mobile: swipe", {"direction": "up"})""",
            ),
            loop,
        )
        assertTrue(one(ScriptRecorder.Swipe("up"), python).contains("""driver.get_window_size()["width"]"""))
    }

    // ---- C#

    @Test
    fun `csharp steps`() {
        assertEquals("""driver.FindElement(By.Id("login")).Click();""", one(ScriptRecorder.Tap(byId), csharp))
        assertEquals(
            listOf("""driver.FindElement(MobileBy.AccessibilityId("login")).Clear();""", """driver.FindElement(MobileBy.AccessibilityId("login")).SendKeys("x");"""),
            lines(ScriptRecorder.Fill(byAcc, "x"), csharp),
        )
        assertEquals(
            """driver.ExecuteScript("mobile: longClickGesture", new Dictionary<string, object> { { "elementId", ((WebElement)driver.FindElement(By.Id("login"))).Id }, { "duration", 700 } });""",
            one(ScriptRecorder.LongPress(byId, 700), csharp),
        )
        assertEquals(
            """Assert.That(wait.Until(d => d.FindElements(By.Id("login")).FirstOrDefault(e => e.Displayed)).Text, Is.EqualTo("Hi"));""",
            one(ScriptRecorder.Expect(byId, Assertion.HasText("Hi")), csharp),
        )
        assertEquals("""Assert.That(wait.Until(d => d.FindElements(By.Id("login")).FirstOrDefault(e => e.Displayed)).Text, Does.Contain("Hi"));""", one(ScriptRecorder.Expect(byId, Assertion.ContainsText("Hi")), csharp))
        assertEquals("""wait.Until(d => !d.FindElements(By.Id("login")).Any(e => e.Displayed));""", one(ScriptRecorder.WaitFor(byId, WaitState.HIDDEN), csharp))
        assertEquals("""wait.Until(d => d.FindElements(By.Id("login")).Any(e => !e.Enabled));""", one(ScriptRecorder.WaitFor(byId, WaitState.DISABLED), csharp))
        assertEquals("driver.Navigate().Back();", one(ScriptRecorder.GoBack, csharp))
        assertTrue(one(ScriptRecorder.Swipe("up"), csharp).contains("driver.Manage().Window.Size.Width"))
        assertEquals("""for (var i = 0; i < 2 && !driver.FindElements(By.Id("login")).Any(e => e.Displayed); i++) {""", lines(ScriptRecorder.ScrollUntilVisible(byId, "up", 2), csharp)[0])
    }

    // ---- whole script

    @Test
    fun `render adds the wait declaration and the assert import only when used`() {
        val tapOnly = AppiumSteps.render(listOf(ScriptRecorder.Tap(byId)), java, Platform.ANDROID)
        assertEquals("""driver.findElement(By.id("login")).click();""", tapOnly)
        val full = AppiumSteps.render(listOf(ScriptRecorder.Tap(byId), ScriptRecorder.Expect(byId, Assertion.HasText("Hi"))), java, Platform.ANDROID).lines()
        assertEquals("// WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));", full[0])
        assertEquals("// import static org.junit.jupiter.api.Assertions.*;", full[1])
        assertEquals(4, full.size)
        val cs = AppiumSteps.render(listOf(ScriptRecorder.WaitFor(byId, WaitState.HIDDEN)), csharp, Platform.IOS).lines()
        assertEquals(listOf("// var wait = new WebDriverWait(driver, TimeSpan.FromSeconds(10));", "// using System.Linq;"), cs.take(2))
        val csVisible = AppiumSteps.render(listOf(ScriptRecorder.Expect(byId, Assertion.Visible)), csharp, Platform.IOS)
        assertTrue(csVisible.contains("FirstOrDefault(") && csVisible.contains("// using System.Linq;"))
        val py = AppiumSteps.render(listOf(ScriptRecorder.Expect(byId, Assertion.Visible)), python, Platform.IOS)
        assertTrue(py.startsWith("# wait = WebDriverWait(driver, 10)"))
    }

    @Test
    fun `mobilewright output is the plain code and the summary joins lines`() {
        val steps = listOf(ScriptRecorder.Tap(byId))
        assertEquals("await screen.getByTestId('login').tap();", RecorderOutput.MOBILEWRIGHT.render(steps, Platform.ANDROID))
        assertEquals(
            """driver.findElement(By.id("login")).clear(); driver.findElement(By.id("login")).sendKeys("x");""",
            RecorderOutput.APPIUM_JAVA.summary(ScriptRecorder.Fill(byId, "x"), Platform.ANDROID),
        )
        assertEquals(RecorderOutput.MOBILEWRIGHT, RecorderOutput.fromName("nonsense"))
        assertEquals(RecorderOutput.APPIUM_PYTHON, RecorderOutput.fromName("APPIUM_PYTHON"))
    }

    // ---- recorder integration

    private val ios = WdaSourceParser.parse(javaClass.getResourceAsStream("/fixtures/ios_login.xml")!!.readBytes().toString(Charsets.UTF_8))

    @Test
    fun `the recorder computes the appium locator at record time`() {
        val r = ScriptRecorder(Platform.IOS)
        r.tap(ios, 100, 220)
        r.type("hi")
        val fill = r.steps.single() as ScriptRecorder.Fill
        assertEquals(PageLocator.Appium::class, fill.target.appium!!::class)
        val out = AppiumSteps.lines(fill, java, Platform.IOS)
        assertEquals(2, out.size)
        assertTrue(out.toString(), out[1].endsWith(""".sendKeys("hi");"""))
        println("recorded appium locator: ${fill.target.appium}")
    }
}
