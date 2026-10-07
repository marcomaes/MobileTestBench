package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.inspector.LocatorSuggestion
import io.github.marcomaes.mobiletestbench.core.inspector.LocatorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocatorHintTest {

    private fun suggestion(framework: String, strategy: String, matches: Int) =
        LocatorSuggestion(framework, strategy, "x", matches)

    @Test
    fun `a unique test id has no hint`() {
        assertNull(suggestion("Mobilewright", "test id", 1).hint)
    }

    @Test
    fun `a locator that matches nothing or several elements says so`() {
        assertTrue(suggestion("Mobilewright", "test id", 0).hint!!.contains("nothing"))
        assertTrue(suggestion("Mobilewright", "role", 16).hint!!.contains("16"))
    }

    @Test
    fun `fragile but unique locators are flagged`() {
        assertNotNull(suggestion("Appium", "xpath (absolute)", 1).hint)
        assertTrue(suggestion("Mobilewright", "label", 1).hint!!.contains("language"))
        assertTrue(suggestion("Mobilewright", "text", 1).hint!!.contains("language"))
    }

    @Test
    fun `text based appium strategies are not flagged by the mobilewright rule`() {
        assertNull(suggestion("Appium", "id", 1).hint)
    }

    private fun xpath(expr: String, strategy: String = "xpath") = LocatorSuggestion("Appium", strategy, expr, 1)

    @Test
    fun `index based xpath is detected`() {
        assertTrue(LocatorHints.isIndexBased("//android.widget.Button[3]"))
        assertTrue(LocatorHints.isIndexBased("(//android.widget.Button)[2]"))
        assertTrue(LocatorHints.isIndexBased("//a[ last() ]"))
        assertTrue(LocatorHints.isIndexBased("//a[last()-1]"))
        assertTrue(LocatorHints.isIndexBased("//a[position()=2]"))
        assertTrue(LocatorHints.isIndexBased("//a[@index=\"1\"]"))
        assertFalse(LocatorHints.isIndexBased("//a[@text=\"Item [3]\"]"))
        assertFalse(LocatorHints.isIndexBased("//a[@resource-id='x']"))
    }

    @Test
    fun `an index based xpath gets a hint`() {
        assertTrue(xpath("(//a[@text=\"x\"])[2]").hint!!.contains("Index"))
        assertNull(xpath("//a[@text=\"x\"]").hint)
        assertNull(LocatorSuggestion("Appium", "id", "a[3]", 1).hint)
    }

    @Test
    fun `no alternative is named without a warning`() {
        val good = suggestion("Mobilewright", "test id", 1)
        assertNull(LocatorHints.hintWithAlternative(good, listOf(good)))
    }

    @Test
    fun `the next best unique locator without warning is named`() {
        val bad = xpath("//a[3]")
        val dup = LocatorSuggestion("Appium", "id", "dup", 2)
        val fragile = xpath("/hierarchy/a", "xpath (absolute)")
        val good = LocatorSuggestion("Appium", "accessibility id", "login", 1)
        val mw = LocatorSuggestion("Mobilewright", "test id", "screen.getByTestId('x')", 1)
        val text = LocatorHints.hintWithAlternative(bad, listOf(mw, bad, dup, fragile, good))!!
        assertTrue(text.contains("Index based"))
        assertTrue(text.contains("Next best: accessibility id, login"))
        // Without a same framework candidate, another framework is used and named.
        val other = LocatorHints.hintWithAlternative(bad, listOf(mw, bad, dup))!!
        assertTrue(other.contains("Next best: Mobilewright test id"))
    }

    @Test
    fun `the hint stays plain when there is no alternative`() {
        val bad = xpath("//a[3]")
        assertEquals(bad.hint, LocatorHints.hintWithAlternative(bad, listOf(bad, LocatorSuggestion("Appium", "id", "d", 2))))
    }
}
