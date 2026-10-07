package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.discovery.DiscoveryResult
import io.github.marcomaes.mobiletestbench.core.reconnect.ReconnectEvent
import io.github.marcomaes.mobiletestbench.core.reconnect.ReconnectPhase
import io.github.marcomaes.mobiletestbench.core.reconnect.ReconnectWatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectTest {
    private val pixel = Device("emulator-5554", "Pixel_9", Platform.ANDROID, DeviceKind.EMULATOR, state = "device", avdName = "Pixel_9")
    private val iphone = Device("UDID-1", "iPhone 17 Pro", Platform.IOS, DeviceKind.SIMULATOR, state = "Booted")

    private var devices: List<Device>? = emptyList()
    private var now = 0L
    private val watcher = ReconnectWatcher({ devices }, { now })

    @Test fun staysConnectedWhileListed() {
        devices = listOf(pixel)
        watcher.watch(pixel)
        repeat(5) { assertEquals(ReconnectEvent.None, watcher.tick()) }
        assertEquals(ReconnectPhase.CONNECTED, watcher.phase)
    }

    @Test fun oneMissingPollIsNotEnough() {
        watcher.watch(pixel)
        devices = emptyList()
        assertEquals(ReconnectEvent.None, watcher.tick())
        devices = listOf(pixel)
        assertEquals(ReconnectEvent.None, watcher.tick())
        devices = emptyList()
        // the miss counter was reset by the good poll
        assertEquals(ReconnectEvent.None, watcher.tick())
        assertEquals(ReconnectPhase.CONNECTED, watcher.phase)
    }

    @Test fun twoMissingPollsInARowMeanLost() {
        watcher.watch(pixel)
        devices = emptyList()
        assertEquals(ReconnectEvent.None, watcher.tick())
        assertEquals(ReconnectEvent.Lost(pixel), watcher.tick())
        assertEquals(ReconnectPhase.WAITING, watcher.phase)
    }

    @Test fun unusableCountsAsMissing() {
        watcher.watch(pixel)
        devices = listOf(pixel.copy(state = "offline"))
        watcher.tick()
        assertTrue(watcher.tick() is ReconnectEvent.Lost)
        // a bootable offline entry is not usable either
        watcher.watch(pixel)
        devices = listOf(pixel.copy(id = "Pixel_9", state = "offline", bootable = true))
        watcher.tick()
        assertTrue(watcher.tick() is ReconnectEvent.Lost)
    }

    @Test fun inconclusiveDiscoveryChangesNothing() {
        watcher.watch(pixel)
        devices = null
        repeat(4) { assertEquals(ReconnectEvent.None, watcher.tick()) }
        assertEquals(ReconnectPhase.CONNECTED, watcher.phase)
    }

    @Test fun discoveryWithWarningsAndNoTargetIsInconclusive() {
        val failed = DiscoveryResult(emptyList(), listOf("Android: adb timed out"))
        assertNull(ReconnectWatcher.conclusive(failed, pixel))
        assertEquals(emptyList<Device>(), ReconnectWatcher.conclusive(DiscoveryResult(emptyList(), emptyList()), pixel))
        val partial = DiscoveryResult(listOf(pixel), listOf("iOS: simctl failed"))
        assertEquals(listOf(pixel), ReconnectWatcher.conclusive(partial, pixel))
        // failed polls in a row never make the watcher give up
        devices = null
        watcher.watch(pixel)
        repeat(4) { assertEquals(ReconnectEvent.None, watcher.tick()) }
        assertEquals(ReconnectPhase.CONNECTED, watcher.phase)
    }

    @Test fun throwingDiscoveryIsInconclusive() {
        val w = ReconnectWatcher({ error("adb died") }, { now })
        w.watch(pixel)
        repeat(3) { assertEquals(ReconnectEvent.None, w.tick()) }
        assertEquals(ReconnectPhase.CONNECTED, w.phase)
    }

    @Test fun sessionErrorPlusOneMissingPollIsLost() {
        watcher.watch(pixel)
        watcher.sessionFailed()
        devices = emptyList()
        assertEquals(ReconnectEvent.Lost(pixel), watcher.tick())
    }

    @Test fun sessionErrorAloneDoesNotLoseAListedDevice() {
        watcher.watch(pixel)
        watcher.sessionFailed()
        devices = listOf(pixel)
        assertEquals(ReconnectEvent.None, watcher.tick())
        devices = emptyList()
        assertEquals(ReconnectEvent.None, watcher.tick())
    }

    @Test fun reconnectsWhenSameDeviceIsBack() {
        watcher.watch(iphone)
        devices = emptyList()
        watcher.tick(); watcher.tick()
        now = 5_000
        assertEquals(ReconnectEvent.StillWaiting(iphone, 5_000), watcher.tick())
        devices = listOf(pixel)
        assertTrue(watcher.tick() is ReconnectEvent.StillWaiting)
        devices = listOf(pixel, iphone)
        assertEquals(ReconnectEvent.Reconnected(iphone), watcher.tick())
        assertEquals(ReconnectPhase.IDLE, watcher.phase)
        assertEquals(ReconnectEvent.None, watcher.tick())
    }

    @Test fun emulatorWithNewSerialIsTheSameDevice() {
        watcher.watch(pixel)
        devices = emptyList(); watcher.tick(); watcher.tick()
        val rebooted = pixel.copy(id = "emulator-5556")
        devices = listOf(rebooted)
        assertEquals(ReconnectEvent.Reconnected(rebooted), watcher.tick())
    }

    @Test fun waitingIgnoresOfflineEntryOfTheSameDevice() {
        watcher.waitFor(pixel)
        devices = listOf(pixel.copy(id = "Pixel_9", state = "offline", bootable = true))
        assertTrue(watcher.tick() is ReconnectEvent.StillWaiting)
        assertEquals(ReconnectPhase.WAITING, watcher.phase)
    }

    @Test fun backoffGrowsFromThreeToTenSeconds() {
        assertEquals(3_000, watcher.pollDelayMs())
        watcher.watch(pixel)
        assertEquals(3_000, watcher.pollDelayMs())
        devices = emptyList(); watcher.tick(); watcher.tick()
        val delays = mutableListOf(watcher.pollDelayMs())
        repeat(10) { watcher.tick(); delays += watcher.pollDelayMs() }
        assertEquals(listOf(3_000L, 4_000, 5_000, 6_000, 7_000, 8_000, 9_000, 10_000, 10_000, 10_000, 10_000), delays)
    }

    @Test fun noTimeLimit() {
        watcher.waitFor(pixel)
        devices = emptyList()
        now = 24 * 3_600_000L
        assertTrue(watcher.tick() is ReconnectEvent.StillWaiting)
        assertEquals(ReconnectPhase.WAITING, watcher.phase)
    }

    @Test fun stopCancelsEverything() {
        watcher.watch(pixel)
        watcher.stop()
        devices = emptyList()
        assertEquals(ReconnectEvent.None, watcher.tick())
        assertNull(watcher.target)
        watcher.waitFor(pixel)
        watcher.stop()
        devices = listOf(pixel)
        assertEquals(ReconnectEvent.None, watcher.tick())
        assertEquals(ReconnectPhase.IDLE, watcher.phase)
    }

    @Test fun answerForAReplacedTargetIsDropped() {
        // discovery runs while the user picks another device: the old answer must not mark the new one lost
        lateinit var w: ReconnectWatcher
        w = ReconnectWatcher({ w.watch(iphone); emptyList() }, { now })
        w.watch(pixel)
        assertEquals(ReconnectEvent.None, w.tick())
        assertEquals(iphone, w.target)
        assertEquals(ReconnectPhase.CONNECTED, w.phase)
    }

    @Test fun waitForGoesStraightToWaitingAndReconnects() {
        now = 100
        watcher.waitFor(iphone)
        assertEquals(ReconnectPhase.WAITING, watcher.phase)
        now = 4_100
        assertEquals(4_000, watcher.waitedMs())
        devices = listOf(iphone)
        assertEquals(ReconnectEvent.Reconnected(iphone), watcher.tick())
    }

    @Test fun waitingText() {
        assertEquals("Waiting for Pixel_9 to come back...", ReconnectWatcher.waitingText(pixel, 3_000))
        assertEquals("Waiting for Pixel_9 to come back... (12 s)", ReconnectWatcher.waitingText(pixel, 12_500))
    }
}
