package io.github.marcomaes.mobiletestbench.core.freeze

/**
 * Whether the mirror is frozen, plus a generation number so that work started for one freeze (the
 * background hierarchy capture) is dropped when the freeze was turned off or replaced in the meantime.
 */
class FreezeState {
    @Volatile var frozen: Boolean = false
        private set

    @Volatile private var generation = 0

    /** Turns the freeze on and returns its generation; while already frozen it keeps the current one. */
    @Synchronized
    fun freeze(): Int {
        if (!frozen) {
            frozen = true
            generation++
        }
        return generation
    }

    @Synchronized
    fun unfreeze() {
        frozen = false
        generation++
    }

    /** True while the freeze that returned [token] is still the active one. */
    fun isCurrent(token: Int): Boolean = frozen && token == generation
}
