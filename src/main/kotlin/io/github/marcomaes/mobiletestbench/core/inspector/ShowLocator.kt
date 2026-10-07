package io.github.marcomaes.mobiletestbench.core.inspector

/** The result of trying a locator from the editor against a hierarchy. */
sealed class ShowLocatorResult {
    data class Matches(val expression: String, val nodes: List<UiNode>) : ShowLocatorResult()
    data class NotSupported(val expression: String, val reason: String) : ShowLocatorResult()
    data class Failed(val reason: String) : ShowLocatorResult()
    object NoLocator : ShowLocatorResult()
    object NoDevice : ShowLocatorResult()

    /** One line for the balloon and the status line. */
    fun message(): String = when (this) {
        is Matches -> when (nodes.size) {
            0 -> "no match: $expression"
            1 -> "1 match (unique): $expression"
            else -> "${nodes.size} matches: $expression"
        }
        is NotSupported -> "not supported: $reason"
        is Failed -> "could not read the screen: $reason"
        NoLocator -> "no locator found in the selection or on this line"
        NoDevice -> "no device connected: connect one in Mobile Devices"
    }

    /** True when something worth highlighting was found. */
    val isSuccess: Boolean get() = this is Matches && nodes.isNotEmpty()
}

object ShowLocator {
    /** Picks the locator out of [text] (a selection or a line of code) and evaluates it against [root]. Never throws. */
    fun evaluate(text: String, root: UiNode): ShowLocatorResult {
        val extracted = LocatorExtraction.extract(text) ?: return ShowLocatorResult.NoLocator
        return evaluateExpression(extracted.expression, root)
    }

    fun evaluateExpression(expression: String, root: UiNode): ShowLocatorResult = try {
        ShowLocatorResult.Matches(expression, LocatorQuery.find(expression, root))
    } catch (e: Exception) {
        val reason = (e.message ?: e.javaClass.simpleName).lineSequence().first().removePrefix("not supported yet: ").removePrefix("not supported: ")
        ShowLocatorResult.NotSupported(expression, reason)
    }
}
