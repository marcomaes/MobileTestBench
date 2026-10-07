package io.github.marcomaes.mobiletestbench.core.recording

/** The optional time limit of a screen recording: a setting in seconds, 0 meaning no limit. */
object ScreenRecordingLimit {
    const val MAX_SECONDS = 3600
    val SECONDS = 0..MAX_SECONDS

    /** The stored setting clamped to [SECONDS]. */
    fun clamp(setting: Int): Int = setting.coerceIn(SECONDS)

    /** Milliseconds after which to stop automatically, or null for no limit. */
    fun limitMs(setting: Int): Long? = clamp(setting).takeIf { it > 0 }?.let { it * 1000L }
}
