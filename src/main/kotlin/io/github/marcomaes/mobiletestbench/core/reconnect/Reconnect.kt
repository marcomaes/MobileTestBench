package io.github.marcomaes.mobiletestbench.core.reconnect

import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.discovery.DiscoveryResult
import io.github.marcomaes.mobiletestbench.core.power.Polling

enum class ReconnectPhase { IDLE, CONNECTED, WAITING }

sealed class ReconnectEvent {
    /** Nothing to do (device still there, or discovery gave no answer). */
    object None : ReconnectEvent()
    /** The device was missing or unusable for two polls in a row (or a session error plus one poll). */
    data class Lost(val device: Device) : ReconnectEvent()
    /** Still waiting after a poll; [waitedMs] is the time since the loss. */
    data class StillWaiting(val device: Device, val waitedMs: Long) : ReconnectEvent()
    /** The same device is usable again; [device] is the fresh entry (an emulator serial can differ after a boot). */
    data class Reconnected(val device: Device) : ReconnectEvent()
}

/**
 * The reconnect state machine: IDLE (not watching) -> CONNECTED (watching a live session) -> WAITING (lost, polling until it is back)
 * -> IDLE again once [ReconnectEvent.Reconnected] was returned; the caller then connects and calls [watch] again.
 *
 * It never schedules anything itself: the caller calls [tick] every [pollDelayMs]. [discover] returns the discovered devices, or
 * null when discovery itself failed (an inconclusive poll, which changes nothing). Matching uses [Device.isSameDevice] through
 * [Polling.usableMatch], so an emulator that comes back with another adb serial is still the same device.
 */
class ReconnectWatcher(
    private val discover: () -> List<Device>?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @get:Synchronized var phase: ReconnectPhase = ReconnectPhase.IDLE
        private set
    @get:Synchronized var target: Device? = null
        private set
    private var misses = 0
    private var attempts = 0
    private var lostAt = 0L

    /** Start watching a connected device. */
    @Synchronized fun watch(device: Device) {
        phase = ReconnectPhase.CONNECTED
        target = device
        misses = 0
        attempts = 0
    }

    /** Go straight to waiting (the caller shut the device down or rebooted it on purpose and wants it back). */
    @Synchronized fun waitFor(device: Device) {
        phase = ReconnectPhase.WAITING
        target = device
        misses = 0
        attempts = 0
        lostAt = clock()
    }

    /** Stop (Disconnect, another device chosen, dispose, Cancel). */
    @Synchronized fun stop() {
        phase = ReconnectPhase.IDLE
        target = null
        misses = 0
        attempts = 0
    }

    /** The session reported an error: count it as one missed poll, so one more missing poll confirms the loss. */
    @Synchronized fun sessionFailed() {
        if (phase == ReconnectPhase.CONNECTED && misses < 1) misses = 1
    }

    /** How long to wait before the next [tick]: 3 s while connected, growing by 1 s per waiting poll up to 10 s. */
    @Synchronized fun pollDelayMs(): Long = when (phase) {
        ReconnectPhase.WAITING -> minOf(MAX_DELAY_MS, BASE_DELAY_MS + attempts * STEP_MS)
        else -> BASE_DELAY_MS
    }

    @Synchronized fun waitedMs(): Long = if (phase == ReconnectPhase.WAITING) clock() - lostAt else 0

    /** One poll. Run it off the EDT: [discover] blocks. */
    fun tick(): ReconnectEvent {
        val current = synchronized(this) { if (phase == ReconnectPhase.IDLE) return ReconnectEvent.None else target!! }
        val devices = runCatching(discover).getOrNull() ?: return ReconnectEvent.None
        val match = Polling.usableMatch(devices, current)
        return synchronized(this) {
            // stop() or a new watch() may have happened while discovery ran: drop the stale answer.
            if (phase == ReconnectPhase.IDLE || target !== current) return@synchronized ReconnectEvent.None
            when (phase) {
                ReconnectPhase.CONNECTED -> when {
                    match != null -> { misses = 0; ReconnectEvent.None }
                    ++misses >= LOST_AFTER_MISSES -> {
                        phase = ReconnectPhase.WAITING
                        attempts = 0
                        lostAt = clock()
                        ReconnectEvent.Lost(current)
                    }
                    else -> ReconnectEvent.None
                }
                else -> if (match != null) {
                    phase = ReconnectPhase.IDLE
                    target = null
                    ReconnectEvent.Reconnected(match)
                } else {
                    attempts++
                    ReconnectEvent.StillWaiting(current, clock() - lostAt)
                }
            }
        }
    }

    companion object {
        /**
         * Turns a discovery result into the watcher's input. Discovery never throws: a failing adb, simctl or mobilecli adds a warning and
         * the devices are just missing. So a result with warnings that does not list a usable [target] proves nothing and is inconclusive (null).
         */
        fun conclusive(result: DiscoveryResult, target: Device?): List<Device>? =
            if (result.warnings.isNotEmpty() && result.devices.none { it.isUsable && it.isSameDevice(target) }) null else result.devices

        const val LOST_AFTER_MISSES = 2
        const val BASE_DELAY_MS = 3_000L
        const val MAX_DELAY_MS = 10_000L
        const val STEP_MS = 1_000L

        fun waitingText(device: Device, waitedMs: Long): String =
            "Waiting for ${device.name} to come back..." + if (waitedMs >= 10_000) " (${waitedMs / 1000} s)" else ""
    }
}
