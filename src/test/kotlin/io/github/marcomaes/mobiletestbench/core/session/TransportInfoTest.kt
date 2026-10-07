package io.github.marcomaes.mobiletestbench.core.session

import org.junit.Assert.assertEquals
import org.junit.Test

class TransportInfoTest {
    @Test fun withoutFallback() = assertEquals("Transport: emulator gRPC", TransportInfo("emulator gRPC").tooltip())

    @Test fun withFallback() =
        assertEquals("Transport: adb screenshots\nFallback: gRPC failed", TransportInfo("adb screenshots", "gRPC failed").tooltip())

    @Test fun blankReasonIgnored() = assertEquals("Transport: x", TransportInfo("x", "  ").tooltip())

    @Test fun reasonIsOneLineAndCut() {
        assertEquals("a", TransportInfo.oneLine("\n a \nb"))
        assertEquals(300, TransportInfo.oneLine("x".repeat(500)).length)
    }
}
