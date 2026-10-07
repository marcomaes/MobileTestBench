package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.exec.CommandResult
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import io.github.marcomaes.mobiletestbench.core.android.AdbClient
import io.github.marcomaes.mobiletestbench.core.discovery.DeviceDiscovery
import io.github.marcomaes.mobiletestbench.core.ios.MobilecliClient
import io.github.marcomaes.mobiletestbench.core.power.OfflineDevices
import io.github.marcomaes.mobiletestbench.core.power.Polling
import io.github.marcomaes.mobiletestbench.core.power.PowerAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Duration

class DevicePowerTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    private val runningIos = Device("F2368465-C17E-4E1D-B270-D9433E8A6622", "iPhone 17 Pro", Platform.IOS, DeviceKind.SIMULATOR, "iOS 26.5", "Booted")
    private val runningAndroid = Device("emulator-5554", "Pixel 9", Platform.ANDROID, DeviceKind.EMULATOR, "Android 17", "device", avdName = "Pixel_9")

    @Test fun parsesOnlyOfflineEntries() {
        val offline = OfflineDevices.parse(fixture("mobilecli-devices-include-offline.json"))
        assertEquals(1, offline.size)
        val d = offline.single()
        assertEquals("33915C06-CAF3-430C-9607-AFD7B6AAF75E", d.id)
        assertEquals("iPhone 18 Pro", d.name)
        assertEquals(DeviceKind.SIMULATOR, d.kind)
        assertEquals("iOS 27.0", d.osVersion)
        assertTrue(d.bootable)
        assertFalse(d.isUsable)
        assertTrue(d.displayName.endsWith("(offline)"))
    }

    @Test fun offlineAvdKeepsIdAndNamePieces() {
        val json = """{"status":"ok","data":{"devices":[{"id":"Scout_Tmp","name":"Scout Tmp","platform":"android","type":"emulator","version":"16.0","state":"offline","model":""},
            {"id":"X","name":"Real","platform":"ios","type":"real","state":"offline"},{"id":"","name":"x","platform":"ios","type":"simulator","state":"offline"}]}}"""
        val d = OfflineDevices.parse(json).single()
        assertEquals("Scout_Tmp", d.id)
        assertEquals("Scout Tmp", d.name)
        assertEquals("Android 16.0", d.osVersion)
        assertEquals("Scout_Tmp", d.avdKey)
    }

    @Test fun errorEnvelopeAndEmptyData() {
        try {
            OfflineDevices.parse(fixture("mobilecli-device-notfound-error.json"))
            fail()
        } catch (e: MobilecliException) {
            assertTrue(e.message!!.contains("device not found"))
        }
        assertTrue(OfflineDevices.parse("""{"status":"ok","data":{}}""").isEmpty())
    }

    @Test fun runningAvdIsNotListedAgainAsOffline() {
        val json = """{"status":"ok","data":{"devices":[{"id":"Pixel_9","name":"Pixel 9","platform":"android","type":"emulator","version":"17","state":"offline"},
            {"id":"Small","name":"Small","platform":"android","type":"emulator","version":"16","state":"offline"}]}}"""
        val missing = OfflineDevices.missingFrom(listOf(runningAndroid, runningIos), json)
        assertEquals(listOf("Small"), missing.map { it.id })
    }

    @Test fun runningSimulatorIsNotListedAgain() {
        val missing = OfflineDevices.missingFrom(listOf(runningIos), fixture("mobilecli-devices-include-offline.json"))
        assertEquals(listOf("iPhone 18 Pro"), missing.map { it.name })
    }

    @Test fun availabilityFollowsState() {
        val offline = OfflineDevices.parse(fixture("mobilecli-devices-include-offline.json")).single()
        assertTrue(PowerAction.BOOT.isAvailableFor(offline))
        assertFalse(PowerAction.SHUTDOWN.isAvailableFor(offline))
        assertFalse(PowerAction.REBOOT.isAvailableFor(offline))
        assertFalse(PowerAction.BOOT.isAvailableFor(runningIos))
        assertTrue(PowerAction.SHUTDOWN.isAvailableFor(runningAndroid))
        assertTrue(PowerAction.REBOOT.isAvailableFor(runningIos))
        assertFalse(PowerAction.BOOT.isAvailableFor(null))
    }

    @Test fun adbOfflineTransportIsNotBootable() {
        val adbOffline = Device("emulator-5554", "emulator-5554", Platform.ANDROID, DeviceKind.EMULATOR, state = "offline")
        assertFalse(adbOffline.isUsable)
        assertFalse(PowerAction.BOOT.isAvailableFor(adbOffline))
        assertFalse(PowerAction.SHUTDOWN.isAvailableFor(adbOffline))
        assertTrue(adbOffline.displayName.endsWith("(offline)"))
    }

    @Test fun physicalDevicesHaveNoPower() {
        val phone = Device("R5CT", "Galaxy", Platform.ANDROID, DeviceKind.PHYSICAL, state = "device")
        assertFalse(PowerAction.SHUTDOWN.isAvailableFor(phone))
    }

    @Test fun sameDeviceMatchesAvdAcrossSerials() {
        val offline = Device("Pixel_9", "Pixel 9", Platform.ANDROID, DeviceKind.EMULATOR, state = "offline", bootable = true)
        assertTrue(offline.isSameDevice(runningAndroid))
        assertTrue(runningAndroid.isSameDevice(runningAndroid.copy(id = "emulator-5556")))
        assertFalse(runningAndroid.isSameDevice(runningAndroid.copy(id = "emulator-5556", avdName = "Other")))
        assertFalse(runningAndroid.isSameDevice(runningIos))
    }

    @Test fun commandsAndTimeouts() {
        assertEquals(listOf("device", "boot"), PowerAction.BOOT.args)
        assertEquals(listOf("device", "shutdown"), PowerAction.SHUTDOWN.args)
        assertEquals(listOf("device", "reboot"), PowerAction.REBOOT.args)
        assertTrue(PowerAction.BOOT.timeout > Duration.ofSeconds(20))
        assertFalse(PowerAction.BOOT.needsConfirmation)
        assertTrue(PowerAction.SHUTDOWN.needsConfirmation && PowerAction.REBOOT.needsConfirmation)
    }

    private class Fake(val stdout: String, val exit: Int = 0, val stderr: String = "") : CommandRunner() {
        var command: List<String>? = null
        var timeout: Duration? = null
        override fun run(command: List<String>, timeout: Duration): CommandResult {
            this.command = command
            this.timeout = timeout
            return CommandResult(exit, stdout.toByteArray(), stderr)
        }
    }

    @Test fun bootCallBuildsCommandLineAndUsesLongTimeout() {
        val runner = Fake(fixture("mobilecli-device-boot-ok.json"))
        MobilecliClient("mobilecli", runner).call("33915C06", PowerAction.BOOT.args, PowerAction.BOOT.timeout)
        assertEquals(listOf("mobilecli", "device", "boot", "--device", "33915C06"), runner.command)
        assertEquals(Duration.ofSeconds(120), runner.timeout)
    }

    @Test fun bootOfRunningDeviceSurfacesMobilecliText() {
        val runner = Fake(fixture("mobilecli-device-boot-error-running.json"), exit = 1, stderr = "plain")
        try {
            MobilecliClient("mobilecli", runner).call("x", PowerAction.BOOT.args)
            fail()
        } catch (e: MobilecliException) {
            assertTrue(e.message!!.contains("simulator is already running"))
        }
    }

    @Test fun shutdownOfOfflineDeviceSurfacesMobilecliText() {
        val runner = Fake(fixture("mobilecli-device-shutdown-error-offline.json"), exit = 1)
        try {
            MobilecliClient("mobilecli", runner).call("x", PowerAction.SHUTDOWN.args)
            fail()
        } catch (e: MobilecliException) {
            assertTrue(e.message!!.contains("already offline"))
        }
    }

    @Test fun offlineListingCommand() {
        val runner = Fake(fixture("mobilecli-devices-include-offline.json"))
        MobilecliClient("mobilecli", runner).devicesIncludingOfflineJson()
        assertEquals(listOf("mobilecli", "devices", "--include-offline"), runner.command)
    }

    @Test fun pollingFindsDeviceAfterSomeRounds() {
        var clock = 0L
        var calls = 0
        val found = Polling.until(60_000, 2_000, now = { clock }, sleep = { clock += it }) { if (++calls == 4) "up" else null }
        assertEquals("up", found)
        assertEquals(6_000L, clock)
    }

    @Test fun pollingTimesOutAndIgnoresProbeErrors() {
        var clock = 0L
        val found = Polling.until<String>(10_000, 2_000, now = { clock }, sleep = { clock += it }) { error("adb down") }
        assertNull(found)
        assertEquals(10_000L, clock)
    }

    @Test fun pollingStopsWhenCancelled() {
        var calls = 0
        val found = Polling.until<String>(60_000, 1, sleep = {}, cancelled = { calls >= 2 }) { calls++; null }
        assertNull(found)
        assertEquals(2, calls)
    }

    @Test fun usableMatchSkipsStillOfflineEntry() {
        val offline = Device("Pixel_9", "Pixel 9", Platform.ANDROID, DeviceKind.EMULATOR, state = "offline", bootable = true)
        assertNull(Polling.usableMatch(listOf(offline), offline))
        assertNotNull(Polling.usableMatch(listOf(offline, runningAndroid), offline))
    }

    @Test fun reselectPrefersUsableThenOfflineEntryOfSameAvd() {
        val offline = Device("Pixel_9", "Pixel 9", Platform.ANDROID, DeviceKind.EMULATOR, state = "offline", bootable = true)
        // After shutting down emulator-5554 only the offline entry is left: it must stay selected, not the first device.
        assertEquals(offline, Polling.reselect(listOf(runningIos, offline), runningAndroid))
        // A usable match wins over an offline one.
        val rebooted = runningAndroid.copy(id = "emulator-5556")
        assertEquals(rebooted, Polling.reselect(listOf(offline, rebooted), runningAndroid))
        assertNull(Polling.reselect(listOf(runningIos), runningAndroid))
        assertNull(Polling.reselect(listOf(runningIos), null))
    }

    @Test fun parseOfNonJsonIsEmpty() {
        assertTrue(OfflineDevices.parse("not json at all").isEmpty())
        assertTrue(OfflineDevices.parse("").isEmpty())
    }

    /** Answers `adb devices -l` with one emulator and `mobilecli devices --include-offline` with [offlineStdout]. */
    private class DiscoveryFake(val offlineStdout: String, val offlineExit: Int = 0) : CommandRunner() {
        override fun run(command: List<String>, timeout: Duration): CommandResult = when {
            command.first() == "adb" && command.contains("devices") ->
                CommandResult(0, "List of devices attached\nemulator-5554          device product:sdk model:sdk_gphone device:emu transport_id:1\n\n".toByteArray(), "")
            command.first() == "mobilecli" && command.contains("--include-offline") -> CommandResult(offlineExit, offlineStdout.toByteArray(), "boom")
            else -> CommandResult(1, ByteArray(0), "unexpected")
        }
    }

    private fun discover(runner: CommandRunner) =
        DeviceDiscovery(AdbClient("adb", runner), null, MobilecliClient("mobilecli", runner)).discover()

    @Test fun discoveryAppendsOfflineEntriesAfterOnlineOnes() {
        val json = """{"status":"ok","data":{"devices":[{"id":"Small","name":"Small","platform":"android","type":"emulator","version":"16","state":"offline"}]}}"""
        val result = discover(DiscoveryFake(json))
        assertEquals(listOf("emulator-5554", "Small"), result.devices.map { it.id })
        assertTrue(result.devices.last().bootable)
    }

    @Test fun discoveryExplainsAMissingAdb() {
        val runner = object : CommandRunner() {
            override fun run(command: List<String>, timeout: Duration): CommandResult =
                throw java.io.IOException("Cannot run program \"/nonexistent/adb\": error=2, No such file or directory")
        }
        val result = DeviceDiscovery(AdbClient("/nonexistent/adb", runner), null).discover()
        assertTrue(result.devices.isEmpty())
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().contains("adb was not found (/nonexistent/adb)"))
    }

    @Test fun discoveryKeepsOnlineDevicesWhenOfflineListingFails() {
        val failed = discover(DiscoveryFake("", offlineExit = 1))
        assertEquals(listOf("emulator-5554"), failed.devices.map { it.id })
        assertTrue(failed.warnings.any { it.startsWith("Offline devices") })
        val garbage = discover(DiscoveryFake("garbage output"))
        assertEquals(listOf("emulator-5554"), garbage.devices.map { it.id })
    }
}
