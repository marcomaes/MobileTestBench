package io.github.marcomaes.mobiletestbench.core.recording

import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.session.DeviceSession

/**
 * Empties the focused text field the way Mobilewright's own driver does (select all, then backspace), because
 * `fill` replaces text. Kept in one place so the method can change per platform.
 */
object TextClearing {
    /**
     * Android takes ctrl+a. iOS needs cmd+a: there ctrl+a is the Emacs "beginning of line", so ctrl+a + backspace
     * would leave the text untouched.
     */
    fun keysFor(platform: Platform): List<String> = when (platform) {
        Platform.ANDROID -> listOf("ctrl+a", "backspace")
        Platform.IOS -> listOf("cmd+a", "backspace")
    }

    /**
     * One key call. It succeeds silently when nothing is focused, so it never proves the field is empty:
     * the caller has to read the field back.
     */
    fun clearFocusedText(session: DeviceSession) = session.pressKeys(keysFor(session.device.platform))
}
