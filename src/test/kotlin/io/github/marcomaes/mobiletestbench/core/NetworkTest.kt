package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.exec.CommandResult
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import io.github.marcomaes.mobiletestbench.core.network.AndroidNetwork
import io.github.marcomaes.mobiletestbench.core.network.DelayPreset
import io.github.marcomaes.mobiletestbench.core.network.EmuNetworkStatus
import io.github.marcomaes.mobiletestbench.core.network.NetworkActions
import io.github.marcomaes.mobiletestbench.core.network.NetworkChange
import io.github.marcomaes.mobiletestbench.core.network.NetworkCommands
import io.github.marcomaes.mobiletestbench.core.network.NetworkKey
import io.github.marcomaes.mobiletestbench.core.network.NetworkParsers
import io.github.marcomaes.mobiletestbench.core.network.NetworkReset
import io.github.marcomaes.mobiletestbench.core.network.NetworkState
import io.github.marcomaes.mobiletestbench.core.network.SpeedPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class NetworkTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    private class FakeRunner(val answer: (List<String>) -> CommandResult) : CommandRunner(emptyMap()) {
        val commands = mutableListOf<List<String>>()
        override fun run(command: List<String>, timeout: Duration): CommandResult {
            commands += command
            return answer(command)
        }
    }

    private fun ok(text: String) = CommandResult(0, text.toByteArray(), "")

    @Test fun parsesStatusFixtures() {
        assertEquals(EmuNetworkStatus(0, 0, 0, 0), NetworkParsers.emuStatus(fixture("android-emu-network-status-full.txt")))
        assertEquals(EmuNetworkStatus(473600, 473600, 80, 400), NetworkParsers.emuStatus(fixture("android-emu-network-status-edge.txt")))
        assertEquals(EmuNetworkStatus(200000, 100000, 150, 400), NetworkParsers.emuStatus(fixture("android-emu-network-status-custom.txt")))
        assertNull(NetworkParsers.emuStatus(fixture("android-emu-network-help.txt")))
    }

    @Test fun findsKoLinesIgnoringNoise() {
        assertEquals("invalid <speed> argument, see 'help network speed' for valid values", NetworkParsers.emuError(fixture("android-emu-network-speed-error.txt")))
        assertEquals("missing sub-command", NetworkParsers.emuError(fixture("android-emu-network-help.txt")))
        assertNull(NetworkParsers.emuError("Warning: time of day goes back (1) taking countermeasures.\nOK\n"))
        assertNull(NetworkParsers.emuError(fixture("android-emu-network-status-edge.txt")))
    }

    @Test fun parsesSwitches() {
        assertEquals(false, NetworkParsers.airplane("disabled\n"))
        assertEquals(true, NetworkParsers.airplane("enabled"))
        assertEquals(true, NetworkParsers.airplane("1\n"))
        assertNull(NetworkParsers.airplane("null"))
        assertEquals(true, NetworkParsers.wifi(fixture("android-wifi-status-enabled.txt")))
        assertEquals(false, NetworkParsers.wifi("Wifi is disabled\nWifi scanning is only available when wifi is enabled\n"))
        assertNull(NetworkParsers.wifi("Error: nope"))
        assertEquals(true, NetworkParsers.mobileData("1\n"))
        assertEquals(false, NetworkParsers.mobileData("0"))
        assertNull(NetworkParsers.mobileData("null"))
    }

    @Test fun parsesDefaultNetwork() {
        assertEquals(true, NetworkParsers.online("Active default network: 100\n"))
        assertEquals(false, NetworkParsers.online("Active default network: none\n"))
        assertNull(NetworkParsers.online(""))
    }

    @Test fun matchesPresetsAndPrefersTheChosenOne() {
        val full = EmuNetworkStatus(0, 0, 0, 0)
        assertEquals(SpeedPreset.FULL, NetworkParsers.matchSpeed(full))
        assertEquals(SpeedPreset.G5, NetworkParsers.matchSpeed(full, SpeedPreset.G5))
        assertEquals(SpeedPreset.EDGE, NetworkParsers.matchSpeed(EmuNetworkStatus(473600, 473600, 80, 400)))
        assertNull(NetworkParsers.matchSpeed(EmuNetworkStatus(200000, 100000, 150, 400)))
        assertEquals(DelayPreset.EDGE, NetworkParsers.matchDelay(EmuNetworkStatus(0, 0, 80, 400)))
        assertEquals(DelayPreset.UMTS, NetworkParsers.matchDelay(EmuNetworkStatus(0, 0, 35, 200), DelayPreset.UMTS))
        assertEquals(DelayPreset.GPRS, NetworkParsers.matchDelay(EmuNetworkStatus(0, 0, 35, 200)))
        assertNull(NetworkParsers.matchDelay(EmuNetworkStatus(0, 0, 150, 400)))
    }

    @Test fun describesStatus() {
        assertEquals("unlimited speed, no delay", EmuNetworkStatus(0, 0, 0, 0).describe())
        assertEquals("473600 bits/s down, 473600 bits/s up, 80-400 ms", EmuNetworkStatus(473600, 473600, 80, 400).describe())
    }

    @Test fun buildsCommands() {
        val c = NetworkCommands("adb", "emulator-5554")
        assertEquals(listOf("adb", "-s", "emulator-5554", "shell", "cmd", "connectivity", "airplane-mode", "enable"), c.airplane(true))
        assertEquals(listOf("adb", "-s", "emulator-5554", "shell", "svc", "wifi", "disable"), c.wifi(false))
        assertEquals(listOf("adb", "-s", "emulator-5554", "shell", "svc", "data", "enable"), c.mobileData(true))
        assertEquals(listOf("adb", "-s", "emulator-5554", "emu", "network", "speed", "edge"), c.speed("edge"))
        assertEquals(listOf("adb", "-s", "emulator-5554", "emu", "network", "delay", "none"), c.delay("none"))
    }

    private fun backend(emulator: Boolean = true, answer: (List<String>) -> String): Pair<AndroidNetwork, FakeRunner> {
        val runner = FakeRunner { ok(answer(it)) }
        return AndroidNetwork("adb", "emulator-5554", runner, emulator) to runner
    }

    @Test fun readsEverything() {
        val (net, _) = backend { cmd ->
            val line = cmd.drop(3).joinToString(" ")
            when {
                "airplane-mode" in line -> "disabled\n"
                "wifi status" in line -> fixture("android-wifi-status-enabled.txt")
                "mobile_data" in line -> "1\n"
                "dumpsys" in line -> "Active default network: 100\n"
                else -> fixture("android-emu-network-status-edge.txt")
            }
        }
        val s = net.read()
        assertEquals(false, s.airplane)
        assertEquals(true, s.wifi)
        assertEquals(true, s.mobileData)
        assertEquals(true, s.online)
        assertEquals(EmuNetworkStatus(473600, 473600, 80, 400), s.emulator)
        assertTrue(s.errors.isEmpty())
    }

    @Test fun readFallsBackToSettingAndKeepsOtherPartsOnError() {
        val (net, _) = backend(emulator = false) { cmd ->
            val line = cmd.drop(3).joinToString(" ")
            when {
                "cmd connectivity" in line -> "cmd: Can't find service: connectivity\n"
                "airplane_mode_on" in line -> "1\n"
                "wifi status" in line -> "garbage\n"
                "mobile_data" in line -> "0\n"
                else -> ""
            }
        }
        val s = net.read()
        assertEquals(true, s.airplane)
        assertNull(s.wifi)
        assertEquals("Wi-Fi state unknown", s.errors["wifi"])
        assertEquals(false, s.mobileData)
        assertNull(s.emulator)
    }

    @Test fun consoleKoIsAnErrorEvenWithExitCodeZero() {
        val (net, _) = backend { fixture("android-emu-network-speed-error.txt") }
        try {
            net.setSpeed("bogus")
            org.junit.Assert.fail("expected an error")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("invalid <speed> argument"))
        }
    }

    @Test fun nonZeroExitIsAnError() {
        val runner = FakeRunner { CommandResult(1, ByteArray(0), "adb: device offline\n") }
        val net = AndroidNetwork("adb", "emulator-5554", runner, true)
        try {
            net.setWifi(false)
            org.junit.Assert.fail("expected an error")
        } catch (e: Exception) {
            assertEquals("adb: device offline", e.message)
        }
    }

    private fun state(airplane: Boolean = false, wifi: Boolean = true, data: Boolean = true, emu: EmuNetworkStatus? = EmuNetworkStatus(0, 0, 0, 0)) =
        NetworkState(airplane, wifi, data, true, emu)

    @Test fun resetWithoutBaselineGoesToNormal() {
        val now = state(airplane = true, wifi = false, data = false, emu = EmuNetworkStatus(14400, 14400, 80, 400))
        val plan = NetworkReset.plan(null, now, emulator = true)
        assertEquals(
            listOf(
                NetworkChange(NetworkKey.AIRPLANE, on = false), NetworkChange(NetworkKey.WIFI, on = true), NetworkChange(NetworkKey.DATA, on = true),
                NetworkChange(NetworkKey.SPEED, arg = "full"), NetworkChange(NetworkKey.DELAY, arg = "none"),
            ),
            plan,
        )
        assertTrue(NetworkReset.plan(null, state(), emulator = true).isEmpty())
    }

    @Test fun resetRestoresOriginalsOnlyWhereDifferent() {
        val original = state(wifi = false, emu = EmuNetworkStatus(473600, 473600, 80, 400))
        val now = state(wifi = false, data = false, emu = EmuNetworkStatus(14400, 14400, 80, 400))
        val plan = NetworkReset.plan(original, now, emulator = true)
        assertEquals(listOf(NetworkChange(NetworkKey.DATA, on = true), NetworkChange(NetworkKey.SPEED, arg = "edge")), plan)
    }

    @Test fun resetOfCustomSpeedUsesKbitUpDown() {
        assertEquals("100:200", NetworkReset.speedArg(EmuNetworkStatus(200000, 100000, 150, 400)))
        assertEquals("150:400", NetworkReset.delayArg(EmuNetworkStatus(200000, 100000, 150, 400)))
    }

    @Test fun resetPutsAirplaneLastWhenItWasOn() {
        val plan = NetworkReset.plan(state(airplane = true, wifi = false), state(airplane = false, wifi = true), emulator = false)
        assertEquals(listOf(NetworkChange(NetworkKey.WIFI, on = false), NetworkChange(NetworkKey.AIRPLANE, on = true)), plan)
    }

    @Test fun emulatorPartSkippedForDevices() {
        assertTrue(NetworkReset.plan(null, state(emu = null), emulator = false).isEmpty())
    }

    @Test fun actionsRememberFirstStateAndClearOnSuccessfulReset() {
        val wifi = booleanArrayOf(true)
        val (net, runner) = backend(emulator = false) { cmd ->
            val line = cmd.drop(3).joinToString(" ")
            when {
                "svc wifi" in line -> { wifi[0] = line.endsWith("enable"); "" }
                "airplane-mode" in line -> "disabled\n"
                "wifi status" in line -> if (wifi[0]) "Wifi is enabled\n" else "Wifi is disabled\n"
                "mobile_data" in line -> "1\n"
                else -> ""
            }
        }
        val actions = NetworkActions()
        val before = state(emu = null)
        assertNull(actions.change("d", net, before, listOf(NetworkChange(NetworkKey.WIFI, on = false))))
        assertEquals(before, actions.baseline.originals("d"))
        // a second change must not overwrite the remembered original
        actions.change("d", net, state(wifi = false, emu = null), listOf(NetworkChange(NetworkKey.DATA, on = false)))
        assertEquals(before, actions.baseline.originals("d"))
        val (failure, restored) = actions.reset("d", net)
        assertNull(failure)
        assertTrue(restored)
        assertNull(actions.baseline.originals("d"))
        assertTrue(runner.commands.any { it.contains("svc") && it.contains("wifi") && it.last() == "enable" })
        assertFalse(wifi[0].not())
    }

    @Test fun javaExceptionOnStdoutIsAnErrorEvenWithExitCodeZero() {
        val (net, _) = backend(emulator = false) { "java.lang.IllegalArgumentException: bad\n" }
        try {
            net.setWifi(false)
            org.junit.Assert.fail("expected an error")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("IllegalArgumentException"))
        }
    }

    @Test fun failedFirstWriteLeavesNoBaselineAndLaterChangesDoNotRun() {
        val (net, runner) = backend(emulator = false) { "Error: refused\n" }
        val actions = NetworkActions()
        val failure = actions.change("d", net, state(emu = null), listOf(NetworkChange(NetworkKey.WIFI, on = false), NetworkChange(NetworkKey.DATA, on = false)))
        assertTrue(failure!!.contains("refused"))
        assertNull(actions.baseline.originals("d"))
        assertEquals(1, runner.commands.size)
    }

    @Test fun laterFailureKeepsBaselineAndStops() {
        var n = 0
        val (net, runner) = backend(emulator = false) { if (++n == 2) "Exception: no\n" else "" }
        val actions = NetworkActions()
        val before = state(emu = null)
        val changes = listOf(NetworkChange(NetworkKey.WIFI, on = false), NetworkChange(NetworkKey.DATA, on = false), NetworkChange(NetworkKey.AIRPLANE, on = true))
        assertTrue(actions.change("d", net, before, changes)!!.contains("Exception"))
        assertEquals(before, actions.baseline.originals("d"))
        assertEquals(2, runner.commands.size)
    }

    @Test fun failedResetKeepsBaseline() {
        val (net, _) = backend(emulator = false) { cmd ->
            val line = cmd.drop(3).joinToString(" ")
            when {
                "svc" in line -> "Error: refused\n"
                "airplane-mode" in line -> "disabled\n"
                "wifi status" in line -> "Wifi is disabled\n"
                "mobile_data" in line -> "1\n"
                else -> ""
            }
        }
        val actions = NetworkActions()
        actions.baseline.remember("d", state(emu = null))
        val (failure, _) = actions.reset("d", net)
        assertTrue(failure != null)
        assertTrue(actions.baseline.originals("d") != null)
    }
}
