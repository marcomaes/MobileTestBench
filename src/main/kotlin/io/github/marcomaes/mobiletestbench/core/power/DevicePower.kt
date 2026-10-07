package io.github.marcomaes.mobiletestbench.core.power

import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.DeviceKind
import io.github.marcomaes.mobiletestbench.core.MobilecliJson
import io.github.marcomaes.mobiletestbench.core.Platform
import java.time.Duration

/** What the Device Power menu can do. */
enum class PowerAction(val label: String) {
    BOOT("Boot"),
    SHUTDOWN("Shut Down"),
    REBOOT("Reboot");

    /** `mobilecli device <sub>`; the caller appends `--device <id>`. */
    val args: List<String> get() = listOf("device", name.lowercase())

    /** Boot blocks until the device is usable (8 s for a simulator, 30 s for a cold AVD), so it gets a long timeout. */
    val timeout: Duration get() = if (this == SHUTDOWN) Duration.ofSeconds(60) else Duration.ofSeconds(120)

    /** Shutdown and reboot lose the state of a running device, so they ask first. */
    val needsConfirmation: Boolean get() = this != BOOT

    /** Not idempotent in mobilecli (booting a running device or shutting down an offline one is an error), so the state decides. */
    fun isAvailableFor(device: Device?): Boolean = when {
        device == null -> false
        this == BOOT -> device.bootable
        else -> device.isUsable && (device.kind == DeviceKind.EMULATOR || device.kind == DeviceKind.SIMULATOR)
    }

    companion object {
        /** The user sees "Booting ..." while the command runs. */
        fun progressText(action: PowerAction, device: Device): String = when (action) {
            BOOT -> "Booting ${device.name}..."
            SHUTDOWN -> "Shutting down ${device.name}..."
            REBOOT -> "Rebooting ${device.name}..."
        }

        fun confirmText(action: PowerAction, device: Device): String = when (action) {
            SHUTDOWN -> "Shut down ${device.name}? Anything not saved in the device is lost."
            REBOOT -> "Reboot ${device.name}? Anything not saved in the device is lost."
            BOOT -> "Boot ${device.name}?"
        }
    }
}

/** Offline entries of `mobilecli devices --include-offline`: stopped AVDs and shut-down simulators. */
object OfflineDevices {
    /** Only entries with state "offline" that mobilecli can boot (emulators and simulators; physical devices are not offered). */
    fun parse(json: String): List<Device> {
        val devices = MobilecliJson.data(json)?.takeIf { it.isJsonObject }?.asJsonObject?.get("devices")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()
        return devices.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            if (obj["state"]?.asString != "offline") return@mapNotNull null
            val id = obj["id"]?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val platform = when (obj["platform"]?.asString) {
                "android" -> Platform.ANDROID
                "ios" -> Platform.IOS
                else -> return@mapNotNull null
            }
            val kind = when (obj["type"]?.asString?.lowercase()) {
                "emulator" -> DeviceKind.EMULATOR
                "simulator" -> DeviceKind.SIMULATOR
                else -> return@mapNotNull null
            }
            val version = obj["version"]?.asString?.takeIf { it.isNotBlank() }
            Device(
                id = id,
                name = obj["name"]?.asString?.takeIf { it.isNotBlank() } ?: id,
                platform = platform,
                kind = kind,
                osVersion = version?.let { (if (platform == Platform.IOS) "iOS " else "Android ") + it },
                state = "offline",
                bootable = true,
            )
        }
    }

    /** The offline entries of [json] that are not already running in [online] (matched by UDID, or by AVD name for emulators). */
    fun missingFrom(online: List<Device>, json: String): List<Device> =
        parse(json).filter { offline -> online.none { it.isSameDevice(offline) } }
}

/** Polling with an injected clock so it can be tested without waiting. */
object Polling {
    /** Calls [probe] every [intervalMs] until it returns non null, [timeoutMs] passed, or [cancelled]. Probe errors count as "not yet". */
    fun <T : Any> until(
        timeoutMs: Long,
        intervalMs: Long,
        now: () -> Long = System::currentTimeMillis,
        sleep: (Long) -> Unit = Thread::sleep,
        cancelled: () -> Boolean = { false },
        probe: () -> T?,
    ): T? {
        val deadline = now() + timeoutMs
        while (true) {
            if (cancelled()) return null
            val found = runCatching(probe).getOrNull()
            if (found != null) return found
            if (now() >= deadline) return null
            sleep(intervalMs)
        }
    }

    /** The usable device equal to [target] in [devices], if any. */
    fun usableMatch(devices: List<Device>, target: Device): Device? =
        devices.firstOrNull { it.isUsable && it.isSameDevice(target) }

    /** The entry to keep selected after a refresh: same id, else the same device (a usable one first, then an offline AVD entry after a shutdown). */
    fun reselect(devices: List<Device>, previous: Device?): Device? =
        devices.firstOrNull { it.id == previous?.id }
            ?: devices.firstOrNull { it.isUsable && it.isSameDevice(previous) }
            ?: devices.firstOrNull { it.isSameDevice(previous) }
}
