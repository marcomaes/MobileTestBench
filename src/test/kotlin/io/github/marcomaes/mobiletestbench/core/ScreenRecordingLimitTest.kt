package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.recording.ScreenRecordingLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenRecordingLimitTest {
    @Test fun zeroAndNegativeMeanNoLimit() {
        assertNull(ScreenRecordingLimit.limitMs(0))
        assertNull(ScreenRecordingLimit.limitMs(-5))
    }

    @Test fun convertsSecondsToMillis() {
        assertEquals(30_000L, ScreenRecordingLimit.limitMs(30))
    }

    @Test fun clampsToMaximum() {
        assertEquals(3600, ScreenRecordingLimit.clamp(99999))
        assertEquals(3_600_000L, ScreenRecordingLimit.limitMs(99999))
    }
}
