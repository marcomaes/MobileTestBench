package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.exec.CommandResult
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import io.github.marcomaes.mobiletestbench.core.ios.IosMobilecliSession
import io.github.marcomaes.mobiletestbench.core.ios.MobilecliClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.awt.image.BufferedImage
import java.time.Duration

class OrientationTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    @Test fun parsesGetFixture() {
        assertEquals(Orientation.LANDSCAPE, Orientation.parse(fixture("mobilecli-orientation-get.json")))
    }

    @Test fun errorEnvelopeThrowsWithMobilecliText() {
        try {
            Orientation.parse(fixture("mobilecli-orientation-error.json"))
            fail()
        } catch (e: MobilecliException) {
            assertTrue(e.message!!.contains("invalid orientation value 'sideways'"))
        }
    }

    @Test fun unknownOrMissingValueThrows() {
        for (bad in listOf("""{"status":"ok","data":{"orientation":"upside"}}""", """{"status":"ok"}""", "garbage")) {
            try {
                Orientation.parse(bad)
                fail(bad)
            } catch (e: MobilecliException) {
            }
        }
    }

    @Test fun commandLines() {
        assertEquals(
            listOf("mobilecli", "device", "orientation", "set", "landscape", "--device", "Pixel_9"),
            MobilecliJson.commandLine("mobilecli", "Pixel_9", Orientation.setArgs(Orientation.LANDSCAPE)),
        )
        assertEquals(listOf("device", "orientation", "get"), Orientation.getArgs())
        assertEquals(Orientation.PORTRAIT, Orientation.LANDSCAPE.other)
    }

    @Test fun portraitFrameIsRotatedCounterClockwiseForLandscape() {
        val frame = BufferedImage(2, 4, BufferedImage.TYPE_INT_RGB)
        frame.setRGB(1, 0, 0xFF0000) // top right corner
        val out = FrameOrientation.upright(frame, Size(4, 2))
        assertEquals(4, out.width)
        assertEquals(2, out.height)
        // counter-clockwise: the top right corner ends up top left
        assertEquals(0xFF0000, out.getRGB(0, 0) and 0xFFFFFF)
    }

    @Test fun otherCombinationsAreUntouched() {
        val portrait = BufferedImage(2, 4, BufferedImage.TYPE_INT_RGB)
        assertSame(portrait, FrameOrientation.upright(portrait, Size(2, 4)))
        assertSame(portrait, FrameOrientation.upright(portrait, null))
        val wide = BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB)
        assertSame(wide, FrameOrientation.upright(wide, Size(4, 2)))
    }

    /** `device info` answers with the queued screen sizes in order; the last one repeats. A null entry is a failed call. */
    private class InfoRunner(private val sizes: List<Pair<Int, Int>?>) : CommandRunner(emptyMap()) {
        private var calls = 0
        override fun run(command: List<String>, timeout: Duration): CommandResult {
            val size = sizes[minOf(calls++, sizes.size - 1)] ?: return CommandResult(1, ByteArray(0), "agent busy")
            val json = """{"status":"ok","data":{"device":{"screenSize":{"width":${size.first},"height":${size.second},"scale":3}}}}"""
            return CommandResult(0, json.toByteArray(), "")
        }
    }

    private fun iosSession(runner: CommandRunner) = IosMobilecliSession(
        Device("F2368465", "iPhone 17 Pro", Platform.IOS, DeviceKind.SIMULATOR), MobilecliClient("mobilecli", runner),
    )

    @Test fun refreshSizeFollowsTheRotation() {
        val session = iosSession(InfoRunner(listOf(402 to 874, 874 to 402)))
        assertEquals(null, session.logicalSize())
        session.refreshSize()
        assertEquals(Size(402, 874), session.logicalSize())
        session.refreshSize() // after rotating to landscape
        assertEquals(Size(874, 402), session.logicalSize())
    }

    @Test fun failedRefreshKeepsTheLastKnownSize() {
        val session = iosSession(InfoRunner(listOf(402 to 874, null)))
        session.refreshSize()
        session.refreshSize()
        assertEquals(Size(402, 874), session.logicalSize())
    }
}
