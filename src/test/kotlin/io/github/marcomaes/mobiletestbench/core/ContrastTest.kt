package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.android.AdbOutput
import io.github.marcomaes.mobiletestbench.core.inspector.Contrast
import io.github.marcomaes.mobiletestbench.core.inspector.ContrastSampler
import io.github.marcomaes.mobiletestbench.core.inspector.DpConverter
import java.awt.Color
import java.awt.image.BufferedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastTest {

    /** A flat [bg] image with [fg] stripes standing in for text, covering about 10 percent. */
    private fun textImage(w: Int, h: Int, fg: Int, bg: Int): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(bg); g.fillRect(0, 0, w, h)
        g.color = Color(fg)
        var x = 4
        while (x < w - 4) { g.fillRect(x, h / 3, 3, h / 3); x += 30 }
        g.dispose()
        return img
    }

    @Test
    fun `wcag ratio of known colors`() {
        assertEquals(21.0, Contrast.ratio(0x000000, 0xFFFFFF), 0.001)
        assertEquals(1.0, Contrast.ratio(0x777777, 0x777777), 0.001)
        assertEquals(4.48, Contrast.ratio(0x777777, 0xFFFFFF), 0.01)
        assertEquals(4.61, Contrast.ratio(0x757575, 0xFFFFFF), 0.01)
        assertEquals(Contrast.ratio(0x123456, 0xFEDCBA), Contrast.ratio(0xFEDCBA, 0x123456), 1e-12)
        assertEquals("#00FF0A", Contrast.hex(0x00FF0A))
    }

    @Test
    fun `dp conversion`() {
        assertEquals(40.0, DpConverter.toDp(105, 420), 1e-9)
        assertEquals(24.0, DpConverter.toDp(63, 420), 1e-9)
        assertEquals("40", DpConverter.format(40.0))
        assertEquals("26.9", DpConverter.format(DpConverter.toDp(71, 420) * 0 + 26.93))
    }

    @Test
    fun `parses wm density and lets an override win`() {
        assertEquals(420, AdbOutput.parseWmDensity("Physical density: 420\n"))
        assertEquals(320, AdbOutput.parseWmDensity("Physical density: 420\nOverride density: 320\n"))
        assertNull(AdbOutput.parseWmDensity("error: device offline"))
    }

    @Test
    fun `measures a known contrast with the exact colors`() {
        val img = textImage(200, 40, 0x757575, 0xFFFFFF)
        val e = ContrastSampler.estimate(img, Size(200, 40), Rect(0, 0, 200, 40))!!
        assertEquals(0x757575, e.foreground)
        assertEquals(0xFFFFFF, e.background)
        assertEquals(4.61, e.ratio, 0.01)
    }

    @Test
    fun `low and high contrast text`() {
        val low = ContrastSampler.estimate(textImage(200, 40, 0xB0B0B0, 0xFFFFFF), Size(200, 40), Rect(0, 0, 200, 40))!!
        assertEquals(2.17, low.ratio, 0.02)
        val dark = ContrastSampler.estimate(textImage(200, 40, 0xFFFFFF, 0x202020), Size(200, 40), Rect(0, 0, 200, 40))!!
        assertTrue(dark.ratio > 14)
        assertEquals(0xFFFFFF, dark.foreground)
    }

    @Test
    fun `maps logical bounds onto a frame with another resolution`() {
        // 3x frame: the left half is dark on white text-like stripes, the right half is a flat color.
        val img = BufferedImage(600, 120, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(0xFFFFFF); g.fillRect(0, 0, 600, 120)
        g.color = Color(0x999999); var x = 10; while (x < 290) { g.fillRect(x, 40, 6, 40); x += 40 }
        g.dispose()
        val left = ContrastSampler.estimate(img, Size(200, 40), Rect(0, 0, 100, 40))!!
        assertEquals(0x999999, left.foreground)
        assertNull("flat region has no text", ContrastSampler.estimate(img, Size(200, 40), Rect(110, 0, 90, 40)))
    }

    @Test
    fun `skips photos gradients tiny regions and mismatching frames`() {
        val gradient = BufferedImage(256, 40, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 256) for (y in 0 until 40) gradient.setRGB(x, y, (x shl 16) or (x shl 8) or x)
        assertNull(ContrastSampler.estimate(gradient, Size(256, 40), Rect(0, 0, 256, 40)))
        val noise = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        val r = java.util.Random(1)
        for (x in 0 until 100) for (y in 0 until 100) noise.setRGB(x, y, r.nextInt(0xFFFFFF))
        assertNull(ContrastSampler.estimate(noise, Size(100, 100), Rect(0, 0, 100, 100)))
        val img = textImage(200, 40, 0, 0xFFFFFF)
        assertNull("too small", ContrastSampler.estimate(img, Size(200, 40), Rect(0, 0, 10, 40)))
        assertNull("too flat in height", ContrastSampler.estimate(img, Size(200, 40), Rect(0, 0, 200, 6)))
        assertNull("aspect differs from the logical size (rotation)", ContrastSampler.estimate(img, Size(40, 200), Rect(0, 0, 40, 100)))
        assertNotNull(ContrastSampler.estimate(img, Size(200, 40), Rect(0, 0, 200, 40)))
    }

    @Test
    fun `an almost invisible text color is not estimated`() {
        assertNull(ContrastSampler.estimate(textImage(200, 40, 0xFAFAFA, 0xFFFFFF), Size(200, 40), Rect(0, 0, 200, 40)))
    }

    @Test
    fun `stray anti aliased pixels do not decide the text color`() {
        val img = textImage(200, 40, 0x333333, 0xFFFFFF)
        for (i in 0 until 3) img.setRGB(100 + i, 2, 0x000000) // three pixels in a different group, below the share limit
        val e = ContrastSampler.estimate(img, Size(200, 40), Rect(0, 0, 200, 40))!!
        assertEquals(0x333333, e.foreground)
    }
}
