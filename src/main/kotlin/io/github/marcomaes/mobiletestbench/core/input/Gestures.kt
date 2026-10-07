package io.github.marcomaes.mobiletestbench.core.input

import kotlin.math.hypot

/** How the mirror turns mouse events into device gestures. Pure, so the rules are unit tested. */
object Gestures {
    /**
     * How long the mouse stays down before it counts as a long press, and the hold the device gets. Android's own
     * long press timeout is about 400 ms and iOS's 500 ms; anything shorter than 700 ms was flaky on the emulator.
     */
    const val LONG_PRESS_MS = 700L

    /** Allowed range of the long press setting (ms). */
    val LONG_PRESS_RANGE = 300..3000

    /** The long press time to use for a stored setting value: clamped to [LONG_PRESS_RANGE]. */
    fun longPressMs(setting: Int): Long = setting.coerceIn(LONG_PRESS_RANGE).toLong()

    /** The initial hold of an Option/Alt drag. */
    const val HOLD_DRAG_MS = 700L

    enum class Release {
        /** A long press already went out while the button was down: the release does nothing. */
        CONSUMED,
        TAP,
        DOUBLE_TAP,
        SWIPE,
        HOLD_DRAG,
    }

    fun movedBeyondSlop(dx: Double, dy: Double, slop: Double): Boolean = hypot(dx, dy) >= slop

    /**
     * What a mouse release means. [clickCount] is Swing's count of quick clicks: the second click is a double tap,
     * the third a plain tap again. [alt] is Option/Alt held when the button went down.
     */
    fun onRelease(longPressFired: Boolean, moved: Boolean, alt: Boolean, clickCount: Int): Release = when {
        longPressFired -> Release.CONSUMED
        moved -> if (alt) Release.HOLD_DRAG else Release.SWIPE
        clickCount >= 2 && clickCount % 2 == 0 -> Release.DOUBLE_TAP
        else -> Release.TAP
    }
}
