package io.github.marcomaes.mobiletestbench.core.recording

import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.inspector.MobilewrightLocators
import io.github.marcomaes.mobiletestbench.core.inspector.UiNode

/** One entry of the Assert menu: turns the clicked element's locator into a recorded step. */
interface Check {
    /** Short description for a menu. */
    val label: String

    fun toStep(target: ScriptRecorder.Target): ScriptRecorder.Step
}

/**
 * Mobilewright's `locator.waitFor({ state })`, with the same rules as its `checkState`: `hidden` also holds for an
 * element that is not on the screen, `disabled` needs the element to exist.
 */
enum class WaitState(val js: String) {
    VISIBLE("visible"),
    HIDDEN("hidden"),
    ENABLED("enabled"),
    DISABLED("disabled");

    fun holds(node: UiNode?): Boolean = when (this) {
        VISIBLE -> node != null && node.isVisible
        HIDDEN -> node == null || !node.isVisible
        ENABLED -> node != null && Assertion.isEnabled(node)
        DISABLED -> node != null && !Assertion.isEnabled(node)
    }
}

/** An explicit wait step: `await locator.waitFor({ state: 'visible' });`. */
data class WaitUntil(val state: WaitState) : Check {
    override val label get() = "Wait until ${state.js}"
    override fun toStep(target: ScriptRecorder.Target): ScriptRecorder.Step = ScriptRecorder.WaitFor(target, state)
}

/** `await expect(locator).toHaveCount(n);`: how many elements match a locator shared by repeated elements. */
data class CountIs(val count: Int) : Check {
    override val label get() = "Count of matching elements = $count"
    override fun toStep(target: ScriptRecorder.Target): ScriptRecorder.Step = ScriptRecorder.ExpectCount(target, count)
}

object Checks {
    /**
     * The whole Assert menu for an element: the assertions that fit it, then "becomes hidden", the explicit waits and, when
     * the locator matches [matchCount] >= 2 elements, a count assertion prefilled with that number.
     */
    fun optionsFor(node: UiNode, matchCount: Int = 1): List<Check> = buildList {
        addAll(Assertion.optionsFor(node))
        add(Assertion.Hidden)
        WaitState.entries.forEach { add(WaitUntil(it)) }
        if (matchCount >= 2) add(CountIs(matchCount))
    }
}

/**
 * Something to check about an element, written as a Mobilewright `expect(locator)` matcher. Mobilewright
 * assertions retry until they hold, so recording one doubles as an explicit wait.
 */
sealed interface Assertion : Check {
    /** The matcher after `expect(locator)`, e.g. `.toHaveText('Welcome')`. */
    fun matcher(): String

    fun holds(node: UiNode): Boolean

    /** True when an element that is not on the screen at all satisfies this assertion (Mobilewright's `toBeHidden`). */
    val passesWhenMissing: Boolean get() = false

    override fun toStep(target: ScriptRecorder.Target): ScriptRecorder.Step = ScriptRecorder.Expect(target, this)

    /** What the element has instead, for a failure message. */
    fun actual(node: UiNode): String

    data object Visible : Assertion {
        override fun matcher() = ".toBeVisible()"
        override val label get() = "Is visible"
        override fun holds(node: UiNode) = node.isVisible
        override fun actual(node: UiNode) = if (node.isVisible) "visible" else "not visible"
    }

    /** Mobilewright: `toBeHidden()` is `!isVisible`, so an element that left the screen counts as hidden. */
    data object Hidden : Assertion {
        override fun matcher() = ".toBeHidden()"
        override val label get() = "Becomes hidden"
        override fun holds(node: UiNode) = !node.isVisible
        override val passesWhenMissing get() = true
        override fun actual(node: UiNode) = "visible"
    }

    data class HasText(val text: String) : Assertion {
        override fun matcher() = ".toHaveText(${MobilewrightLocators.js(text)})"
        override val label get() = "Has text ${quote(text)}"
        override fun holds(node: UiNode) = visibleText(node) == text
        override fun actual(node: UiNode) = quote(visibleText(node).orEmpty())
    }

    data class ContainsText(val text: String) : Assertion {
        override fun matcher() = ".toContainText(${MobilewrightLocators.js(text)})"
        override val label get() = "Contains text ${quote(text)}"
        override fun holds(node: UiNode) = visibleText(node)?.contains(text) == true
        override fun actual(node: UiNode) = quote(visibleText(node).orEmpty())
    }

    data class HasValue(val value: String) : Assertion {
        override fun matcher() = ".toHaveValue(${MobilewrightLocators.js(value)})"
        override val label get() = "Has value ${quote(value)}"
        override fun holds(node: UiNode) = inputValue(node) == value
        override fun actual(node: UiNode) = quote(inputValue(node).orEmpty())
    }

    data class Enabled(val enabled: Boolean) : Assertion {
        override fun matcher() = if (enabled) ".toBeEnabled()" else ".toBeDisabled()"
        override val label get() = if (enabled) "Is enabled" else "Is disabled"
        override fun holds(node: UiNode) = isEnabled(node) == enabled
        override fun actual(node: UiNode) = if (isEnabled(node)) "enabled" else "disabled"
    }

    data object Checked : Assertion {
        override fun matcher() = ".toBeChecked()"
        override val label get() = "Is checked"
        override fun holds(node: UiNode) = isChecked(node)
        override fun actual(node: UiNode) = if (isChecked(node)) "checked" else "not checked"
    }

    companion object {
        private const val MAX_LABEL_TEXT = 30

        private fun quote(text: String) = "'" + (if (text.length > MAX_LABEL_TEXT) text.take(MAX_LABEL_TEXT) + "..." else text) + "'"

        /** What Mobilewright's `getText()` reads: the visible text or label. */
        fun visibleText(node: UiNode): String? = (node.text ?: node.label ?: node.value)?.takeIf { it.isNotEmpty() }

        /** The content of an input: iOS `value`, Android `text`. */
        fun inputValue(node: UiNode): String? = when (node.platform) {
            Platform.IOS -> node.value
            Platform.ANDROID -> node.text
        }

        fun isEnabled(node: UiNode) = node.attr("enabled") != "false"

        fun isChecked(node: UiNode): Boolean = when (node.platform) {
            Platform.ANDROID -> node.attr("checked") == "true"
            Platform.IOS -> node.value == "1" || node.value.equals("true", ignoreCase = true)
        }

        private val inputTypes = setOf("textfield", "securetextfield", "searchfield", "edittext", "reactedittext")
        private val checkTypes = setOf("switch", "toggle", "checkbox", "checkedtextview", "radiobutton", "togglebutton")

        /** The assertions that make sense for this element as it is now, most useful first. */
        fun optionsFor(node: UiNode): List<Assertion> = buildList {
            add(Visible)
            val type = node.type.lowercase()
            val text = visibleText(node)
            if (type in inputTypes) {
                inputValue(node)?.takeIf { it.isNotEmpty() }?.let { add(HasValue(it)) }
            } else if (text != null) {
                add(HasText(text))
                if (text.length > 12) add(ContainsText(text.take(12).trim()))
            }
            add(Enabled(isEnabled(node)))
            if (type in checkTypes && isChecked(node)) add(Checked)
        }
    }
}
