package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.links.UrlLauncher
import io.github.marcomaes.mobiletestbench.core.links.UrlLauncher.Validation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlLauncherTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    @Test
    fun `valid urls are trimmed`() {
        assertEquals(Validation.Ok("https://example.com/a?x=1&y=2"), UrlLauncher.validate("  https://example.com/a?x=1&y=2 \n"))
        assertEquals(Validation.Ok("myapp://open/42"), UrlLauncher.validate("myapp://open/42"))
        assertEquals(Validation.Ok("tel:123"), UrlLauncher.validate("tel:123"))
    }

    @Test
    fun `invalid values are rejected with a reason`() {
        assertTrue(UrlLauncher.validate("   ") is Validation.Invalid)
        assertTrue((UrlLauncher.validate("not a url") as Validation.Invalid).reason.contains("spaces"))
        assertTrue((UrlLauncher.validate("a://b\nc://d") as Validation.Invalid).reason.contains("single line"))
        assertTrue((UrlLauncher.validate("foo") as Validation.Invalid).reason.contains("scheme"))
        assertTrue(UrlLauncher.validate("1http://x") is Validation.Invalid)
    }

    @Test
    fun `args`() {
        assertEquals(listOf("url", "myapp://x"), UrlLauncher.args("myapp://x"))
    }

    @Test
    fun `android error shows the Error line`() {
        val raw = com.google.gson.JsonParser.parseString(fixture("mobilecli-url-error-android.json")).asJsonObject["error"].asString
        assertEquals("Activity not started, unable to resolve Intent { act=android.intent.action.VIEW dat=settings:///... flg=0x10000000 xflg=0x4 }", UrlLauncher.friendlyError(raw))
    }

    @Test
    fun `ios exit statuses are explained`() {
        val e115 = com.google.gson.JsonParser.parseString(fixture("mobilecli-url-error-ios-115.json")).asJsonObject["error"].asString
        val e206 = com.google.gson.JsonParser.parseString(fixture("mobilecli-url-error-ios-206.json")).asJsonObject["error"].asString
        assertEquals("No app on the device handles this URL scheme", UrlLauncher.friendlyError(e115))
        assertEquals("Not a valid URL (is the scheme missing?)", UrlLauncher.friendlyError(e206))
        assertEquals("Could not open the URL", UrlLauncher.friendlyError(""))
        assertEquals("device offline", UrlLauncher.friendlyError("device offline"))
    }

    @Test
    fun `history is distinct newest first and capped`() {
        assertEquals(listOf("c", "a", "b"), UrlLauncher.addToHistory(listOf("a", "b"), "c"))
        assertEquals(listOf("b", "a", "c"), UrlLauncher.addToHistory(listOf("a", "b", "c"), "b"))
        val full = (1..20).map { "s$it://x" }
        val next = UrlLauncher.addToHistory(full, "new://x")
        assertEquals(20, next.size)
        assertEquals("new://x", next.first())
        assertEquals("s19://x", next.last())
        assertEquals(listOf("s5://x") + full.filter { it != "s5://x" }, UrlLauncher.addToHistory(full, "s5://x"))
    }
}
