package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.exec.CommandResult
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import io.github.marcomaes.mobiletestbench.core.perf.AndroidPerf
import io.github.marcomaes.mobiletestbench.core.perf.PerfException
import io.github.marcomaes.mobiletestbench.core.perf.AndroidPerfParsers
import io.github.marcomaes.mobiletestbench.core.perf.CpuTracker
import io.github.marcomaes.mobiletestbench.core.perf.FrameDelta
import io.github.marcomaes.mobiletestbench.core.perf.IosPerf
import io.github.marcomaes.mobiletestbench.core.perf.IosPerfParsers
import io.github.marcomaes.mobiletestbench.core.perf.PerfCsv
import io.github.marcomaes.mobiletestbench.core.perf.PerfPoint
import io.github.marcomaes.mobiletestbench.core.perf.PerfSampler
import io.github.marcomaes.mobiletestbench.core.perf.Reading
import io.github.marcomaes.mobiletestbench.core.perf.RingSeries
import io.github.marcomaes.mobiletestbench.core.perf.StartBackend
import io.github.marcomaes.mobiletestbench.core.perf.StartEntry
import io.github.marcomaes.mobiletestbench.core.perf.StartHistory
import io.github.marcomaes.mobiletestbench.core.perf.StartKind
import io.github.marcomaes.mobiletestbench.core.perf.StartResult
import io.github.marcomaes.mobiletestbench.core.perf.StartRun
import io.github.marcomaes.mobiletestbench.core.perf.StartRunner
import io.github.marcomaes.mobiletestbench.core.perf.StartSummary
import io.github.marcomaes.mobiletestbench.core.perf.Stats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime

class PerfTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()
    private fun ok(text: String, code: Int = 0) = CommandResult(code, text.toByteArray(), "")

    private class FakeRunner(val answer: (List<String>) -> CommandResult) : CommandRunner(emptyMap()) {
        val commands = mutableListOf<List<String>>()
        override fun run(command: List<String>, timeout: Duration): CommandResult {
            commands += command
            return answer(command)
        }
    }

    // ---- Android parsers

    @Test fun parsesProcStatLine() {
        // utime 9713 + stime 5912, counted after the last ')'
        assertEquals(15625L, AndroidPerfParsers.statTicks(fixture("android-proc-stat-playground.txt").trim()))
        // a command name with spaces and parentheses
        assertEquals(30L, AndroidPerfParsers.statTicks("12 (my (weird) app) S 1 1 0 0 -1 0 0 0 0 0 10 20 0 0 0"))
    }

    @Test fun parsesTheProbeOutput() {
        val out = "1791120996.737075986\nPID=8642\n" + fixture("android-proc-stat-playground.txt") + fixture("android-proc-status-playground.txt") +
            "Total frames rendered: 65\nJanky frames: 65 (100.00%)\n"
        val p = AndroidPerfParsers.parseProbe(out)
        assertEquals(1791120996.737075986, p.deviceSeconds!!, 1e-3)
        assertEquals(8642, p.pid)
        assertEquals(15625L, p.cpuTicks)
        assertEquals(240640L, p.rssKb)
        assertEquals(50512L, p.swapKb)
        assertEquals(65L, p.totalFrames)
        assertEquals(65L, p.jankyFrames)
    }

    @Test fun probeOfAppThatIsNotRunning() {
        val p = AndroidPerfParsers.parseProbe("1791120996.5\n")
        assertNull(p.pid)
        assertNull(p.cpuTicks)
        assertNull(p.totalFrames)
    }

    @Test fun parsesPssFromMeminfo() {
        assertEquals(120629L, AndroidPerfParsers.parsePssKb(fixture("android-dumpsys-meminfo-playground.txt")))
        assertNull(AndroidPerfParsers.parsePssKb(fixture("android-dumpsys-meminfo-no-process.txt")))
        assertNull(AndroidPerfParsers.parsePssKb("Got IoException! java.io.IOException: Timeout"))
        assertEquals(120629L, AndroidPerfParsers.parsePssKb("        TOTAL   120629    38704    17616    37279   240304    53587    37895    11558\n"))
    }

    @Test fun parsesGfxinfoCounters() {
        assertEquals(40L to 40L, AndroidPerfParsers.parseGfxinfo(fixture("android-dumpsys-gfxinfo-playground.txt")))
        assertNull(AndroidPerfParsers.parseGfxinfo(fixture("android-dumpsys-gfxinfo-no-process.txt")))
    }

    @Test fun parsesPidofAndActivity() {
        assertEquals(8642, AndroidPerfParsers.firstPid("8642\n"))
        assertEquals(8642, AndroidPerfParsers.firstPid("8642 8700\n"))
        assertNull(AndroidPerfParsers.firstPid(""))
        assertEquals("com.mobilenext.playground/.MainActivity",
            AndroidPerfParsers.parseResolvedActivity("priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\ncom.mobilenext.playground/.MainActivity\n"))
    }

    @Test fun parsesAmStartOutput() {
        val cold = AndroidPerfParsers.parseAmStart(fixture("android-am-start-W-cold.txt"))
        assertEquals("COLD", cold.launchState)
        assertEquals(34211L, cold.totalMs)
        assertEquals(34223L, cold.waitMs)
        assertNull(cold.error)
        val warm = AndroidPerfParsers.parseAmStart(fixture("android-am-start-W-warm-launcher-intent.txt"))
        assertEquals("WARM", warm.launchState)
        assertEquals(7879L, warm.totalMs)
        val plain = AndroidPerfParsers.parseAmStart(fixture("android-am-start-W-warm.txt"))
        assertNull(plain.totalMs)
        assertEquals(2526L, plain.waitMs)
        assertTrue(plain.warning!!.contains("brought to the front"))
        val hot = AndroidPerfParsers.parseAmStart(fixture("android-am-start-W-hot.txt"))
        assertEquals(0L, hot.totalMs)
        assertEquals("Activity class {x/.Y} does not exist.", AndroidPerfParsers.parseAmStart("Error type 3\nError: Activity class {x/.Y} does not exist.\n").error)
    }

    @Test fun onlySafeIdsGoIntoTheScript() {
        assertTrue(AndroidPerfParsers.isSafeId("com.mobilenext.playground"))
        assertFalse(AndroidPerfParsers.isSafeId("a; rm -rf /"))
        assertFalse(AndroidPerfParsers.isSafeId(""))
    }

    // ---- iOS parsers

    @Test fun parsesLaunchctlPid() {
        val out = fixture("ios-simctl-launchctl-playground.txt")
        assertEquals(35979, IosPerfParsers.launchctlPid(out, "com.mobilenext.playground"))
        assertNull(IosPerfParsers.launchctlPid(out, "com.mobilenext"))
        assertNull(IosPerfParsers.launchctlPid(out, "com.not.installed"))
    }

    @Test fun parsesFootprintAndPs() {
        assertEquals(28887248 / 1048576.0, IosPerfParsers.footprintMb(fixture("ios-footprint-playground.txt"))!!, 1e-9)
        assertNull(IosPerfParsers.footprintMb(fixture("ios-footprint-no-process.txt")))
        assertEquals(1.80, IosPerfParsers.psCpuSeconds(fixture("ios-ps-time-playground.txt"))!!, 1e-9)
        assertNull(IosPerfParsers.psCpuSeconds(""))
        assertEquals(3723.5, IosPerfParsers.parseCpuTime("1:02:03.50")!!, 1e-9)
        assertEquals(86400.0 + 61.0, IosPerfParsers.parseCpuTime("1-00:01:01")!!, 1e-9)
        assertNull(IosPerfParsers.parseCpuTime("abc"))
    }

    @Test fun parsesLaunchTimingFromTheSimulatorLog() {
        val log = fixture("ios-log-show-launch-cold.txt")
        // request 15:46:51.746, last "-> 0;" of pid 36136 at 15:46:52.807
        assertEquals(1061L, IosPerfParsers.launchMillis(log, "com.mobilenext.playground", 36136))
        assertEquals(1061L, IosPerfParsers.launchMillis(log, "com.mobilenext.playground", null))
        assertNull(IosPerfParsers.launchMillis(log, "com.mobilenext.playground", 1))
        assertNull(IosPerfParsers.launchMillis("", "com.mobilenext.playground", 36136))
        // a bundle id that is a prefix of the one in the log must not match its request line
        assertNull(IosPerfParsers.launchMillis(log, "com.mobilenext.play", 36136))
        assertEquals(36136, IosPerfParsers.launchOutputPid("com.mobilenext.playground: 36136\n"))
        assertEquals("2026-10-04 15:42:36", IosPerfParsers.logStartArgument(LocalDateTime.of(2026, 10, 4, 15, 42, 36, 999_000_000)))
    }

    // ---- model

    @Test fun cpuPercentFromTimeDeltas() {
        val t = CpuTracker()
        assertNull(t.update(10, 1.0, 100.0)) // baseline
        assertEquals(12.0, t.update(10, 1.12, 101.0)!!, 1e-9)
        assertEquals(150.0, t.update(10, 2.62, 102.0)!!, 1e-9) // several cores
        assertNull(t.update(11, 0.5, 103.0)) // a new pid re-baselines
        assertEquals(5.0, t.update(11, 0.55, 104.0)!!, 1e-9)
        assertNull(t.update(null, null, 105.0))
        assertNull(t.update(11, 0.6, 106.0)) // after a gap
    }

    @Test fun frameDeltas() {
        val f = FrameDelta()
        assertNull(f.update(1, 100, 10, 0.0))
        val a = f.update(1, 160, 16, 1.0)!!
        assertEquals(60.0, a.fps, 1e-9)
        assertEquals(10.0, a.jankPercent!!, 1e-9)
        val idle = f.update(1, 160, 16, 2.0)!!
        assertEquals(0.0, idle.fps, 1e-9)
        assertNull(idle.jankPercent)
        assertNull(f.update(1, 5, 1, 3.0)) // counters went down: restart or reset
        assertNotNull(f.update(1, 15, 1, 4.0))
    }

    @Test fun samplerBuildsPointsAndGaps() {
        val s = PerfSampler()
        val p1 = s.point(Reading(5, 10.0, rssMb = 200.0, cpuSeconds = 1.0, totalFrames = 10, jankyFrames = 1), pssMb = null, elapsedSeconds = 0.0)
        assertNull(p1.cpuPercent)
        assertNull(p1.memoryMb)
        assertEquals(200.0, p1.rssMb!!, 1e-9)
        val p2 = s.point(Reading(5, 11.0, rssMb = 201.0, cpuSeconds = 1.1, totalFrames = 40, jankyFrames = 4), pssMb = 120.0, elapsedSeconds = 1.0)
        assertEquals(10.0, p2.cpuPercent!!, 1e-6)
        assertEquals(120.0, p2.memoryMb!!, 1e-9)
        assertEquals(30.0, p2.fps!!, 1e-9)
        assertEquals(10.0, p2.jankPercent!!, 1e-9)
        val gap = s.point(Reading(null, 12.0), 120.0, 2.0)
        assertNull(gap.memoryMb)
        assertNull(gap.cpuPercent)
    }

    @Test fun ringKeepsTheNewest() {
        val r = RingSeries<Int>(3)
        (1..5).forEach { r.add(it) }
        assertEquals(listOf(3, 4, 5), r.toList())
        r.clear()
        assertEquals(0, r.size)
    }

    @Test fun statsIgnoreGaps() {
        val s = Stats.of(listOf(10.0, null, 30.0, null))
        assertEquals(30.0, s.current!!, 1e-9)
        assertEquals(10.0, s.min!!, 1e-9)
        assertEquals(20.0, s.avg!!, 1e-9)
        assertEquals(30.0, s.max!!, 1e-9)
        assertEquals(2, s.count)
        assertNull(Stats.of(listOf(null, null)).current)
        assertEquals("-", Stats.format(null, " MB"))
        assertEquals("12.5 MB", Stats.format(12.5, " MB"))
    }

    @Test fun csv() {
        val csv = PerfCsv.toCsv(listOf(PerfPoint(1.0, 100.0, 200.0, 5.5, 60.0, null), PerfPoint(2.0)))
        assertEquals("seconds,memory_mb,rss_mb,cpu_percent,fps,janky_percent\n1.000,100.000,200.000,5.500,60.000,\n2.000,,,,,\n", csv)
    }

    @Test fun startHistoryAndSummary() {
        val h = StartHistory(20)
        (1..25).forEach { h.add(StartEntry("app", "dev", StartKind.COLD, it.toLong(), it.toLong())) }
        assertEquals(20, h.entries().size)
        assertEquals(25L, h.entries().first().millis)
        val s = StartSummary.of(listOf(StartRun(1, StartKind.COLD, 1000), StartRun(2, StartKind.COLD, 1100), StartRun(3, StartKind.COLD, null, "x")))!!
        assertEquals(1050L, s.avg)
        assertEquals(1000L, s.min)
        assertEquals(1100L, s.max)
        assertEquals(2, s.count)
        assertNull(StartSummary.of(emptyList()))
    }

    @Test fun runnerStopsAtTheFirstFailureAndOnCancel() {
        var calls = 0
        val backend = object : StartBackend {
            override fun measure(app: String, kind: StartKind): StartResult {
                calls++
                if (calls == 3) error("boom\nsecond line")
                return StartResult(100L * calls)
            }
        }
        val runs = StartRunner.run(backend, "a", StartKind.COLD, 5, { false })
        assertEquals(3, runs.size)
        assertNull(runs[2].millis)
        assertEquals("boom", runs[2].note)
        calls = 0
        var cancel = false
        val cancelled = StartRunner.run(backend, "a", StartKind.WARM, 10, { cancel }, onRun = { if (it.index == 1) cancel = true })
        assertEquals(1, cancelled.size)
        assertEquals(10, StartRunner.run(object : StartBackend { override fun measure(app: String, kind: StartKind) = StartResult(1) }, "a", StartKind.WARM, 99, { false }).size)
    }

    // ---- backends with a fake runner

    @Test fun androidSampleUsesOneShellCall() {
        val out = "1791120996.7\nPID=8642\n" + fixture("android-proc-stat-playground.txt") + fixture("android-proc-status-playground.txt") + "Total frames rendered: 65\nJanky frames: 13 (20.00%)\n"
        val runner = FakeRunner { ok(out) }
        val reading = AndroidPerf("adb", "emulator-5554", runner).sample("com.mobilenext.playground")
        assertEquals(1, runner.commands.size)
        assertEquals(listOf("adb", "-s", "emulator-5554", "shell"), runner.commands[0].take(4))
        assertEquals(8642, reading.pid)
        assertEquals(156.25, reading.cpuSeconds!!, 1e-9)
        assertEquals(240640 / 1024.0, reading.rssMb!!, 1e-9)
        assertEquals(13L, reading.jankyFrames)
        assertNull(AndroidPerf("adb", "s", FakeRunner { ok("1.0\n") }).sample("a.b").pid)
        try { AndroidPerf("adb", "s", runner).sample("a;b"); org.junit.Assert.fail() } catch (e: Exception) { assertTrue(e.message!!.contains("valid")) }
    }

    @Test fun androidColdAndWarmStart() {
        val steps = mutableListOf<String>()
        var starts = 0
        val runner = FakeRunner { cmd ->
            val line = cmd.drop(4).joinToString(" ")
            steps += line
            if (line.startsWith("am start")) starts++
            when {
                line.startsWith("cmd package") -> ok("priority=0\ncom.mobilenext.playground/.MainActivity\n")
                line.startsWith("am start") && starts == 1 -> ok(fixture("android-am-start-W-cold.txt"))
                line.startsWith("am start") -> ok(fixture("android-am-start-W-warm-launcher-intent.txt"))
                line.startsWith("pidof") -> ok("8642\n")
                else -> ok("")
            }
        }
        val perf = AndroidPerf("adb", "emulator-5554", runner, sleep = {})
        val cold = perf.measure("com.mobilenext.playground", StartKind.COLD)
        assertEquals(34211L, cold.millis)
        assertTrue(steps.any { it == "am force-stop com.mobilenext.playground" })
        assertTrue(steps.any { it.contains("-a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n com.mobilenext.playground/.MainActivity") })
        steps.clear()
        val warm = perf.measure("com.mobilenext.playground", StartKind.WARM)
        assertEquals(7879L, warm.millis)
        assertTrue(steps.any { it == "input keyevent KEYCODE_HOME" })
        assertFalse(steps.any { it.startsWith("am force-stop") })
    }

    @Test fun androidStartError() {
        val runner = FakeRunner { cmd ->
            if (cmd.contains("resolve-activity")) ok("x/.A\n") else ok("Error type 3\nError: Activity class {x/.A} does not exist.\n", 1)
        }
        try { AndroidPerf("adb", "s", runner, sleep = {}).measure("x", StartKind.COLD); org.junit.Assert.fail() } catch (e: Exception) { assertTrue(e.message!!.contains("does not exist")) }
    }

    @Test fun iosSampleCombinesLaunchctlFootprintAndPs() {
        var t = 100.0
        val runner = FakeRunner { cmd ->
            when {
                cmd.contains("launchctl") -> ok(fixture("ios-simctl-launchctl-playground.txt"))
                cmd[0] == "footprint" -> ok(fixture("ios-footprint-playground.txt"))
                else -> ok(fixture("ios-ps-time-playground.txt"))
            }
        }
        val r = IosPerf("xcrun", "UDID", runner, {}, sleep = {}, clock = { t += 0.5; t }).sample("com.mobilenext.playground")
        assertEquals(35979, r.pid)
        assertEquals(1.80, r.cpuSeconds!!, 1e-9)
        assertEquals(27.55, r.memoryMb!!, 0.01)
        assertNull(IosPerf("xcrun", "UDID", FakeRunner { ok("PID\tStatus\tLabel\n") }, {}, sleep = {}).sample("com.x").pid)
        // footprint says the pid is gone
        val gone = FakeRunner { cmd -> if (cmd.contains("launchctl")) ok(fixture("ios-simctl-launchctl-playground.txt")) else ok(fixture("ios-footprint-no-process.txt"), 66) }
        assertNull(IosPerf("xcrun", "UDID", gone, {}, sleep = {}).sample("com.mobilenext.playground").pid)
    }

    @Test fun iosColdStartTimedFromTheLogWithFallback() {
        val steps = mutableListOf<String>()
        val recorded = mutableListOf<List<String>>()
        fun runner(log: String) = FakeRunner { cmd ->
            steps += cmd.drop(2).joinToString(" ").take(40)
            recorded += cmd
            when {
                cmd.contains("terminate") -> ok("", 3)
                cmd.contains("launch") && cmd.contains("simctl") && cmd[2] == "launch" -> ok("com.mobilenext.playground: 36136\n")
                cmd.contains("log") -> ok(log)
                else -> ok("")
            }
        }
        val perf = IosPerf("xcrun", "U", runner(fixture("ios-log-show-launch-cold.txt")), {}, sleep = {}, now = { LocalDateTime.of(2026, 10, 4, 15, 46, 51) })
        val cold = perf.measure("com.mobilenext.playground", StartKind.COLD)
        assertEquals(1061L, cold.millis)
        assertTrue(steps.first().startsWith("terminate"))
        val logCmd = recorded.first { it.contains("log") && it.contains("show") }
        assertEquals("2026-10-04 15:46:50", logCmd[logCmd.indexOf("--start") + 1])
        assertEquals(IosPerfParsers.logPredicate("com.mobilenext.playground"), logCmd[logCmd.indexOf("--predicate") + 1])
        val fallback = IosPerf("xcrun", "U", runner(""), {}, sleep = {}).measure("com.mobilenext.playground", StartKind.COLD)
        assertNotNull(fallback.millis)
        assertTrue(fallback.note.contains("fallback"))
    }

    @Test fun iosFootprintFailureIsAnErrorNotNotRunning() {
        val runner = FakeRunner { cmd ->
            if (cmd.contains("launchctl")) ok(fixture("ios-simctl-launchctl-playground.txt")) else CommandResult(1, ByteArray(0), "footprint: timed out")
        }
        try { IosPerf("xcrun", "U", runner, {}, sleep = {}).sample("com.mobilenext.playground"); org.junit.Assert.fail() } catch (e: PerfException) { assertTrue(e.message!!.contains("timed out")) }
    }

    @Test fun androidProbeScriptAlwaysExitsZero() {
        assertTrue(AndroidPerfParsers.probeScript("com.x").trimEnd().endsWith("; true"))
    }

    @Test fun iosWarmStartPressesHome() {
        var homes = 0
        val runner = FakeRunner { cmd ->
            when {
                cmd.contains("launchctl") -> ok(fixture("ios-simctl-launchctl-playground.txt"))
                cmd.contains("log") -> ok("")
                cmd.contains("launch") -> ok("com.mobilenext.playground: 36136\n")
                else -> ok("")
            }
        }
        IosPerf("xcrun", "U", runner, { homes++ }, sleep = {}).measure("com.mobilenext.playground", StartKind.WARM)
        assertEquals(1, homes)
        assertFalse(runner.commands.any { it.contains("terminate") })
    }
}
