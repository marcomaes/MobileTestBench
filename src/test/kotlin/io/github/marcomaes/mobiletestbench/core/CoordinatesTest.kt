package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.coords.CoordinateMapper
import io.github.marcomaes.mobiletestbench.core.coords.CoordinateText
import io.github.marcomaes.mobiletestbench.core.coords.ElementInfo
import io.github.marcomaes.mobiletestbench.core.coords.GridSteps
import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinatesTest {
    // 100x200 panel without padding, frame is 2x the logical size (retina).
    private val geometry = ScreenGeometry(Size(100, 200), Size(200, 400), padding = 0)
    private val logical = Size(100, 200)
    private val frame = Size(200, 400)

    @Test fun mapsPanelToLogicalFrameAndPercent() {
        val p = CoordinateMapper.map(geometry, 50, 100, logical, frame)!!
        assertEquals(50, p.x); assertEquals(100, p.y)
        assertEquals(100, p.frameX); assertEquals(200, p.frameY)
        assertEquals(50.5, p.percentX, 0.001); assertEquals(50.25, p.percentY, 0.001)
        assertTrue(p.frameDiffers)
    }

    @Test fun outsideTheImageIsNull() {
        val g = ScreenGeometry(Size(300, 200), Size(100, 200), padding = 0)
        assertNull(CoordinateMapper.map(g, 5, 100, Size(100, 200), Size(100, 200)))
        assertNotNull(CoordinateMapper.map(g, 150, 100, Size(100, 200), Size(100, 200)))
    }

    @Test fun sameSizeFrameDoesNotDiffer() {
        val g = ScreenGeometry(Size(100, 200), Size(100, 200), padding = 0)
        val p = CoordinateMapper.map(g, 10, 20, Size(100, 200), Size(100, 200))!!
        assertFalse(p.frameDiffers)
    }

    @Test fun logicalPointRoundTripsThroughThePanel() {
        val (px, py) = geometry.logicalToPanelPoint(30, 40, logical)
        val back = geometry.panelToLogical(px, py, logical)
        assertEquals(30 to 40, back)
    }

    @Test fun letterboxedGeometryKeepsPercentRelativeToTheImage() {
        val g = ScreenGeometry(Size(400, 200), Size(100, 200), padding = 0) // image 100 wide centred at x=150
        val corner = CoordinateMapper.map(g, 150, 0, Size(100, 200), Size(100, 200))!!
        assertEquals(0, corner.x)
        assertEquals(0, corner.y)
        val middle = CoordinateMapper.map(g, 200, 100, Size(100, 200), Size(100, 200))!!
        assertEquals(50, middle.x)
        assertEquals(100, middle.y)
        assertEquals(50.0, middle.percentX, 1.0)
        assertEquals(50.0, middle.percentY, 1.0)
        val far = CoordinateMapper.map(g, 249, 199, Size(100, 200), Size(100, 200))!!
        assertEquals(99, far.x)
        assertEquals(199, far.y)
        assertEquals(99.0, far.percentX, 1.5)
        assertEquals(99.5, far.percentY, 1.5)
        assertNull(CoordinateMapper.map(g, 100, 100, Size(100, 200), Size(100, 200))) // left black bar
        assertNull(CoordinateMapper.map(g, 300, 100, Size(100, 200), Size(100, 200))) // right black bar
    }

    @Test fun textFormats() {
        val p = CoordinateMapper.map(geometry, 50, 100, logical, frame)!!
        assertEquals("50, 100", CoordinateText.xy(p))
        assertEquals("50.5%, 50.3%", CoordinateText.percent(p))
        val e = ElementInfo("Button", Rect(10, 20, 200, 44))
        assertEquals("200 x 44", CoordinateText.size(e))
        assertEquals("10, 20, 200, 44", CoordinateText.bounds(e))
    }

    @Test fun infoLineParts() {
        val p = CoordinateMapper.map(geometry, 50, 100, logical, frame)!!
        val line = CoordinateText.infoLine(p, Platform.IOS, ElementInfo("Button", Rect(0, 0, 10, 20)), true)
        assertTrue(line, line.startsWith("x 50, y 100 pt"))
        assertTrue(line, "frame 100, 200 px" in line)
        assertTrue(line, "Button  10 x 20" in line)
        val android = CoordinateText.infoLine(CoordinateMapper.map(ScreenGeometry(Size(100, 200), Size(100, 200), 0), 5, 6, logical, logical)!!, Platform.ANDROID, null, false)
        assertTrue(android, "px" in android && "frame" !in android && "open the inspector" in android)
        assertTrue(CoordinateText.infoLine(null, null, null, false).startsWith("Move the mouse"))
    }

    @Test fun elementInfoFromNode() {
        val node = UiNode(Platform.ANDROID, "android.widget.Button", mapOf("text" to "Go"), Rect(1, 2, 3, 4))
        val info = ElementInfo.of(node)!!
        assertEquals(Rect(1, 2, 3, 4), info.bounds)
        assertTrue(info.description.startsWith("Button"))
        assertNull(ElementInfo.of(UiNode(Platform.ANDROID, "x", emptyMap(), null)))
        assertNull(ElementInfo.of(null))
    }

    @Test fun gridStepKeepsAboutEightToTwelveLines() {
        assertEquals(100, GridSteps.choose(Size(393, 852)))   // iPhone points
        assertEquals(200, GridSteps.choose(Size(1080, 2400))) // Pixel pixels
        assertEquals(200, GridSteps.choose(Size(1600, 2560)))
        for (size in listOf(Size(393, 852), Size(1080, 2400), Size(1440, 3120), Size(320, 480), Size(2560, 1600))) {
            val n = GridSteps.lines(maxOf(size.width, size.height), GridSteps.choose(size)).size
            assertTrue("$size has $n lines", n in 5..GridSteps.MAX_LINES)
        }
    }

    @Test fun gridLinesAreInsideTheExtent() {
        assertEquals(listOf(100, 200), GridSteps.lines(300, 100))
        assertEquals(listOf(100, 200), GridSteps.lines(301, 100).take(2))
        assertEquals(emptyList<Int>(), GridSteps.lines(100, 100))
        assertEquals(emptyList<Int>(), GridSteps.lines(0, 50))
    }
}
