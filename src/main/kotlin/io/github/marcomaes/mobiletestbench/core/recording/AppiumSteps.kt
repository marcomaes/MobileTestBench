package io.github.marcomaes.mobiletestbench.core.recording

import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.pageobject.AppiumStrategy
import io.github.marcomaes.mobiletestbench.core.pageobject.PageLocator
import io.github.marcomaes.mobiletestbench.core.pageobject.PageObjectGenerator
import io.github.marcomaes.mobiletestbench.core.pageobject.PageObjectLanguage
import io.github.marcomaes.mobiletestbench.core.recording.ScriptRecorder.Step
import io.github.marcomaes.mobiletestbench.core.recording.ScriptRecorder.Target

/** What the Recording tab writes the steps as. Stored by [name] in the settings. */
enum class RecorderOutput(val label: String, val appium: PageObjectLanguage?, val webdriverio: Boolean = false) {
    MOBILEWRIGHT("Mobilewright", null),
    APPIUM_JAVA("Appium Java", PageObjectLanguage.APPIUM_JAVA),
    APPIUM_KOTLIN("Appium Kotlin", PageObjectLanguage.APPIUM_KOTLIN),
    APPIUM_PYTHON("Appium Python", PageObjectLanguage.APPIUM_PYTHON),
    APPIUM_CSHARP("Appium C#", PageObjectLanguage.APPIUM_CSHARP),
    APPIUM_WEBDRIVERIO("Appium JavaScript (WebdriverIO)", null, webdriverio = true);

    override fun toString() = label

    /** One step as it is shown in the list: Mobilewright code, or the Appium lines joined by a space. */
    fun summary(step: Step, platform: Platform): String =
        when {
            webdriverio -> WebdriverIoSteps.lines(step, platform).joinToString(" ") { it.trim() }
            appium == null -> step.code()
            else -> AppiumSteps.lines(step, appium, platform).joinToString(" ") { it.trim() }
        }

    /** The whole recording as code to copy or insert. */
    fun render(steps: List<Step>, platform: Platform): String =
        when {
            webdriverio -> WebdriverIoSteps.render(steps, platform)
            appium == null -> steps.joinToString("\n") { it.code() }
            else -> AppiumSteps.render(steps, appium, platform)
        }

    companion object {
        fun fromName(name: String?): RecorderOutput = entries.firstOrNull { it.name == name } ?: MOBILEWRIGHT
    }
}

/**
 * Renders recorded steps as Appium client code (Java, Kotlin, Python, C#). Pure text generation.
 *
 * Conventions: the code assumes a `driver` and, for waits and asserts, a `wait` (a `WebDriverWait` of 10 s; [render] puts the
 * declaration in a leading comment when it is needed). Elements are found with the best unique Appium locator recorded
 * with the step ([Target.appium]); a step whose element has none is written as a comment, never as coordinates.
 * Assertions: JUnit 5 (`assertEquals`, `assertTrue`) for Java and Kotlin, plain `assert` for Python, NUnit `Assert.That` for C#.
 * Gestures use the Appium `mobile:` commands of the recorded platform (UiAutomator2 `mobile: longClickGesture`,
 * `doubleClickGesture`, `swipeGesture`; XCUITest `mobile: touchAndHold`, `doubleTap`, `swipe`), because no one form works on both.
 */
object AppiumSteps {

    private const val WAIT_SECONDS = 10
    private const val SWIPE_PERCENT = "0.75"

    fun render(steps: List<Step>, language: PageObjectLanguage, platform: Platform): String {
        val body = steps.flatMap { lines(it, language, platform) }
        val code = body.filter { !it.trimStart().startsWith(Syntax(language, platform).commentPrefix) }
        val header = header(language, code)
        return (header + body).joinToString("\n")
    }

    /** The lines of one step. A single line for most steps, a few for fill, scroll until visible and the like. */
    fun lines(step: Step, language: PageObjectLanguage, platform: Platform): List<String> {
        require(language.appium) { "Not an Appium language: $language" }
        return Syntax(language, platform).lines(step)
    }

    private fun header(language: PageObjectLanguage, code: List<String>): List<String> {
        val s = Syntax(language, Platform.ANDROID)
        val usesWait = code.any { it.contains("wait.") }
        val usesAssert = code.any { s.isAssert(it) }
        val usesLinq = language == PageObjectLanguage.APPIUM_CSHARP && code.any { it.contains(".Any(") || it.contains(".FirstOrDefault(") }
        return buildList {
            if (usesWait) add(s.comment(s.waitDeclaration))
            if (usesAssert) add(s.comment(s.assertImport))
            if (usesLinq) add(s.comment("using System.Linq;"))
        }
    }

    private class Syntax(val language: PageObjectLanguage, val platform: Platform) {
        private val java = language == PageObjectLanguage.APPIUM_JAVA
        private val kotlin = language == PageObjectLanguage.APPIUM_KOTLIN
        private val python = language == PageObjectLanguage.APPIUM_PYTHON
        private val csharp = language == PageObjectLanguage.APPIUM_CSHARP
        private val android = platform == Platform.ANDROID

        /** The wait the waits and asserts of the current step use: the shared `wait` or, for a step with its own timeout, a new one. */
        private var waiter = "wait"

        private fun waiterFor(timeoutMs: Long?): String {
            if (timeoutMs == null) return "wait"
            val s = ReplayOptions.wholeSeconds(timeoutMs)
            return when {
                java -> "new WebDriverWait(driver, Duration.ofSeconds($s))"
                kotlin -> "WebDriverWait(driver, Duration.ofSeconds($s))"
                python -> "WebDriverWait(driver, $s)"
                else -> "new WebDriverWait(driver, TimeSpan.FromSeconds($s))"
            }
        }

        val commentPrefix = if (python) "#" else "//"
        private val semi = if (java || csharp) ";" else ""

        fun comment(text: String) = "$commentPrefix $text"

        val waitDeclaration: String = when {
            java -> "WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds($WAIT_SECONDS));"
            kotlin -> "val wait = WebDriverWait(driver, Duration.ofSeconds($WAIT_SECONDS))"
            python -> "wait = WebDriverWait(driver, $WAIT_SECONDS)  (from selenium.webdriver.support.ui import WebDriverWait; from selenium.webdriver.support import expected_conditions as EC)"
            else -> "var wait = new WebDriverWait(driver, TimeSpan.FromSeconds($WAIT_SECONDS));"
        }

        val assertImport: String = when {
            java -> "import static org.junit.jupiter.api.Assertions.*;"
            kotlin -> "import org.junit.jupiter.api.Assertions.*"
            python -> "plain assert statements"
            else -> "using NUnit.Framework;"
        }

        fun isAssert(line: String) = when {
            python -> line.trimStart().startsWith("assert ")
            csharp -> line.contains("Assert.That(")
            else -> line.contains("assertEquals(") || line.contains("assertTrue(") || line.contains("assertFalse(")
        }

        // ---- literals and locators

        private fun str(text: String) = PageObjectGenerator.quote(text, kotlin = kotlin)

        private fun by(l: PageLocator.Appium): String = PageObjectGenerator.appiumCode(language, l)!!

        /** Python takes the strategy and the value as two arguments: `(AppiumBy.ID, "x")` becomes `AppiumBy.ID, "x"`. */
        private fun args(l: PageLocator.Appium) = by(l).removePrefix("(").removeSuffix(")")

        fun find(l: PageLocator.Appium) = when {
            java || kotlin -> "driver.findElement(${by(l)})"
            python -> "driver.find_element(${args(l)})"
            else -> "driver.FindElement(${by(l)})"
        }

        private fun findAll(l: PageLocator.Appium) = when {
            java || kotlin -> "driver.findElements(${by(l)})"
            python -> "driver.find_elements(${args(l)})"
            else -> "driver.FindElements(${by(l)})"
        }

        // ---- waits

        /** An expression that waits for the element to be visible and gives the element. */
        private fun visibleElement(l: PageLocator.Appium) = when {
            java || kotlin -> "$waiter.until(ExpectedConditions.visibilityOfElementLocated(${by(l)}))"
            python -> "$waiter.until(EC.visibility_of_element_located(${by(l)}))"
            else -> "$waiter.Until(d => d.FindElements(${by(l)}).FirstOrDefault(e => e.Displayed))"
        }

        private fun waitFor(l: PageLocator.Appium, state: WaitState): String = when (state) {
            WaitState.VISIBLE -> "${visibleElement(l)}$semi"
            WaitState.HIDDEN -> when {
                java || kotlin -> "$waiter.until(ExpectedConditions.invisibilityOfElementLocated(${by(l)}))$semi"
                python -> "$waiter.until(EC.invisibility_of_element_located(${by(l)}))"
                else -> "$waiter.Until(d => !d.FindElements(${by(l)}).Any(e => e.Displayed));"
            }
            WaitState.ENABLED, WaitState.DISABLED -> {
                val enabled = state == WaitState.ENABLED
                when {
                    java -> "$waiter.until(d -> ${if (enabled) "" else "!"}d.findElement(${by(l)}).isEnabled());"
                    kotlin -> "$waiter.until { ${if (enabled) "" else "!"}it.findElement(${by(l)}).isEnabled }"
                    python -> "$waiter.until(lambda d: ${if (enabled) "" else "not "}d.${"find_element(${args(l)})"}.is_enabled())"
                    else -> "$waiter.Until(d => d.FindElements(${by(l)}).Any(e => ${if (enabled) "" else "!"}e.Enabled));"
                }
            }
        }

        // ---- reading

        private fun text(el: String) = when { java -> "$el.getText()"; kotlin -> "$el.text"; python -> "$el.text"; else -> "$el.Text" }

        private fun attribute(el: String, name: String) = when {
            java || kotlin -> "$el.getAttribute(${str(name)})"
            python -> "$el.get_attribute(${str(name)})"
            else -> "$el.GetAttribute(${str(name)})"
        }

        private fun enabled(el: String) = when { java -> "$el.isEnabled()"; kotlin -> "$el.isEnabled"; python -> "$el.is_enabled()"; else -> "$el.Enabled" }

        private fun assertEquals(expected: String, actual: String) = when {
            java -> "assertEquals($expected, $actual);"
            kotlin -> "assertEquals($expected, $actual)"
            python -> "assert $actual == $expected"
            else -> "Assert.That($actual, Is.EqualTo($expected));"
        }

        private fun assertTrue(condition: String, negated: Boolean = false) = when {
            java -> "${if (negated) "assertFalse" else "assertTrue"}($condition);"
            kotlin -> "${if (negated) "assertFalse" else "assertTrue"}($condition)"
            python -> "assert ${if (negated) "not " else ""}$condition"
            else -> "Assert.That($condition, Is.${if (negated) "False" else "True"});"
        }

        private fun assertion(l: PageLocator.Appium, a: Assertion): List<String> {
            val el = visibleElement(l).removeSuffix(";")
            return listOf(
                when (a) {
                    Assertion.Visible -> waitFor(l, WaitState.VISIBLE)
                    Assertion.Hidden -> waitFor(l, WaitState.HIDDEN)
                    is Assertion.HasText -> assertEquals(str(a.text), text(el))
                    is Assertion.ContainsText ->
                        if (python) "assert ${str(a.text)} in ${text(el)}"
                        else if (csharp) "Assert.That(${text(el)}, Does.Contain(${str(a.text)}));"
                        else assertTrue("${text(el)}.contains(${str(a.text)})")
                    is Assertion.HasValue ->
                        // Android keeps the content of an input in its text, iOS in its value.
                        assertEquals(str(a.value), if (android) text(el) else attribute(el, "value"))
                    is Assertion.Enabled -> assertTrue(enabled(el), negated = !a.enabled)
                    Assertion.Checked ->
                        if (android) assertEquals(str("true"), attribute(el, "checked")) else assertEquals(str("1"), attribute(el, "value"))
                },
            )
        }

        // ---- gestures

        private fun literal(v: Any) = if (v is String) str(v) else v.toString()

        /** `executeScript("mobile: name", { args })`. A value of the type [Raw] is already code. */
        private class Raw(val code: String) { override fun toString() = code }

        private fun script(name: String, vararg arguments: Pair<String, Any>): String {
            val map = when {
                java -> "Map.of(" + arguments.joinToString(", ") { "${str(it.first)}, ${literal(it.second)}" } + ")"
                kotlin -> "mapOf(" + arguments.joinToString(", ") { "${str(it.first)} to ${literal(it.second)}" } + ")"
                python -> "{" + arguments.joinToString(", ") { "${str(it.first)}: ${literal(it.second)}" } + "}"
                else -> "new Dictionary<string, object> { " + arguments.joinToString(", ") { "{ ${str(it.first)}, ${literal(it.second)} }" } + " }"
            }
            return when {
                java -> "((JavascriptExecutor) driver).executeScript(${str("mobile: $name")}, $map);"
                kotlin -> "(driver as JavascriptExecutor).executeScript(${str("mobile: $name")}, $map)"
                python -> "driver.execute_script(${str("mobile: $name")}, $map)"
                else -> "driver.ExecuteScript(${str("mobile: $name")}, $map);"
            }
        }

        private fun elementId(l: PageLocator.Appium): Raw = Raw(
            when {
                java -> "((RemoteWebElement) ${find(l)}).getId()"
                kotlin -> "(${find(l)} as RemoteWebElement).id"
                python -> "${find(l)}.id"
                else -> "((WebElement)${find(l)}).Id"
            },
        )

        private fun windowSize(dimension: String): Raw = Raw(
            when {
                java -> "driver.manage().window().getSize().get$dimension()"
                kotlin -> "driver.manage().window().size.${dimension.lowercase()}"
                python -> "driver.get_window_size()[${str(dimension.lowercase())}]"
                else -> "driver.Manage().Window.Size.$dimension"
            },
        )

        private fun swipe(direction: String): String =
            if (android) {
                script(
                    "swipeGesture", "left" to 0, "top" to 0, "width" to windowSize("Width"), "height" to windowSize("Height"),
                    "direction" to direction, "percent" to Raw(SWIPE_PERCENT),
                )
            } else {
                script("swipe", "direction" to direction)
            }

        private fun longPress(l: PageLocator.Appium, ms: Long): String =
            if (android) script("longClickGesture", "elementId" to elementId(l), "duration" to ms)
            else script("touchAndHold", "elementId" to elementId(l), "duration" to Raw((ms / 1000.0).toString()))

        private fun doubleTap(l: PageLocator.Appium): String =
            script(if (android) "doubleClickGesture" else "doubleTap", "elementId" to elementId(l))

        private fun scrollUntilVisible(l: PageLocator.Appium, step: ScriptRecorder.ScrollUntilVisible): List<String> {
            val gesture = swipe(step.direction)
            return when {
                java -> listOf(
                    "for (int i = 0; i < ${step.maxSwipes} && ${findAll(l)}.stream().noneMatch(WebElement::isDisplayed); i++) {",
                    "    $gesture", "}",
                )
                kotlin -> listOf(
                    "for (i in 0 until ${step.maxSwipes}) {",
                    "    if (${findAll(l)}.any { it.isDisplayed }) break", "    $gesture", "}",
                )
                python -> listOf(
                    "for _ in range(${step.maxSwipes}):",
                    "    if any(e.is_displayed() for e in ${findAll(l)}): break", "    $gesture",
                )
                else -> listOf(
                    "for (var i = 0; i < ${step.maxSwipes} && !${findAll(l)}.Any(e => e.Displayed); i++) {",
                    "    $gesture", "}",
                )
            }
        }

        private fun back() = when { python -> "driver.back()"; csharp -> "driver.Navigate().Back();"; java -> "driver.navigate().back();"; else -> "driver.navigate().back()" }

        // ---- steps

        private fun noLocator(what: String, target: Target) =
            listOf(comment("$what ${target.code()}: no unique Appium locator for this element, give it an id or accessibility id"))

        private fun withNote(lines: List<String>, unverified: Boolean): List<String> =
            if (unverified && lines.isNotEmpty()) lines.dropLast(1) + (lines.last() + "  " + comment("check: matched against the screen before the previous action")) else lines

        private fun onElement(what: String, target: Target, unverified: Boolean = false, make: (PageLocator.Appium) -> List<String>): List<String> {
            val l = target.appium ?: return noLocator(what, target)
            return withNote(make(l), unverified)
        }

        fun lines(step: Step): List<String> = when (step) {
            is ScriptRecorder.Tap -> onElement("tap", step.target, step.unverified) {
                listOf("${find(it)}.${if (csharp) "Click" else "click"}()$semi")
            }
            is ScriptRecorder.LongPress -> onElement("long press", step.target, step.unverified) { listOf(longPress(it, step.durationMs)) }
            is ScriptRecorder.DoubleTap -> onElement("double tap", step.target, step.unverified) { listOf(doubleTap(it)) }
            is ScriptRecorder.Fill -> onElement("fill", step.target, step.unverified) {
                // Mobilewright fill replaces the text, so clear first.
                listOf(
                    "${find(it)}.${if (csharp) "Clear" else "clear"}()$semi",
                    "${find(it)}.${if (csharp) "SendKeys" else if (python) "send_keys" else "sendKeys"}(${str(step.text)})$semi",
                )
            }
            is ScriptRecorder.Expect -> onElement("expect", step.target) { withWaiter(step.timeoutMs) { assertion(it, step.assertion) } }
            is ScriptRecorder.ExpectCount -> listOf(
                comment("expect count ${step.count} of ${step.target.withoutNth().code()}: no Appium form, the unique Appium locator of the element matches one element"),
            )
            is ScriptRecorder.WaitFor -> onElement("wait", step.target) { withWaiter(step.timeoutMs) { listOf(waitFor(it, step.state)) } }
            is ScriptRecorder.Swipe -> listOf(swipe(step.direction))
            is ScriptRecorder.ScrollUntilVisible -> onElement("scroll until visible", step.target) { scrollUntilVisible(it, step) }
            is ScriptRecorder.Button ->
                if (step.name == "HOME") listOf(if (android) script("pressKey", "keycode" to 3) else script("pressButton", "name" to "home"))
                else listOf(comment("press button ${step.name}: no Appium form"))
            ScriptRecorder.GoBack -> listOf(back())
            is ScriptRecorder.UnfocusedText -> listOf(comment("typed ${str(step.text)}: tap the field first so it can be recorded as fill"))
            is ScriptRecorder.Note -> listOf(comment(appiumNote(step.text)))
        }

        private fun <T> withWaiter(timeoutMs: Long?, block: () -> T): T {
            waiter = waiterFor(timeoutMs)
            try { return block() } finally { waiter = "wait" }
        }

    }

    /** A recorder note as it reads for Appium output. */
    internal fun appiumNote(text: String) =
        if (text.contains(": Mobilewright has no drag API")) text.substringBefore(": Mobilewright has no drag API") + ": no Appium form recorded (a drag needs an element to drag)" else text
}
