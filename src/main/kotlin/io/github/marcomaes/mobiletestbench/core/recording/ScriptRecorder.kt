package io.github.marcomaes.mobiletestbench.core.recording

import io.github.marcomaes.mobiletestbench.core.DeviceKey
import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.inspector.AccessibilityAudit
import io.github.marcomaes.mobiletestbench.core.inspector.HierarchyQueries
import io.github.marcomaes.mobiletestbench.core.inspector.MobilewrightLocators
import io.github.marcomaes.mobiletestbench.core.inspector.MobilewrightLocators.Derived
import io.github.marcomaes.mobiletestbench.core.inspector.ScopedLocators
import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import io.github.marcomaes.mobiletestbench.core.pageobject.PageLocator
import io.github.marcomaes.mobiletestbench.core.pageobject.PageObjectCandidates
import kotlin.math.abs
import io.github.marcomaes.mobiletestbench.core.recording.ScrollUntilVisible as ScrollConversion

/**
 * Turns what the user does on the device into Mobilewright code (`screen.getByTestId('x').tap()`).
 * Not thread safe: call it from one thread, e.g. the input executor.
 */
class ScriptRecorder(val platform: Platform) {

    /**
     * A locator. When it is not unique on the screen it is either scoped by a [parent] locator that is, or carries the
     * index among its matches ([nth]).
     */
    data class Target(
        val locator: Derived,
        val nth: Int? = null,
        val parent: Derived? = null,
        /** The best unique Appium locator of the element, computed at record time; null when it has none. Used for the Appium output only. */
        val appium: PageLocator.Appium? = null,
    ) {
        fun code(): String =
            (if (parent != null) ScopedLocators.chain(parent, locator) else MobilewrightLocators.toCode(locator)) +
                (nth?.let { ".nth($it)" } ?: "")

        /** The element this target points to in [root], counted the way the recorder counted ([nth] over all matches). */
        fun resolve(root: UiNode): UiNode? {
            val all = root.walk().filter { !it.isRoot }.toList()
            val candidates = if (parent != null) {
                val parentNode = all.filter { MobilewrightLocators.matches(it, parent) }.singleOrNull() ?: return null
                ScopedLocators.within(all, parentNode, locator)
            } else {
                all.filter { MobilewrightLocators.matches(it, locator) }
            }
            return candidates.getOrNull(nth ?: 0)
        }

        /** How many elements the locator matches in [root], ignoring [nth]: what Mobilewright's `count()` returns. */
        fun countMatches(root: UiNode): Int {
            val all = root.walk().filter { !it.isRoot }.toList()
            if (parent == null) return all.count { MobilewrightLocators.matches(it, locator) }
            val parentNode = all.filter { MobilewrightLocators.matches(it, parent) }.singleOrNull() ?: return 0
            return ScopedLocators.within(all, parentNode, locator).size
        }

        /** The same locator without the index, for assertions about all matches. */
        fun withoutNth(): Target = copy(nth = null)
    }

    sealed interface Step {
        fun code(): String
    }

    /** [unverified]: matched against a hierarchy from before the previous action, so the locator may be wrong. */
    data class Tap(val target: Target, val unverified: Boolean = false) : Step {
        override fun code() = "await ${target.code()}.tap();" + if (unverified) UNVERIFIED_NOTE else ""
    }

    /** `await loc.longPress({ duration: 700 });` */
    data class LongPress(val target: Target, val durationMs: Long, val unverified: Boolean = false) : Step {
        override fun code() = "await ${target.code()}.longPress({ duration: $durationMs });" + if (unverified) UNVERIFIED_NOTE else ""
    }

    data class DoubleTap(val target: Target, val unverified: Boolean = false) : Step {
        override fun code() = "await ${target.code()}.doubleTap();" + if (unverified) UNVERIFIED_NOTE else ""
    }

    data class Fill(val target: Target, val text: String, val unverified: Boolean = false) : Step {
        override fun code() = "await ${target.code()}.fill(${MobilewrightLocators.js(text)});" + if (unverified) UNVERIFIED_NOTE else ""
    }

    /** `await expect(locator).toHaveText('x');`. Needs `expect` from `@mobilewright/test`. */
    data class Expect(val target: Target, val assertion: Assertion, val timeoutMs: Long? = null) : Step {
        override fun code() = "await expect(${target.code()})${ReplayOptions.matcherWithTimeout(assertion.matcher(), timeoutMs)};"
    }

    /** `await expect(locator).toHaveCount(3);`. The locator is written without `.nth()`: it counts all matches. */
    data class ExpectCount(val target: Target, val count: Int, val timeoutMs: Long? = null) : Step {
        override fun code() = "await expect(${target.withoutNth().code()})${ReplayOptions.matcherWithTimeout(".toHaveCount($count)", timeoutMs)};"
    }

    /** `await locator.waitFor({ state: 'visible' });`: an explicit wait, no assertion. */
    data class WaitFor(val target: Target, val state: WaitState, val timeoutMs: Long? = null) : Step {
        override fun code() = "await ${target.code()}.waitFor({ state: '${state.js}'${timeoutMs?.let { ", timeout: $it" }.orEmpty()} });"
    }

    data class Swipe(val direction: String) : Step {
        override fun code() = "await screen.swipe('$direction');"
    }

    /**
     * Swipe in [direction] until [target] is visible on screen, at most [maxSwipes] times. Made from recorded swipes
     * by [ScrollConversion.convert]. `isVisible()` is the Mobilewright locator method; it was not run against Mobilewright.
     */
    data class ScrollUntilVisible(val target: Target, val direction: String, val maxSwipes: Int) : Step {
        override fun code() =
            "for (let i = 0; i < $maxSwipes && !(await ${target.code()}.isVisible()); i++) { await screen.swipe('$direction'); }"
    }

    data class Button(val name: String) : Step {
        override fun code() = "await screen.pressButton('$name');"
    }

    data object GoBack : Step {
        override fun code() = "await screen.goBack();"
    }

    /** Typed text with no element tapped before it, so there is nothing to `fill`. */
    data class UnfocusedText(val text: String) : Step {
        override fun code() = "// typed ${MobilewrightLocators.js(text)}: tap the field first so it can be recorded as fill()"
    }

    data class Note(val text: String) : Step {
        override fun code() = "// $text"
    }

    private val recorded = mutableListOf<Step>()

    val steps: List<Step> get() = recorded
    val isEmpty: Boolean get() = recorded.isEmpty()

    fun code(): String = recorded.joinToString("\n") { it.code() }

    fun clear() = recorded.clear()

    /**
     * Replaces the swipe steps at [indices] and the element step after them by one scroll-until-visible step plus
     * that element step. Returns null on success, or why it cannot be done.
     */
    fun scrollUntilVisible(indices: Collection<Int>): String? {
        return when (val result = ScrollConversion.convert(recorded, indices)) {
            is ScrollConversion.Result.Converted -> {
                recorded.clear()
                recorded += result.steps
                null
            }
            is ScrollConversion.Result.Rejected -> result.reason
        }
    }

    /** Sets direction and swipe limit of the scroll step at [index]. Returns false when there is no such step or the values are not allowed. */
    fun setScrollOptions(index: Int, direction: String, maxSwipes: Int): Boolean {
        val step = recorded.getOrNull(index) as? ScrollUntilVisible ?: return false
        recorded[index] = ScrollConversion.withOptions(step, direction, maxSwipes) ?: return false
        return true
    }

    /** A tap at the logical point (x, y); [root] is the hierarchy as it was before the tap. */
    fun tap(root: UiNode, x: Int, y: Int, verified: Boolean = true) {
        recorded += pointStep(root, x, y, "tap") { Tap(it, unverified = !verified) }
    }

    /** A long press of [durationMs] at (x, y), tied to an element the same way a tap is. */
    fun longPress(root: UiNode, x: Int, y: Int, durationMs: Long, verified: Boolean = true) {
        recorded += pointStep(root, x, y, "long press") { LongPress(it, durationMs, unverified = !verified) }
    }

    /**
     * A double tap. The mirror sends the first click as a tap before it knows it is a double click, so a tap on the
     * same element right before is the same gesture and is replaced.
     */
    fun doubleTap(root: UiNode, x: Int, y: Int, verified: Boolean = true) {
        val step = pointStep(root, x, y, "double tap") { DoubleTap(it, unverified = !verified) }
        val last = recorded.lastOrNull()
        if (step is DoubleTap && last is Tap && last.target == step.target) replaceLast(step) else recorded += step
    }

    /** Mobilewright has no drag API (only `screen.swipe` and `screen.gesture`), so a drag with hold is kept as a comment. */
    fun dragWithHold(fromX: Int, fromY: Int, toX: Int, toY: Int, holdMs: Long) {
        recorded += Note("drag with hold ($holdMs ms) from ($fromX, $fromY) to ($toX, $toY): Mobilewright has no drag API, not replayable")
    }

    private fun pointStep(root: UiNode, x: Int, y: Int, what: String, make: (Target) -> Step): Step {
        val node = HierarchyQueries.deepestAt(root, x, y)
        val target = node?.let { targetFor(it, root) }
        return when {
            target != null -> make(target)
            node != null -> Note("$what at ($x, $y) on ${node.type}: no locator, give the element a test id or label")
            else -> Note("$what at ($x, $y): no element found there")
        }
    }

    /** The element under (x, y) and the locator the recorder would use for it. */
    data class Picked(val node: UiNode, val target: Target, val matchCount: Int = 1)

    fun pick(root: UiNode, x: Int, y: Int): Picked? {
        val node = HierarchyQueries.deepestAt(root, x, y) ?: return null
        val target = targetFor(node, root) ?: return null
        // The locator may resolve to a parent of the element under the finger (a button around its text).
        return Picked(target.resolve(root) ?: node, target, target.countMatches(root))
    }

    fun expect(target: Target, assertion: Assertion) {
        recorded += Expect(target, assertion)
    }

    /** Records whatever an Assert menu entry stands for: an assertion, a wait or a count. */
    fun add(target: Target, check: Check) {
        recorded += check.toStep(target)
    }

    /** Sets or removes (null) the own timeout of the wait or expect step at [index]. Returns false when there is no such step. */
    fun setTimeout(index: Int, timeoutMs: Long?): Boolean {
        val step = recorded.getOrNull(index)?.takeIf { ReplayOptions.supportsTimeout(it) } ?: return false
        recorded[index] = ReplayOptions.withTimeout(step, timeoutMs)
        return true
    }

    /** Called when the hierarchy could not be read, so the tap cannot be tied to an element. */
    fun tapWithoutHierarchy(x: Int, y: Int, reason: String?, what: String = "tap") {
        recorded += Note("$what at ($x, $y): hierarchy unavailable${reason?.let { " ($it)" }.orEmpty()}")
    }

    fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int) {
        val dx = toX - fromX
        val dy = toY - fromY
        // The direction is the finger's: swipe up scrolls the content down.
        val direction = if (abs(dy) >= abs(dx)) (if (dy < 0) "up" else "down") else (if (dx < 0) "left" else "right")
        recorded += Swipe(direction)
    }

    fun type(text: String) {
        when (val last = recorded.lastOrNull()) {
            is Tap -> replaceLast(Fill(last.target, text, last.unverified))
            is Fill -> replaceLast(Fill(last.target, last.text + text, last.unverified))
            is UnfocusedText -> replaceLast(UnfocusedText(last.text + text))
            else -> recorded += UnfocusedText(text)
        }
    }

    fun delete() {
        when (val last = recorded.lastOrNull()) {
            is Fill -> replaceLast(if (last.text.length > 1) Fill(last.target, last.text.dropLast(1), last.unverified) else Tap(last.target, last.unverified))
            is UnfocusedText -> if (last.text.length > 1) replaceLast(UnfocusedText(last.text.dropLast(1))) else recorded.removeLast()
            else -> recorded += Note("pressed Backspace")
        }
    }

    fun key(key: DeviceKey) {
        recorded += when {
            key == DeviceKey.HOME -> Button("HOME")
            key == DeviceKey.BACK && platform == Platform.ANDROID -> GoBack
            else -> Note("pressed ${key.name}")
        }
    }

    private fun replaceLast(step: Step) {
        recorded[recorded.lastIndex] = step
    }

    companion object {
        /** How many levels above the tapped element are considered, e.g. a text inside a button. */
        private const val MAX_CLIMB = 3

        /** How much bigger than the tapped element a parent may be to still count as "the same control". */
        private const val MAX_CLIMB_AREA_RATIO = 16
        private const val UNVERIFIED_NOTE = "  // check: matched against the screen before the previous action"

        /**
         * The most stable locator for what the user meant to tap. The tapped element and up to [MAX_CLIMB]
         * ancestors (stopping at the first control or cell) are candidates: a unique test id wins, then any
         * unique locator, then the same locator inside a parent that is unique, then the first locator with its index among duplicates.
         */
        fun targetFor(node: UiNode, root: UiNode): Target? {
            val target = mobilewrightTarget(node, root) ?: return null
            val element = target.resolve(root) ?: node
            return target.copy(appium = PageObjectCandidates.appiumLocatorFor(element, root))
        }

        private fun mobilewrightTarget(node: UiNode, root: UiNode): Target? {
            val all = root.walk().filter { !it.isRoot }.toList()
            val nodeArea = node.bounds?.area ?: 0L
            val candidates = buildList {
                var current: UiNode? = node
                while (current != null && !current.isRoot && size <= MAX_CLIMB) {
                    // A parent far bigger than the tapped element is a container (a list), not the thing that was tapped.
                    if (current !== node && nodeArea > 0 && (current.bounds?.area ?: Long.MAX_VALUE) > nodeArea * MAX_CLIMB_AREA_RATIO) break
                    add(current)
                    if (AccessibilityAudit.isInteractive(current) || current.type.equals("Cell", ignoreCase = true)) break
                    current = current.parent
                }
            }
            fun unique(n: UiNode, testIdOnly: Boolean): Derived? = MobilewrightLocators.deriveAll(n).firstOrNull { d ->
                (!testIdOnly || d.kind == MobilewrightLocators.Kind.TEST_ID) && all.count { MobilewrightLocators.matches(it, d) } == 1
            }
            candidates.firstNotNullOfOrNull { n -> unique(n, testIdOnly = true)?.let { Target(it) } }?.let { return it }
            candidates.firstNotNullOfOrNull { n -> unique(n, testIdOnly = false)?.let { Target(it) } }?.let { return it }
            // Inside a parent: a test id (of the button around the tapped text, say) beats a text locator of the tapped text.
            val scopedCandidates = candidates.mapNotNull { ScopedLocators.forNode(it, root) }
            (scopedCandidates.firstOrNull { it.child.kind == MobilewrightLocators.Kind.TEST_ID } ?: scopedCandidates.firstOrNull())
                ?.let { return Target(it.child, parent = it.parent) }
            val first = candidates.firstOrNull { MobilewrightLocators.derive(it) != null } ?: return null
            val derived = MobilewrightLocators.derive(first)!!
            val matches = all.filter { MobilewrightLocators.matches(it, derived) }
            return Target(derived, matches.indexOf(first))
        }
    }
}
