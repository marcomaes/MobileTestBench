package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.logs.AndroidLogParser
import io.github.marcomaes.mobiletestbench.core.logs.LogEntry
import io.github.marcomaes.mobiletestbench.core.logs.LogFilter
import io.github.marcomaes.mobiletestbench.core.logs.LogLevel
import io.github.marcomaes.mobiletestbench.core.logs.LogStream
import io.github.marcomaes.mobiletestbench.core.logs.MobilecliLogParser
import io.github.marcomaes.mobiletestbench.core.logs.StackFrame
import io.github.marcomaes.mobiletestbench.core.logs.StackFrames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LogsUpgradeTest {
    private fun e(tag: String, msg: String, pid: String = "1") = LogEntry("10-04 11:00:00.000", LogLevel.INFO, tag, pid, msg)
    private fun fixture(name: String) = File("src/test/resources/fixtures/$name").readLines().filter { it.isNotBlank() }

    @Test
    fun `regex filters match case insensitively anywhere`() {
        val f = LogFilter(text = "time(d)?out|refused", tag = "^ok.*ttp$", regex = true)
        assertTrue(f.matches(e("OkHttp", "Connection REFUSED by host")))
        assertTrue(f.matches(e("okhttp", "request timeout")))
        assertFalse(f.matches(e("okhttp", "all fine")))
        assertFalse(f.matches(e("retrofit", "timeout")))
        // without the toggle the same text is plain
        assertFalse(LogFilter(text = "time(d)?out").matches(e("t", "timeout")))
        assertTrue(LogFilter(text = "time(d)?out").matches(e("t", "a time(d)?out here")))
    }

    @Test
    fun `an invalid pattern is reported and ignored`() {
        val f = LogFilter(text = "abc(", regex = true)
        assertNotNull(f.textError)
        assertNull(f.tagError)
        assertTrue(f.matches(e("t", "anything")))
        assertTrue(f.matchRanges("abc(").isEmpty())
        val g = LogFilter(tag = "[", regex = true)
        assertNotNull(g.tagError)
        assertTrue(g.matches(e("whatever", "x")))
        assertNull(LogFilter(text = "abc(", regex = false).textError)
        assertNull(LogFilter(text = "  ", regex = true).textError)
    }

    @Test
    fun `match ranges for plain and regex text`() {
        assertEquals(listOf(4..7, 13..16), LogFilter(text = "boom").matchRanges("a - boom and boom"))
        assertEquals(listOf(0..3), LogFilter(text = "ERRO").matchRanges("error"))
        assertEquals(listOf(2..4, 8..9), LogFilter(text = "\\d+", regex = true).matchRanges("ab123 x 45"))
        assertTrue(LogFilter(text = "").matchRanges("abc").isEmpty())
        assertTrue(LogFilter(text = "x*", regex = true).matchRanges("abc").isEmpty()) // empty matches are not painted
    }

    @Test
    fun `a bundle id as the app filter matches the process name on ios`() {
        val f = LogFilter(app = "com.mobilenext.playground", bundleIdLastPart = true)
        assertTrue(f.matches(e("Playground", "x")))
        assertFalse(f.matches(e("SpringBoard", "x")))
        // Without the iOS flag the last segment is not used (so com.example.app does not match every tag containing "app").
        assertFalse(LogFilter(app = "com.example.app").matches(e("Playground", "x")))
        assertTrue(LogFilter(app = "play").matches(e("Playground", "x")))
    }

    @Test
    fun `extracts java and kotlin frames`() {
        assertEquals(StackFrame("com.example.Foo", "bar", "Foo.java", 12), StackFrames.extract("\tat com.example.Foo.bar(Foo.java:12)"))
        assertEquals(StackFrame("com.example.MainKt\$main\$1", "invokeSuspend", "Main.kt", 12), StackFrames.extract("    at com.example.MainKt\$main\$1.invokeSuspend(Main.kt:12)"))
        assertEquals(StackFrame("android.app.LocaleConfig", "<init>", "LocaleConfig.java", 154), StackFrames.extract("\tat android.app.LocaleConfig.<init>(LocaleConfig.java:154)"))
        assertEquals(StackFrame("java.util.concurrent.ThreadPoolExecutor\$Worker", "run", "ThreadPoolExecutor.java", 644), StackFrames.extract("at java.base/java.util.concurrent.ThreadPoolExecutor\$Worker.run(ThreadPoolExecutor.java:644)"))
        assertEquals(StackFrame("a.B", "lambda\$foo\$0", "B.kt", 3), StackFrames.extract("at a.B.lambda\$foo\$0(B.kt:3)"))
        assertEquals(StackFrame(null, null, "Foo.kt", 34), StackFrames.extract("crashed (Foo.kt:34)"))
        assertEquals("com/example", StackFrames.extract("at com.example.Foo\$Inner.x(Foo.java:1)")!!.packagePath)
        assertEquals("", StackFrames.extract("at Foo.x(Foo.java:1)")!!.packagePath)
    }

    @Test
    fun `rejects frames that have no source`() {
        assertNull(StackFrames.extract("\tat android.os.BinderProxy.transactNative(Native Method)"))
        assertNull(StackFrames.extract("\tat tk_trace.handleBroadcast 2 ms(Unknown Source:0)"))
        assertNull(StackFrames.extract("\tat a.B.c(SourceFile:12)"))
        assertNull(StackFrames.extract("\tat cgfc.createPackageContext(:com.google.android.gms@263131035@26.31.31 (260400-959389638):3)"))
        assertNull(StackFrames.extract("Caused by: android.system.ErrnoException: write failed: EPIPE (Broken pipe)"))
        assertNull(StackFrames.extract("\t... 33 more"))
        assertNull(StackFrames.extract("at a.B.c(B.java:0)"))
    }

    @Test
    fun `real logcat trace has frames in the right entries`() {
        val entries = fixture("android-logcat-stacktrace.txt").mapNotNull { AndroidLogParser.parse(it) }
        val frames = entries.mapNotNull { StackFrames.extractFromMessage(it.message) }
        assertTrue(frames.size >= 5)
        assertEquals("XmlUtils.java", frames.first().file)
        assertEquals(1580, frames.first().line)
        assertTrue(frames.none { it.file.contains("Unknown") })
    }

    @Test
    fun `multi line message uses its first frame and lists all`() {
        val msg = "Crash\n  at com.x.A.a(A.java:1)\n  at com.x.B.b(B.kt:2)"
        assertEquals("A.java", StackFrames.extractFromMessage(msg)!!.file)
        assertEquals(listOf("A.java", "B.kt"), StackFrames.allFrames(msg).map { it.file })
        assertNull(StackFrames.extractFromMessage("nothing here\nat all"))
    }

    @Test
    fun `chooses the file by package path`() {
        val frame = StackFrame("com.example.ui.Foo", "bar", "Foo.java", 1)
        val paths = listOf("/p/app/src/org/other/Foo.java", "/p/app/src/com/example/ui/Foo.java", "/p/lib/src/com/example/Foo.java")
        assertEquals("/p/app/src/com/example/ui/Foo.java", StackFrames.choose(paths, frame))
        assertEquals("/p/a/Foo.java", StackFrames.choose(listOf("/p/a/Foo.java"), frame))
        assertEquals("/x/Foo.java", StackFrames.choose(listOf("/x/Foo.java", "/y/Foo.java"), frame)) // none fits: the first
        assertNull(StackFrames.choose(emptyList(), frame))
    }

    @Test
    fun `parses mobilecli android lines`() {
        val entries = fixture("mobilecli_device_logs_android.txt").map { MobilecliLogParser.parse(it)!! }
        assertEquals(10, entries.size)
        val first = entries.first()
        assertEquals("10-05 03:03:50.597", first.time)
        assertEquals("adbd", first.tag)
        assertEquals("516", first.pid)
        assertEquals(LogLevel.INFO, first.level)
        assertEquals(LogLevel.ERROR, entries[2].level)
        assertEquals("SatelliteController", entries[2].tag)
        assertTrue(entries.any { it.level == LogLevel.VERBOSE })
        assertTrue(entries.any { it.level == LogLevel.DEBUG })
        assertTrue(entries.any { it.level == LogLevel.WARN })
    }

    @Test
    fun `parses mobilecli ios lines`() {
        val entries = fixture("mobilecli_device_logs_ios.txt").map { MobilecliLogParser.parse(it)!! }
        assertEquals(11, entries.size)
        assertEquals("10-05 03:03:51.806", entries[0].time) // microseconds and zone cut
        assertEquals("backboardd", entries[0].tag)
        assertEquals(LogLevel.INFO, entries[0].level) // empty level
        assertEquals("gamecontrollerd", entries[1].tag)
        assertTrue(entries[2].message.contains("<_GCDriverClientConnection"))
        assertTrue(entries.any { it.level == LogLevel.FATAL })
        assertTrue(entries.any { it.level == LogLevel.ERROR })
        assertTrue(entries.any { it.message.contains('\n') })
    }

    @Test
    fun `mobilecli parser ignores non log lines`() {
        assertNull(MobilecliLogParser.parse("not json"))
        assertNull(MobilecliLogParser.parse("{\"status\":\"error\",\"error\":\"device not found\"}"))
        assertNull(MobilecliLogParser.parse("{broken"))
    }

    @Test
    fun `closing the stream also ends children of the process`() {
        assumeTrue(File("/bin/sh").exists())
        val ended = CountDownLatch(1)
        // the shell stays as a wrapper around a child sleep, like the mobilecli node wrapper around its native child
        val stream = LogStream.start({ ProcessBuilder("sh", "-c", "sleep 30; echo done").start() }, AndroidLogParser::parse, {}, { ended.countDown() })
        Thread.sleep(300)
        stream.close()
        assertTrue(ended.await(5, TimeUnit.SECONDS))
    }
}
