package io.github.marcomaes.mobiletestbench.core.recording

import io.github.marcomaes.mobiletestbench.core.DeviceKey
import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import io.github.marcomaes.mobiletestbench.core.recording.ScriptRecorder.Step
import io.github.marcomaes.mobiletestbench.core.session.DeviceSession
import io.github.marcomaes.mobiletestbench.core.session.UnsupportedOperationOnDevice

/**
 * Replays recorded steps on a device. Like Mobilewright, every step that targets an element waits for it
 * to show up (up to [elementTimeoutMs]) and then acts on its center; the first failure stops the run.
 * Runs on the calling thread and blocks: call it off the UI thread.
 */
class ScriptPlayer(
    private val session: DeviceSession,
    private val elementTimeoutMs: Long = 10_000,
    private val pollMs: Long = 500,
    /** Slow motion: pause between two replayed steps. */
    private val stepDelayMs: Long = 0,
    /** How the pause is spent; the default sleeps. Tests inject a fake. */
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    enum class State { RUNNING, PASSED, FAILED, SKIPPED }

    fun interface Listener {
        fun onStep(index: Int, state: State, message: String?)
    }

    private class StepFailed(message: String) : Exception(message)

    /**
     * True when every step passed or was skipped. [cancelled] is polled between steps and while waiting. Only the steps in
     * [range] (default all) run; the listener always gets the index in [steps]. [stepDelayMs] is waited between two steps
     * that run, not before the first one.
     */
    fun run(steps: List<Step>, listener: Listener, cancelled: () -> Boolean = { false }, range: IntRange? = null): Boolean {
        val indices = (range ?: steps.indices).filter { it in steps.indices }
        var ran = false
        for (index in indices) {
            val step = steps[index]
            if (cancelled()) return false
            if (step is ScriptRecorder.Note || step is ScriptRecorder.UnfocusedText) {
                listener.onStep(index, State.SKIPPED, "Comment: nothing to replay")
                continue
            }
            if (ran && stepDelayMs > 0 && !delay(cancelled)) return false
            ran = true
            listener.onStep(index, State.RUNNING, null)
            try {
                perform(step, cancelled)
                listener.onStep(index, State.PASSED, null)
            } catch (e: StepFailed) {
                listener.onStep(index, State.FAILED, e.message)
                return false
            } catch (e: Exception) {
                listener.onStep(index, State.FAILED, e.message ?: e.javaClass.simpleName)
                return false
            }
        }
        return true
    }

    /** Waits [stepDelayMs] in small slices; false when cancelled meanwhile. */
    private fun delay(cancelled: () -> Boolean): Boolean {
        var left = stepDelayMs
        while (left > 0) {
            if (cancelled()) return false
            val slice = minOf(left, DELAY_SLICE_MS)
            pause(slice)
            left -= slice
        }
        return !cancelled()
    }

    private fun perform(step: Step, cancelled: () -> Boolean) {
        when (step) {
            is ScriptRecorder.Tap -> awaitElement(step.target, cancelled).let { session.tap(it.first, it.second) }
            is ScriptRecorder.LongPress -> awaitElement(step.target, cancelled).let { session.longPress(it.first, it.second, step.durationMs) }
            is ScriptRecorder.DoubleTap -> awaitElement(step.target, cancelled).let { session.doubleTap(it.first, it.second) }
            is ScriptRecorder.Fill -> {
                val (x, y) = awaitElement(step.target, cancelled)
                session.tap(x, y)
                clearField(step.target, cancelled)
                session.typeText(step.text)
            }
            is ScriptRecorder.Expect -> expect(step, cancelled)
            is ScriptRecorder.ExpectCount -> expectCount(step, cancelled)
            is ScriptRecorder.WaitFor -> waitFor(step, cancelled)
            is ScriptRecorder.Swipe -> swipe(step.direction)
            is ScriptRecorder.ScrollUntilVisible -> scrollUntilVisible(step, cancelled)
            is ScriptRecorder.Button -> if (step.name == "HOME") session.press(DeviceKey.HOME) else throw StepFailed("Button ${step.name} is not supported")
            is ScriptRecorder.GoBack -> session.press(DeviceKey.BACK)
            else -> Unit
        }
    }

    /** Like Mobilewright's `expect`: retries until the assertion holds or the timeout passes. */
    private fun expect(step: ScriptRecorder.Expect, cancelled: () -> Boolean) {
        val timeoutMs = ReplayOptions.resolveTimeoutMs(step.timeoutMs, elementTimeoutMs)
        val deadline = System.currentTimeMillis() + timeoutMs
        var problem = "element not found"
        while (true) {
            try {
                val node = step.target.resolve(session.hierarchy())
                if (node == null) {
                    if (step.assertion.passesWhenMissing) return
                    problem = "element not found"
                } else {
                    if (step.assertion.holds(node)) return
                    problem = "was ${step.assertion.actual(node)}"
                }
            } catch (e: Exception) {
                problem = "hierarchy: ${e.message?.lineSequence()?.firstOrNull()}"
            }
            if (cancelled()) throw StepFailed("Stopped")
            if (System.currentTimeMillis() >= deadline) {
                throw StepFailed("Expected ${step.target.code()} ${step.assertion.label.replaceFirstChar { it.lowercase() }}, $problem (after ${ReplayOptions.format(timeoutMs)})")
            }
            Thread.sleep(pollMs)
        }
    }

    /** Like `expect(locator).toHaveCount(n)`: counts every match of the locator, retrying until the number is right. */
    private fun expectCount(step: ScriptRecorder.ExpectCount, cancelled: () -> Boolean) {
        val timeoutMs = ReplayOptions.resolveTimeoutMs(step.timeoutMs, elementTimeoutMs)
        val deadline = System.currentTimeMillis() + timeoutMs
        var problem = "element not found"
        while (true) {
            try {
                val count = step.target.countMatches(session.hierarchy())
                if (count == step.count) return
                problem = "was $count"
            } catch (e: Exception) {
                problem = "hierarchy: ${e.message?.lineSequence()?.firstOrNull()}"
            }
            if (cancelled()) throw StepFailed("Stopped")
            if (System.currentTimeMillis() >= deadline) {
                throw StepFailed("Expected ${step.target.withoutNth().code()} to match ${step.count} elements, $problem (after ${ReplayOptions.format(timeoutMs)})")
            }
            Thread.sleep(pollMs)
        }
    }

    /** Like `locator.waitFor({ state })`: polls until the state holds; a missing element counts as hidden. */
    private fun waitFor(step: ScriptRecorder.WaitFor, cancelled: () -> Boolean) {
        val timeoutMs = ReplayOptions.resolveTimeoutMs(step.timeoutMs, elementTimeoutMs)
        val deadline = System.currentTimeMillis() + timeoutMs
        var problem = "element not found"
        while (true) {
            try {
                val node = step.target.resolve(session.hierarchy())
                if (step.state.holds(node)) return
                problem = if (node == null) "element not found" else "element is ${if (node.isVisible) "visible" else "not visible"}"
            } catch (e: Exception) {
                problem = "hierarchy: ${e.message?.lineSequence()?.firstOrNull()}"
            }
            if (cancelled()) throw StepFailed("Stopped")
            if (System.currentTimeMillis() >= deadline) {
                throw StepFailed("Waiting for ${step.target.code()} to be ${step.state.js} timed out, $problem (after ${ReplayOptions.format(timeoutMs)})")
            }
            Thread.sleep(pollMs)
        }
    }

    /** Select all + backspace on the focused field, then read it back: like Mobilewright, a field that keeps its text fails the step. */
    private fun clearField(target: ScriptRecorder.Target, cancelled: () -> Boolean) {
        try {
            TextClearing.clearFocusedText(session)
        } catch (e: UnsupportedOperationOnDevice) {
            throw StepFailed("Cannot clear the field before typing: ${e.message}")
        }
        repeat(CLEAR_CHECKS) { attempt ->
            val node = target.resolve(session.hierarchy()) ?: return // the screen changed under us: nothing to check
            if (isEmptyField(node)) return
            if (cancelled()) throw StepFailed("Stopped")
            if (attempt < CLEAR_CHECKS - 1) Thread.sleep(pollMs)
        }
        throw StepFailed("Failed to clear element ${target.code()}: it still has text after select all + backspace")
    }

    /**
     * Android uiautomator reports an empty field's hint in `text` (mobilecli's dump keeps it in `hint`), so text that
     * equals the hint counts as empty. A uiautomator without a `hint` attribute cannot be told apart: see TODO.md.
     */
    private fun isEmptyField(node: UiNode): Boolean {
        val value = Assertion.inputValue(node).orEmpty()
        return value.isEmpty() || (node.platform == Platform.ANDROID && value == node.attr("hint"))
    }

    /** The center of the visible element, polling the hierarchy until it appears. */
    private fun awaitElement(target: ScriptRecorder.Target, cancelled: () -> Boolean): Pair<Int, Int> {
        val deadline = System.currentTimeMillis() + elementTimeoutMs
        var lastError: String? = null
        while (true) {
            try {
                val node = target.resolve(session.hierarchy())
                val bounds = node?.bounds
                if (node != null && node.isVisible && bounds != null) return bounds.centerX to bounds.centerY
            } catch (e: Exception) {
                lastError = e.message?.lineSequence()?.firstOrNull()
            }
            if (cancelled()) throw StepFailed("Stopped")
            if (System.currentTimeMillis() >= deadline) {
                throw StepFailed("${target.code()} not found within ${elementTimeoutMs / 1000} s" + (lastError?.let { " (hierarchy: $it)" } ?: ""))
            }
            Thread.sleep(pollMs)
        }
    }

    /**
     * Swipes until the element is visible with its center on the screen, checking the hierarchy before the first
     * swipe and after every swipe, at most [ScriptRecorder.ScrollUntilVisible.maxSwipes] swipes.
     */
    private fun scrollUntilVisible(step: ScriptRecorder.ScrollUntilVisible, cancelled: () -> Boolean) {
        var swipes = 0
        var problem = "element not found"
        var readFailedSince = 0L // 0 while the hierarchy reads fine
        while (true) {
            var readOk = true
            try {
                val size = session.logicalSize() ?: throw StepFailed("Screen size unknown")
                val node = step.target.resolve(session.hierarchy())
                if (ScrollUntilVisible.isOnScreen(node, size)) return
                problem = if (node == null) "element not found" else "element is not on the screen"
            } catch (e: StepFailed) {
                throw e
            } catch (e: Exception) {
                readOk = false
                problem = "hierarchy: ${e.message?.lineSequence()?.firstOrNull()}"
            }
            if (cancelled()) throw StepFailed("Stopped")
            if (!readOk) {
                // Never swipe blind: wait for the screen to be readable again, like the other element steps do.
                val now = System.currentTimeMillis()
                if (readFailedSince == 0L) readFailedSince = now
                if (now - readFailedSince >= elementTimeoutMs) {
                    throw StepFailed("${step.target.code()} did not become visible, $problem (after ${elementTimeoutMs / 1000} s)")
                }
                Thread.sleep(pollMs)
                continue
            }
            readFailedSince = 0L
            if (swipes >= step.maxSwipes) {
                throw StepFailed("${step.target.code()} did not become visible after ${step.maxSwipes} swipes ${step.direction}, $problem")
            }
            swipe(step.direction)
            swipes++
            Thread.sleep(pollMs) // let the scroll settle before reading the screen
        }
    }

    /** The finger moves in [direction] over the middle of the screen. */
    private fun swipe(direction: String) {
        val size = session.logicalSize() ?: throw StepFailed("Screen size unknown")
        val w = size.width
        val h = size.height
        when (direction) {
            "up" -> session.swipe(w / 2, h * 7 / 10, w / 2, h * 3 / 10)
            "down" -> session.swipe(w / 2, h * 3 / 10, w / 2, h * 7 / 10)
            "left" -> session.swipe(w * 8 / 10, h / 2, w * 2 / 10, h / 2)
            "right" -> session.swipe(w * 2 / 10, h / 2, w * 8 / 10, h / 2)
            else -> throw StepFailed("Unknown swipe direction $direction")
        }
    }

    private companion object {
        /** How often the field is read back after clearing, in case the keyboard is slow to apply the keys. */
        const val CLEAR_CHECKS = 3
        const val DELAY_SLICE_MS = 100L
    }
}
