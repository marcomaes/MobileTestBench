package io.github.marcomaes.mobiletestbench.core.recording

import io.github.marcomaes.mobiletestbench.core.recording.ScriptRecorder.Step

/** Replay settings (limits and clamping) and the choice of which steps a replay runs. Pure logic. */
object ReplayOptions {
    const val DEFAULT_TIMEOUT_SECONDS = 10
    val TIMEOUT_SECONDS = 1..300
    const val DEFAULT_DELAY_MS = 0
    val DELAY_MS = 0..10_000

    /** The per-step timeout of a wait or expect, in seconds. */
    val STEP_TIMEOUT_SECONDS = 1..3600

    fun timeoutMs(seconds: Int): Long = seconds.coerceIn(TIMEOUT_SECONDS) * 1000L

    fun delayMs(ms: Int): Long = ms.coerceIn(DELAY_MS).toLong()

    /** The timeout a step runs with: its own when it has one, otherwise the replay default. */
    fun resolveTimeoutMs(stepTimeoutMs: Long?, defaultMs: Long): Long = stepTimeoutMs?.takeIf { it > 0 } ?: defaultMs

    /** Whole seconds typed by the user for a step timeout; null when it is not a number in [STEP_TIMEOUT_SECONDS]. */
    fun parseStepSeconds(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in STEP_TIMEOUT_SECONDS }

    /** The seconds of a timeout in milliseconds, rounded up, for code that takes whole seconds. */
    fun wholeSeconds(ms: Long): Int = ((ms + 999) / 1000).toInt().coerceAtLeast(1)

    /** For messages: "10 s", "0.5 s". */
    fun format(ms: Long): String = if (ms % 1000 == 0L) "${ms / 1000} s" else "${ms / 100 / 10.0} s"

    /** The selected step and everything after it; null when nothing valid is selected. */
    fun fromSelected(selected: Collection<Int>, size: Int): IntRange? {
        val first = selected.filter { it in 0 until size }.minOrNull() ?: return null
        return first until size
    }

    /** From the first to the last selected step (a selection with gaps replays the gaps too); null when nothing valid is selected. */
    fun selectedRange(selected: Collection<Int>, size: Int): IntRange? {
        val valid = selected.filter { it in 0 until size }
        if (valid.isEmpty()) return null
        return valid.min()..valid.max()
    }

    /** Waits and expects (not scroll until visible, whose limit is its swipe count) can carry their own timeout. */
    fun supportsTimeout(step: Step): Boolean = step is ScriptRecorder.WaitFor || step is ScriptRecorder.Expect || step is ScriptRecorder.ExpectCount

    fun timeoutOf(step: Step): Long? = when (step) {
        is ScriptRecorder.WaitFor -> step.timeoutMs
        is ScriptRecorder.Expect -> step.timeoutMs
        is ScriptRecorder.ExpectCount -> step.timeoutMs
        else -> null
    }

    /** The step with [timeoutMs] (null removes it); a step that cannot carry one is returned as it is. */
    fun withTimeout(step: Step, timeoutMs: Long?): Step = when (step) {
        is ScriptRecorder.WaitFor -> step.copy(timeoutMs = timeoutMs)
        is ScriptRecorder.Expect -> step.copy(timeoutMs = timeoutMs)
        is ScriptRecorder.ExpectCount -> step.copy(timeoutMs = timeoutMs)
        else -> step
    }

    /**
     * Mobilewright: adds `{ timeout: ms }` as the last argument of a matcher call (`.toBeVisible()` becomes
     * `.toBeVisible({ timeout: 5000 })`, `.toHaveText('x')` becomes `.toHaveText('x', { timeout: 5000 })`).
     */
    fun matcherWithTimeout(matcher: String, timeoutMs: Long?): String = when {
        timeoutMs == null -> matcher
        matcher.endsWith("()") -> matcher.dropLast(1) + "{ timeout: $timeoutMs })"
        else -> matcher.dropLast(1) + ", { timeout: $timeoutMs })"
    }
}
