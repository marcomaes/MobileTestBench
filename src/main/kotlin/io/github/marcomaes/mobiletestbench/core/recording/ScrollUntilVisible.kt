package io.github.marcomaes.mobiletestbench.core.recording

import io.github.marcomaes.mobiletestbench.core.Size
import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import io.github.marcomaes.mobiletestbench.core.recording.ScriptRecorder.Step

/** Turns recorded swipes plus the element step after them into one bounded "scroll until visible" step. */
object ScrollUntilVisible {
    const val DEFAULT_MAX_SWIPES = 10
    val DIRECTIONS = listOf("up", "down", "left", "right")
    val LIMIT_RANGE = 1..100

    /** A whole number of swipes within [LIMIT_RANGE], or null. */
    fun parseLimit(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in LIMIT_RANGE }

    /** The step with another direction and swipe limit, or null when either is not allowed. */
    fun withOptions(step: ScriptRecorder.ScrollUntilVisible, direction: String, maxSwipes: Int): ScriptRecorder.ScrollUntilVisible? =
        if (direction in DIRECTIONS && maxSwipes in LIMIT_RANGE) step.copy(direction = direction, maxSwipes = maxSwipes) else null

    sealed interface Result {
        data class Converted(val steps: List<Step>) : Result
        data class Rejected(val reason: String) : Result
    }

    /**
     * The target of a step that acts on one element, or null for steps a scroll cannot lead up to. That includes
     * steps that expect the element to be hidden or missing: scrolling until it is visible would contradict them.
     */
    fun targetOf(step: Step): ScriptRecorder.Target? = when (step) {
        is ScriptRecorder.Tap -> step.target
        is ScriptRecorder.LongPress -> step.target
        is ScriptRecorder.DoubleTap -> step.target
        is ScriptRecorder.Fill -> step.target
        is ScriptRecorder.Expect -> step.target.takeUnless { step.assertion.passesWhenMissing }
        is ScriptRecorder.WaitFor -> step.target.takeUnless { step.state == WaitState.HIDDEN }
        else -> null
    }

    /**
     * Replaces the swipes at [indices] by a [ScriptRecorder.ScrollUntilVisible] placed before the step that follows
     * them. The swipes must be next to each other, all in the same direction, and followed by an element step.
     * The limit is [DEFAULT_MAX_SWIPES], or the number of swipes selected when that is more.
     */
    fun convert(steps: List<Step>, indices: Collection<Int>): Result {
        val sorted = indices.toSortedSet().toList()
        if (sorted.isEmpty()) return Result.Rejected("Select the swipe steps first")
        if (sorted.any { it !in steps.indices }) return Result.Rejected("The selection is out of date")
        val swipes = sorted.map { steps[it] as? ScriptRecorder.Swipe ?: return Result.Rejected("Only swipe steps can be turned into a scroll") }
        if (sorted.last() - sorted.first() != sorted.size - 1) return Result.Rejected("The selected swipes must be next to each other")
        val direction = swipes.first().direction
        if (swipes.any { it.direction != direction }) return Result.Rejected("The selected swipes go in different directions")
        val next = steps.getOrNull(sorted.last() + 1) ?: return Result.Rejected("There is no step after the swipes to scroll to")
        val target = targetOf(next) ?: return Result.Rejected("The step after the swipes does not act on an element")
        val scroll = ScriptRecorder.ScrollUntilVisible(target, direction, maxOf(DEFAULT_MAX_SWIPES, swipes.size))
        return Result.Converted(steps.take(sorted.first()) + scroll + steps.drop(sorted.last() + 1))
    }

    /** Visible, and its center is on the screen, so that a tap there hits it. A missing element is not on screen. */
    fun isOnScreen(node: UiNode?, screen: Size): Boolean {
        val bounds = node?.bounds ?: return false
        return node.isVisible && bounds.centerX in 0 until screen.width && bounds.centerY in 0 until screen.height
    }
}
