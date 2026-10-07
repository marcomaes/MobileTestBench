package io.github.marcomaes.mobiletestbench.core.visual

import io.github.marcomaes.mobiletestbench.core.Rect
import io.github.marcomaes.mobiletestbench.core.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.Date

class VisualTest {

    private fun image(w: Int, h: Int, rgb: Int = 0xFFFFFF) = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { img ->
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, rgb)
    }

    private fun BufferedImage.fill(r: Rect, rgb: Int) {
        for (y in r.y until r.y + r.height) for (x in r.x until r.x + r.width) setRGB(x, y, rgb)
    }

    @Test fun identicalImagesMatch() {
        val r = ImageDiff.compare(image(40, 30), image(40, 30))!!
        assertTrue(r.matches)
        assertEquals(0L, r.changedPixels)
        assertEquals(1200L, r.comparedPixels)
        assertEquals("Match: no differences", r.verdict())
    }

    @Test fun differentSizesCannotBeCompared() {
        assertNull(ImageDiff.compare(image(40, 30), image(30, 40)))
    }

    @Test fun solidBlockIsOneRegionWithPercent() {
        val b = image(100, 100)
        val c = image(100, 100)
        c.fill(Rect(10, 20, 20, 10), 0x000000)
        val r = ImageDiff.compare(b, c)!!
        assertEquals(listOf(Rect(10, 20, 20, 10)), r.regions)
        assertEquals(200L, r.changedPixels)
        assertEquals(2.0, r.percent, 0.0001)
        assertEquals("1 region, 2.00% of the pixels changed", r.verdict())
    }

    @Test fun toleranceIsPerChannel() {
        val b = image(10, 10, 0x808080)
        val c = image(10, 10, 0x808080)
        c.fill(Rect(0, 0, 10, 10), 0x888080) // red +8
        assertTrue(ImageDiff.compare(b, c, DiffOptions(tolerance = 8))!!.matches)
        assertFalse(ImageDiff.compare(b, c, DiffOptions(tolerance = 7))!!.matches)
        assertFalse(ImageDiff.compare(b, c, DiffOptions(tolerance = 0))!!.matches)
    }

    @Test fun nearbyChangesMergeAndFarOnesStaySeparate() {
        val b = image(200, 100)
        val c = image(200, 100)
        c.fill(Rect(10, 10, 5, 5), 0)
        c.fill(Rect(20, 10, 5, 5), 0)   // 5px gap: merged
        c.fill(Rect(150, 70, 6, 6), 0)  // far away
        val r = ImageDiff.compare(b, c, DiffOptions(mergeGap = 8))!!
        assertEquals(listOf(Rect(10, 10, 15, 5), Rect(150, 70, 6, 6)), r.regions)
    }

    @Test fun mergingFollowsTheBlockGridNotTheExactDistance() {
        fun regions(x1: Int, x2: Int): Int {
            val c = image(64, 8)
            c.fill(Rect(x1, 0, 1, 4), 0)
            c.fill(Rect(x2, 0, 1, 4), 0)
            return ImageDiff.compare(image(64, 8), c, DiffOptions(mergeGap = 8))!!.regions.size
        }
        assertEquals(1, regions(0, 15))  // 15 px apart, neighbouring blocks 0 and 1: merged
        assertEquals(2, regions(7, 16))  // 9 px apart but blocks 0 and 2: separate
        assertEquals(2, regions(0, 16))  // blocks 0 and 2
    }

    @Test fun tinyRegionsAreIgnored() {
        val b = image(60, 60)
        val c = image(60, 60)
        c.setRGB(5, 5, 0)
        c.setRGB(6, 5, 0)
        c.fill(Rect(30, 30, 10, 10), 0)
        val r = ImageDiff.compare(b, c, DiffOptions(minRegionPixels = 4))!!
        assertEquals(listOf(Rect(30, 30, 10, 10)), r.regions)
        assertEquals(100L, r.changedPixels)
        assertFalse(r.mask[5 * 60 + 5])
    }

    @Test fun oneStepEdgeShiftIsForgivenOnlyWithAntiAliasing() {
        // A vertical black bar whose edge moves by one pixel.
        val b = image(40, 20)
        val c = image(40, 20)
        b.fill(Rect(0, 0, 20, 20), 0)
        c.fill(Rect(0, 0, 21, 20), 0)
        assertTrue(ImageDiff.compare(b, c, DiffOptions(antiAliasing = true, minRegionPixels = 1))!!.matches)
        assertFalse(ImageDiff.compare(b, c, DiffOptions(antiAliasing = false, minRegionPixels = 1))!!.matches)
    }

    @Test fun twoPixelShiftIsStillAChange() {
        val b = image(40, 20)
        val c = image(40, 20)
        b.fill(Rect(0, 0, 20, 20), 0)
        c.fill(Rect(0, 0, 23, 20), 0)
        assertFalse(ImageDiff.compare(b, c, DiffOptions(minRegionPixels = 1))!!.matches)
    }

    @Test fun solidColourChangeIsNotForgiven() {
        val r = ImageDiff.compare(image(20, 20, 0xFFFFFF), image(20, 20, 0x000000))!!
        assertEquals(400L, r.changedPixels)
        assertEquals(100.0, r.percent, 0.0001)
    }

    @Test fun ignoreRegionsAreSkippedAndNotCounted() {
        val b = image(100, 50)
        val c = image(100, 50)
        c.fill(Rect(0, 0, 100, 10), 0)       // "status bar"
        c.fill(Rect(40, 30, 10, 10), 0)
        val r = ImageDiff.compare(b, c, ignore = listOf(Rect(0, 0, 100, 10)))!!
        assertEquals(listOf(Rect(40, 30, 10, 10)), r.regions)
        assertEquals(4000L, r.comparedPixels)
        assertEquals(2.5, r.percent, 0.0001)
        assertTrue(ImageDiff.compare(b, c, ignore = listOf(Rect(0, 0, 100, 10), Rect(40, 30, 10, 10)))!!.matches)
    }

    @Test fun ignoreRectOutsideTheImageIsHarmless() {
        val r = ImageDiff.compare(image(10, 10), image(10, 10), ignore = listOf(Rect(-5, -5, 100, 100), Rect(50, 50, 5, 5)))!!
        assertTrue(r.matches)
        assertEquals(0L, r.comparedPixels)
        assertEquals(0.0, r.percent, 0.0)
    }

    @Test fun renderPaintsChangedPixelsRedOverFadedCurrent() {
        val b = image(10, 10)
        val c = image(10, 10, 0x000000)
        c.fill(Rect(0, 0, 10, 10), 0x000000)
        val b2 = image(10, 10)
        val cur = image(10, 10)
        cur.fill(Rect(2, 2, 4, 4), 0)
        val r = ImageDiff.compare(b2, cur)!!
        val out = ImageDiff.render(cur, r)
        assertEquals(0xFFFF0000.toInt(), out.getRGB(3, 3))
        assertEquals(0xFFFFFFFF.toInt(), out.getRGB(8, 8))
        // Unchanged dark pixels would be faded, not black.
        val faded = ImageDiff.render(c, ImageDiff.compare(b, b)!!)
        assertTrue((faded.getRGB(0, 0) and 0xFF) > 0x60)
    }

    @Test fun scaleMapsRectsBetweenSpaces() {
        assertEquals(Rect(10, 20, 30, 40), ImageDiff.scale(Rect(20, 40, 60, 80), Size(200, 400), Size(100, 200)))
        assertEquals(Rect(1, 2, 3, 4), ImageDiff.scale(Rect(1, 2, 3, 4), Size(10, 10), Size(10, 10)))
        assertEquals(Rect(1, 2, 3, 4), ImageDiff.scale(Rect(1, 2, 3, 4), Size(0, 0), Size(10, 10)))
        assertEquals(1, ImageDiff.scale(Rect(0, 0, 1, 1), Size(1000, 1000), Size(10, 10)).width)
    }

    // region names
    @Test fun sanitizeKeepsSafeCharactersOnly() {
        assertEquals("Pixel_9", BaselineNames.sanitize("Pixel_9"))
        assertEquals("a_b_c", BaselineNames.sanitize("a/b\\c"))
        assertEquals("iPhone 17 Pro", BaselineNames.sanitize("  iPhone   17 Pro "))
        assertEquals("baseline", BaselineNames.sanitize("   "))
        assertEquals("baseline", BaselineNames.sanitize("..."))
        assertFalse(BaselineNames.sanitize("../x").contains("/"))
    }

    @Test fun defaultNameUsesAppAndDate() {
        val d = Date(0L)
        assertTrue(BaselineNames.defaultName(null, d).startsWith("screen-19"))
        assertTrue(BaselineNames.defaultName("com.example.app", d).startsWith("com.example.app-19"))
    }

    @Test fun parseRect() {
        assertEquals(Rect(0, 0, 100, 40), BaselineNames.parseRect("0, 0, 100, 40"))
        assertEquals(Rect(1, 2, 3, 4), BaselineNames.parseRect("1 2 3 4"))
        assertNull(BaselineNames.parseRect("1,2,3"))
        assertNull(BaselineNames.parseRect("1,2,0,4"))
        assertNull(BaselineNames.parseRect("-1,2,3,4"))
        assertNull(BaselineNames.parseRect("a,b,c,d"))
    }
    // endregion

    // region store
    private fun tempStore(): Pair<BaselineStore, File> {
        val dir = Files.createTempDirectory("baselines").toFile()
        dir.deleteOnExit()
        return BaselineStore(dir) to dir
    }

    @Test fun saveListLoadDelete() {
        val (store, dir) = tempStore()
        val img = image(30, 20, 0x336699)
        val saved = store.save("Pixel 9", "home", img, Size(15, 10), now = 1234L)
        assertEquals(File(dir, "Pixel 9/home.png"), saved.png)
        assertTrue(File(dir, "Pixel 9/home.json").isFile)
        val list = store.list("Pixel 9")
        assertEquals(listOf("home"), list.map { it.name })
        assertEquals(Size(15, 10), list[0].info.logicalSize)
        assertEquals(Size(30, 20), list[0].info.frameSize)
        assertEquals(1234L, list[0].info.savedAt)
        assertEquals(0x336699, store.load(list[0]).getRGB(5, 5) and 0xFFFFFF)
        assertTrue(store.list("other").isEmpty())
        store.delete(list[0])
        assertTrue(store.list("Pixel 9").isEmpty())
        assertFalse(File(dir, "Pixel 9/home.json").exists())
    }

    @Test fun ignoreRegionsPersistAndSurviveResave() {
        val (store, _) = tempStore()
        val saved = store.save("d", "n", image(10, 10), null)
        assertEquals(Size(10, 10), saved.info.logicalSize)
        store.updateIgnore(saved, listOf(Rect(0, 0, 10, 2), Rect(1, 2, 3, 4)))
        assertEquals(listOf(Rect(0, 0, 10, 2), Rect(1, 2, 3, 4)), store.list("d")[0].info.ignoreRects)
        store.save("d", "n", image(10, 10, 0), null)
        assertEquals(2, store.list("d")[0].info.ignoreRects.size)
    }

    @Test fun missingOrBrokenSidecarStillListsTheBaseline() {
        val (store, dir) = tempStore()
        store.save("d", "n", image(4, 4), null)
        File(dir, "d/n.json").writeText("{ not json")
        val b = store.list("d").single()
        assertEquals("n", b.name)
        assertEquals(0, b.info.logicalWidth)
        File(dir, "d/n.json").delete()
        assertNotNull(store.list("d").single())
    }

    @Test fun namesWithPathCharactersStayInsideTheDeviceFolder() {
        val (store, dir) = tempStore()
        val saved = store.save("d", "../../evil", image(4, 4), null)
        assertEquals(File(dir, "d"), saved.png.parentFile)
    }
    // endregion

    @Test fun renameErrorRules() {
        val existing = listOf("home", "Login")
        assertEquals("Enter a name", BaselineNames.renameError("  ", "home", existing))
        assertEquals("That is already the name", BaselineNames.renameError("home", "home", existing))
        assertTrue(BaselineNames.renameError("login", "home", existing)!!.contains("already exists"))
        assertTrue(BaselineNames.renameError("a/b", "home", listOf("home", "a_b"))!!.contains("a_b"))
        assertNull(BaselineNames.renameError("settings", "home", existing))
        assertNull(BaselineNames.renameError("Home", "home", existing))
    }

    @Test fun defaultNameFromAppIsSanitized() {
        assertTrue(BaselineNames.defaultName("com/example app", Date(0)).startsWith("com_example app-19"))
    }

    @Test fun renameMovesImageAndSidecarAndKeepsIgnore() {
        val (store, dir) = tempStore()
        val saved = store.save("Pixel 7", "old", image(8, 8), Size(4, 4))
        val withIgnore = store.updateIgnore(saved, listOf(Rect(1, 1, 2, 2)))
        val renamed = store.rename(withIgnore, "new name")
        assertEquals("new name", renamed.name)
        assertFalse(saved.png.exists())
        assertFalse(File(saved.png.parentFile, "old.json").exists())
        val listed = store.list("Pixel 7")
        assertEquals(listOf("new name"), listed.map { it.name })
        assertEquals(listOf(Rect(1, 1, 2, 2)), listed[0].info.ignoreRects)
        dir.deleteRecursively()
    }

    @Test fun replaceImageKeepsIgnoreAndFileName() {
        val (store, dir) = tempStore()
        val saved = store.save("Pixel 7", "a", image(8, 8), Size(4, 4))
        val withIgnore = store.updateIgnore(saved, listOf(Rect(1, 1, 2, 2)))
        val replaced = store.replaceImage(withIgnore, image(16, 16), Size(8, 8))
        assertEquals(saved.png, replaced.png)
        assertEquals(16, store.load(replaced).width)
        assertEquals(listOf(Rect(1, 1, 2, 2)), store.list("Pixel 7")[0].info.ignoreRects)
        dir.deleteRecursively()
    }

    @Test fun renameOntoExistingNameFailsAndChangesNothing() {
        val (store, dir) = tempStore()
        val a = store.save("Pixel 7", "a", image(8, 8), null)
        store.save("Pixel 7", "b", image(8, 8), null)
        try {
            store.rename(a, "b")
            org.junit.Assert.fail("expected an IOException")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("already exists"))
        }
        assertEquals(listOf("a", "b"), store.list("Pixel 7").map { it.name })
        dir.deleteRecursively()
    }
}
