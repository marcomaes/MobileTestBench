package io.github.marcomaes.mobiletestbench.core

import com.google.gson.JsonParser
import io.github.marcomaes.mobiletestbench.core.crashes.CrashEntry
import io.github.marcomaes.mobiletestbench.core.crashes.Crashes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashesTest {
    private fun fixture(name: String) = JsonParser.parseString(javaClass.getResource("/fixtures/$name")!!.readText()).asJsonObject.get("data")

    @Test
    fun `android list is sorted newest first`() {
        val list = Crashes.parseList(fixture("crash_list_android.json"))
        assertTrue(list.size >= 3)
        assertEquals(list.map { it.timestamp }.sortedDescending(), list.map { it.timestamp })
    }

    @Test
    fun `ios list is sorted by time and keeps ids with spaces`() {
        val list = Crashes.parseList(fixture("crash_list_ios.json"))
        assertEquals(list.map { it.timestamp }.sortedDescending(), list.map { it.timestamp })
        assertTrue(list.any { " " in it.id })
    }

    @Test
    fun `null or malformed data is an empty list`() {
        assertTrue(Crashes.parseList(null).isEmpty())
        assertTrue(Crashes.parseList(JsonParser.parseString("{}")).isEmpty())
        assertTrue(Crashes.parseList(JsonParser.parseString("[]")).isEmpty())
    }

    @Test
    fun `filter matches process id and time`() {
        val l = listOf(CrashEntry("a_1", "com.Foo", "2026-01-01 10:00:00"), CrashEntry("b_2", "bar", "2026-02-02 10:00:00"))
        assertEquals(1, Crashes.filter(l, "FOO").size)
        assertEquals(1, Crashes.filter(l, "b_2").size)
        assertEquals(1, Crashes.filter(l, "2026-02").size)
        assertEquals(2, Crashes.filter(l, " ").size)
    }

    @Test
    fun `java crash summary is the exception line`() {
        val content = Crashes.parseReport(fixture("crash_get_android_java.json"))!!
        assertTrue(Crashes.summary(Platform.ANDROID, content)!!.startsWith("java.lang.IllegalStateException: UiAutomationService"))
    }

    @Test
    fun `tombstone summary is the signal`() {
        val content = Crashes.parseReport(fixture("crash_get_android_tombstone.json"))!!
        assertEquals("SIGSEGV (SEGV_MAPERR) fault addr 0x0000000000005150", Crashes.summary(Platform.ANDROID, content))
    }

    @Test
    fun `ios summary has exception and termination`() {
        val content = Crashes.parseReport(fixture("crash_get_ios_ips.json"))!!
        val s = Crashes.summary(Platform.IOS, content)!!
        assertTrue(s, s.startsWith("EXC_CRASH (SIGABRT) - Abort trap: 6"))
    }

    @Test
    fun `summary is null when nothing is recognised`() {
        assertNull(Crashes.summary(Platform.ANDROID, "nothing here"))
        assertNull(Crashes.summary(Platform.IOS, "not json\nstill not"))
    }

    @Test
    fun `file names are safe`() {
        assertEquals("2026-09-14_15-48-07.216_26798.txt", Crashes.fileName(CrashEntry("2026-09-14_15:48:07.216_26798", "x", ""), Platform.ANDROID))
        assertEquals("ExcUserFault_Google Chrome-1.ips", Crashes.fileName(CrashEntry("ExcUserFault_Google Chrome-1.ips", "x", ""), Platform.IOS))
    }

    @Test
    fun `get args keep the id as one argument`() {
        assertEquals(listOf("device", "crashes", "get", "a b:c"), Crashes.getArgs("a b:c"))
    }
}
