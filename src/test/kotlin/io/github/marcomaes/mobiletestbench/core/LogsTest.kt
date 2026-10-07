package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.logs.AndroidLogParser
import io.github.marcomaes.mobiletestbench.core.logs.IosLogParser
import io.github.marcomaes.mobiletestbench.core.logs.LogEntry
import io.github.marcomaes.mobiletestbench.core.logs.LogFilter
import io.github.marcomaes.mobiletestbench.core.logs.LogLevel
import io.github.marcomaes.mobiletestbench.core.logs.LogStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LogsTest {

    @Test
    fun `parses logcat threadtime lines`() {
        val e = AndroidLogParser.parse("10-04 11:03:54.580   647   810 E WifiDataStall: Ignore this poll. newStats: null rssi: -50")!!
        assertEquals("10-04 11:03:54.580", e.time)
        assertEquals(LogLevel.ERROR, e.level)
        assertEquals("WifiDataStall", e.tag)
        assertEquals("647", e.pid)
        assertEquals("Ignore this poll. newStats: null rssi: -50", e.message)
        assertEquals(LogLevel.INFO, AndroidLogParser.parse("10-04 11:03:54.483  2683  2686 I artd    : GetBestInfo: /data/x has no usable artifacts")!!.level)
        assertEquals("artd", AndroidLogParser.parse("10-04 11:03:54.483  2683  2686 I artd    : hello")!!.tag)
        assertEquals(LogLevel.WARN, AndroidLogParser.parse("10-04 11:03:54.483  1  2 W Tag: x")!!.level)
        assertEquals(LogLevel.VERBOSE, AndroidLogParser.parse("10-04 11:03:54.483  1  2 V Tag: x")!!.level)
        assertNull(AndroidLogParser.parse("--------- beginning of main"))
        assertNull(AndroidLogParser.parse("    at com.example.Foo.bar(Foo.java:12)"))
    }

    @Test
    fun `parses simulator log stream lines`() {
        val e = IosLogParser.parse("2026-10-04 11:04:04.159 A  backboardd[4172:1e0008] (GamepadHIDServiceFilter) Resume Connection")!!
        assertEquals("10-04 11:04:04.159", e.time)
        assertEquals("backboardd", e.tag)
        assertEquals("4172", e.pid)
        assertEquals("(GamepadHIDServiceFilter) Resume Connection", e.message)
        assertEquals(LogLevel.DEBUG, IosLogParser.parse("2026-10-04 11:04:04.161 Db backboardd[4172:1e0008] [com.apple.xpc] x")!!.level)
        assertEquals(LogLevel.INFO, IosLogParser.parse("2026-10-04 11:04:04.161 Df backboardd[4172:1e0008] x")!!.level)
        assertEquals(LogLevel.ERROR, IosLogParser.parse("2026-10-04 11:04:04.161 E  MyApp[1:2] boom")!!.level)
        assertEquals(LogLevel.FATAL, IosLogParser.parse("2026-10-04 11:04:04.161 F  MyApp[1:2] boom")!!.level)
        assertNull(IosLogParser.parse("Timestamp               Ty Process[PID:TID]"))
        assertNull(IosLogParser.parse(")}"))
    }

    private fun entry(level: LogLevel, tag: String, pid: String, message: String) = LogEntry("t", level, tag, pid, message)

    @Test
    fun `the filter combines level, text, tag and app`() {
        val e = entry(LogLevel.WARN, "OkHttp", "42", "Request failed: timeout")
        assertTrue(LogFilter(minLevel = LogLevel.WARN).matches(e))
        assertFalse(LogFilter(minLevel = LogLevel.ERROR).matches(e))
        assertTrue(LogFilter(text = "TIMEOUT").matches(e))
        assertFalse(LogFilter(text = "nope").matches(e))
        assertTrue(LogFilter(tag = "okhttp").matches(e))
        assertFalse(LogFilter(tag = "retrofit").matches(e))
        assertTrue(LogFilter(minLevel = LogLevel.VERBOSE).matches(entry(LogLevel.VERBOSE, "t", "1", "m")))
        assertFalse(LogFilter().matches(entry(LogLevel.DEBUG, "t", "1", "m"))) // Info is the default
    }

    @Test
    fun `an app is matched by its process ids on android and by name on ios`() {
        val mine = entry(LogLevel.INFO, "OkHttp", "42", "x")
        val other = entry(LogLevel.INFO, "OkHttp", "99", "x")
        val android = LogFilter(app = "com.example.app", appPids = setOf("42"))
        assertTrue(android.matches(mine))
        assertFalse(android.matches(other))
        assertFalse(LogFilter(app = "com.example.app", appPids = emptySet()).matches(mine)) // not running: nothing to show
        val ios = LogFilter(app = "myapp")
        assertTrue(ios.matches(entry(LogLevel.INFO, "MyApp", "1", "x")))
        assertFalse(ios.matches(entry(LogLevel.INFO, "SpringBoard", "1", "x")))
    }

    @Test
    fun `continuation lines join the entry before them`() {
        assumeTrue(File("/bin/sh").exists())
        val entries = mutableListOf<LogEntry>()
        val ended = CountDownLatch(1)
        val script = "printf '10-04 11:00:00.001  1  2 E App: Crash\\n    at Foo.bar(Foo.java:1)\\n    at Foo.baz(Foo.java:2)\\n10-04 11:00:00.002  1  2 I App: next\\n'"
        LogStream.start({ ProcessBuilder("sh", "-c", script).start() }, AndroidLogParser::parse, { synchronized(entries) { entries += it } }, { ended.countDown() })
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertEquals(2, entries.size)
        assertEquals("Crash\n    at Foo.bar(Foo.java:1)\n    at Foo.baz(Foo.java:2)", entries[0].message)
        assertEquals("next", entries[1].message)
    }

    @Test
    fun `a stream that fails reports what the process said`() {
        assumeTrue(File("/bin/sh").exists())
        val ended = CountDownLatch(1)
        var message: String? = null
        LogStream.start({ ProcessBuilder("sh", "-c", "echo 'device offline' >&2; exit 1").start() }, AndroidLogParser::parse, {}, { message = it; ended.countDown() })
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertEquals("device offline", message)
    }

    @Test
    fun `closing the stream ends a running process`() {
        assumeTrue(File("/bin/sh").exists())
        val ended = CountDownLatch(1)
        val stream = LogStream.start({ ProcessBuilder("sh", "-c", "exec sleep 30").start() }, AndroidLogParser::parse, {}, { ended.countDown() })
        stream.close()
        assertTrue(ended.await(5, TimeUnit.SECONDS))
    }
}
