package io.github.marcomaes.mobiletestbench.core.recording

import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.pageobject.PageLocator
import io.github.marcomaes.mobiletestbench.core.pageobject.WebdriverIo
import io.github.marcomaes.mobiletestbench.core.recording.ScriptRecorder.Step
import io.github.marcomaes.mobiletestbench.core.recording.ScriptRecorder.Target

/**
 * Renders recorded steps as Appium JavaScript with WebdriverIO. Pure text generation.
 *
 * Conventions: the code assumes `$`, `driver` and `expect` from `@wdio/globals` (a leading comment names the import). Elements are
 * found with the best unique Appium locator recorded with the step ([Target.appium]); a step whose element has none is written as
 * a comment. Assertions are the `expect-webdriverio` matchers, which retry; an explicit timeout becomes `{ wait }`. Explicit waits use
 * `waitForDisplayed` / `waitForEnabled` with `{ timeout }`. Gestures use the same `mobile:` commands as [AppiumSteps].
 */
object WebdriverIoSteps {

    private const val DEFAULT_WAIT_MS = 10_000
    private const val SWIPE_PERCENT = "0.75"
    private const val IMPORT = "import { $, driver, expect } from '@wdio/globals';"

    fun render(steps: List<Step>, platform: Platform): String {
        val body = steps.flatMap { lines(it, platform) }
        val hasCode = body.any { !it.trimStart().startsWith("//") }
        return ((if (hasCode) listOf("// $IMPORT") else emptyList()) + body).joinToString("\n")
    }

    private fun str(text: String) = WebdriverIo.quote(text)

    private fun el(l: PageLocator.Appium) = WebdriverIo.element(l)

    private fun execute(name: String, vararg arguments: Pair<String, String>) =
        "await driver.execute(${str("mobile: $name")}, { " + arguments.joinToString(", ") { "${it.first}: ${it.second}" } + " });"

    private fun swipe(direction: String, android: Boolean) =
        if (android) {
            execute(
                "swipeGesture", "left" to "0", "top" to "0", "width" to "(await driver.getWindowSize()).width",
                "height" to "(await driver.getWindowSize()).height", "direction" to str(direction), "percent" to SWIPE_PERCENT,
            )
        } else {
            execute("swipe", "direction" to str(direction))
        }

    /** `, { wait: 5000 }` for a step with its own timeout, nothing otherwise. */
    private fun waitOption(timeoutMs: Long?) = timeoutMs?.let { ", { wait: $it }" } ?: ""

    private fun assertion(l: PageLocator.Appium, a: Assertion, timeoutMs: Long?, android: Boolean): String {
        val w = waitOption(timeoutMs)
        val opts = timeoutMs?.let { "{ wait: $it }" } ?: ""
        val matcher = when (a) {
            Assertion.Visible -> "toBeDisplayed($opts)"
            Assertion.Hidden -> "not.toBeDisplayed($opts)"
            is Assertion.HasText -> "toHaveText(${str(a.text)}$w)"
            is Assertion.ContainsText -> "toHaveText(expect.stringContaining(${str(a.text)})$w)"
            // Android keeps the content of an input in its text, iOS in its value.
            is Assertion.HasValue -> if (android) "toHaveText(${str(a.value)}$w)" else "toHaveAttribute('value', ${str(a.value)}$w)"
            is Assertion.Enabled -> "${if (a.enabled) "" else "not."}toBeEnabled($opts)"
            Assertion.Checked -> if (android) "toHaveAttribute('checked', 'true'$w)" else "toHaveAttribute('value', '1'$w)"
        }
        return "await expect(${el(l)}).$matcher;"
    }

    private fun waitFor(l: PageLocator.Appium, state: WaitState, timeoutMs: Long?): String {
        val timeout = "timeout: ${timeoutMs ?: DEFAULT_WAIT_MS}"
        val (method, reverse) = when (state) {
            WaitState.VISIBLE -> "waitForDisplayed" to false
            WaitState.HIDDEN -> "waitForDisplayed" to true
            WaitState.ENABLED -> "waitForEnabled" to false
            WaitState.DISABLED -> "waitForEnabled" to true
        }
        return "await ${el(l)}.$method({ ${if (reverse) "reverse: true, " else ""}$timeout });"
    }

    private fun elementId(l: PageLocator.Appium) = "(await ${el(l)}).elementId"

    private fun noLocator(what: String, target: Target) =
        listOf("// $what ${target.code()}: no unique Appium locator for this element, give it an id or accessibility id")

    private fun withNote(lines: List<String>, unverified: Boolean): List<String> =
        if (unverified && lines.isNotEmpty()) lines.dropLast(1) + (lines.last() + "  // check: matched against the screen before the previous action") else lines

    private fun onElement(what: String, target: Target, unverified: Boolean = false, make: (PageLocator.Appium) -> List<String>): List<String> {
        val l = target.appium ?: return noLocator(what, target)
        return withNote(make(l), unverified)
    }

    /** The lines of one step. */
    fun lines(step: Step, platform: Platform): List<String> {
        val android = platform == Platform.ANDROID
        return when (step) {
            is ScriptRecorder.Tap -> onElement("tap", step.target, step.unverified) { listOf("await ${el(it)}.click();") }
            is ScriptRecorder.LongPress -> onElement("long press", step.target, step.unverified) {
                listOf(
                    if (android) execute("longClickGesture", "elementId" to elementId(it), "duration" to step.durationMs.toString())
                    else execute("touchAndHold", "elementId" to elementId(it), "duration" to (step.durationMs / 1000.0).toString()),
                )
            }
            is ScriptRecorder.DoubleTap -> onElement("double tap", step.target, step.unverified) {
                listOf(execute(if (android) "doubleClickGesture" else "doubleTap", "elementId" to elementId(it)))
            }
            // setValue clears the field first, like the Mobilewright fill.
            is ScriptRecorder.Fill -> onElement("fill", step.target, step.unverified) { listOf("await ${el(it)}.setValue(${str(step.text)});") }
            is ScriptRecorder.Expect -> onElement("expect", step.target) { listOf(assertion(it, step.assertion, step.timeoutMs, android)) }
            is ScriptRecorder.ExpectCount -> listOf(
                "// expect count ${step.count} of ${step.target.withoutNth().code()}: no Appium form, the unique Appium locator of the element matches one element",
            )
            is ScriptRecorder.WaitFor -> onElement("wait", step.target) { listOf(waitFor(it, step.state, step.timeoutMs)) }
            is ScriptRecorder.Swipe -> listOf(swipe(step.direction, android))
            is ScriptRecorder.ScrollUntilVisible -> onElement("scroll until visible", step.target) {
                listOf(
                    "for (let i = 0; i < ${step.maxSwipes} && !(await ${el(it)}.isDisplayed()); i++) {",
                    "    ${swipe(step.direction, android)}",
                    "}",
                )
            }
            is ScriptRecorder.Button ->
                if (step.name == "HOME") listOf(if (android) execute("pressKey", "keycode" to "3") else execute("pressButton", "name" to str("home")))
                else listOf("// press button ${step.name}: no Appium form")
            ScriptRecorder.GoBack -> listOf("await driver.back();")
            is ScriptRecorder.UnfocusedText -> listOf("// typed ${str(step.text)}: tap the field first so it can be recorded as fill")
            is ScriptRecorder.Note -> listOf("// ${AppiumSteps.appiumNote(step.text)}")
        }
    }
}
