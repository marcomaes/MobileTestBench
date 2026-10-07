package io.github.marcomaes.mobiletestbench.core

import com.google.gson.Gson
import io.github.marcomaes.mobiletestbench.core.ios.MjpegReader
import io.github.marcomaes.mobiletestbench.core.ios.WdaPayloads
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream

class StreamAndGeometryTest {

    /** Returns at most [chunk] bytes per read to exercise markers split over reads. */
    private class ChunkedStream(input: InputStream, private val chunk: Int) : FilterInputStream(input) {
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, chunk))
    }

    private fun fakeJpeg(seed: Int): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), seed.toByte(), 0x11, 0xFF.toByte(), 0x00, 0x22, 0xFF.toByte(), 0xD9.toByte())

    @Test
    fun `splits mjpeg multipart stream into frames`() {
        val frames = listOf(fakeJpeg(1), fakeJpeg(2), fakeJpeg(3))
        val body = ByteArrayOutputStream()
        for (f in frames) {
            body.write("--BoundaryString\r\nContent-type: image/jpeg\r\nContent-Length: ${f.size}\r\n\r\n".toByteArray())
            body.write(f)
            body.write("\r\n".toByteArray())
        }
        for (chunk in listOf(1, 2, 3, 7, 4096)) {
            val received = mutableListOf<ByteArray>()
            MjpegReader(ChunkedStream(body.toByteArray().inputStream(), chunk)).readFrames { received += it }
            assertEquals("chunk=$chunk", 3, received.size)
            frames.indices.forEach { assertArrayEquals("chunk=$chunk", frames[it], received[it]) }
        }
    }

    @Test
    fun `maps panel to logical coordinates with retina frames`() {
        // iOS: 1170x2532 pixel frame, 390x844 point logical space, drawn in a 400x900 panel.
        val geometry = ScreenGeometry(Size(400, 900), Size(1170, 2532), padding = 0)
        val logical = Size(390, 844)
        val rect = geometry.imageRect
        assertTrue(rect.width <= 400 && rect.height <= 900)
        val (x, y) = geometry.panelToLogical(rect.x + rect.width / 2, rect.y + rect.height / 2, logical)!!
        assertEquals(195.0, x.toDouble(), 2.0)
        assertEquals(422.0, y.toDouble(), 2.0)
        assertNull(geometry.panelToLogical(0, 0, logical).takeIf { rect.x > 0 })
        val highlight = geometry.logicalToPanel(Rect(0, 0, 390, 844), logical)
        assertEquals(rect, highlight)
    }

    @Test
    fun `wda tap payload is w3c actions`() {
        val json = Gson().toJson(WdaPayloads.tap(10, 20))
        assertTrue(json.contains("\"pointerType\":\"touch\""))
        assertTrue(json.contains("\"x\":10"))
        assertTrue(json.contains("\"pointerDown\""))
    }
}
