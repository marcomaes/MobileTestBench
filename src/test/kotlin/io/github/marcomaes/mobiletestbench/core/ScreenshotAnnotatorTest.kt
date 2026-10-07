package io.github.marcomaes.mobiletestbench.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage

class ScreenshotAnnotatorTest {

    private fun white(w: Int, h: Int) = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { image ->
        for (x in 0 until w) for (y in 0 until h) image.setRGB(x, y, Color.WHITE.rgb)
    }

    @Test
    fun `the outline is scaled from logical to frame pixels and the original is untouched`() {
        val frame = white(200, 400)
        val result = ScreenshotAnnotator.outline(frame, Size(100, 200), Rect(10, 20, 30, 40)) // frame: 20,40 60x80
        assertEquals(Color.WHITE.rgb, frame.getRGB(50, 80))
        assertEquals(200, result.width)
        assertNotEquals(Color.WHITE.rgb and 0xFFFFFF, result.getRGB(50, 80) and 0xFFFFFF) // inside: tinted
        assertNotEquals(Color.WHITE.rgb and 0xFFFFFF, result.getRGB(20, 80) and 0xFFFFFF) // left edge: outline
        assertEquals(Color.WHITE.rgb and 0xFFFFFF, result.getRGB(150, 300) and 0xFFFFFF) // outside: unchanged
    }

    @Test
    fun `an unknown logical size leaves the image as it is`() {
        val result = ScreenshotAnnotator.outline(white(10, 10), Size(0, 0), Rect(0, 0, 5, 5))
        assertEquals(Color.WHITE.rgb and 0xFFFFFF, result.getRGB(2, 2) and 0xFFFFFF)
    }
}
