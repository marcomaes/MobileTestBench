package io.github.marcomaes.mobiletestbench.core

enum class Platform { ANDROID, IOS }

enum class DeviceKind { EMULATOR, SIMULATOR, PHYSICAL }

data class Device(
    val id: String,
    val name: String,
    val platform: Platform,
    val kind: DeviceKind,
    val osVersion: String? = null,
    val state: String = "online",
    /** True for an offline entry that mobilecli can boot (a stopped AVD or shut-down simulator). Not the same as adb's "offline" transport state. */
    val bootable: Boolean = false,
    /** The AVD name of a running Android emulator (the adb serial is not stable across boots); null when unknown or not an emulator. */
    val avdName: String? = null,
) {
    val isUsable: Boolean get() = state == "device" || state == "online" || state == "Booted"

    val displayName: String
        get() = buildString {
            append(name)
            osVersion?.let { append(" ").append(it) }
            append(" [").append(kind.name.lowercase()).append("]")
            if (bootable) append(" (offline)") else if (!isUsable) append(" (").append(state).append(")")
        }

    /** Identifies "the same emulator" across a boot or reboot: the AVD name of a running or a bootable emulator. */
    val avdKey: String? get() = avdName ?: if (bootable && kind == DeviceKind.EMULATOR) id else null

    /** Same UDID or serial, or the same Android emulator (matched by AVD name, since the serial changes between boots). */
    fun isSameDevice(other: Device?): Boolean =
        other != null && (id == other.id || (platform == other.platform && avdKey != null && avdKey == other.avdKey))

    override fun toString(): String = displayName
}

/** Hardware and navigation keys the toolbar and keyboard forwarding can send. */
enum class DeviceKey { HOME, BACK, APP_SWITCH, ENTER, DELETE, VOLUME_UP, VOLUME_DOWN, POWER }

data class Size(val width: Int, val height: Int) {
    val isEmpty: Boolean get() = width <= 0 || height <= 0
    val isLandscape: Boolean get() = width > height
    fun swapped(): Size = Size(height, width)
}

/** Axis aligned rectangle in the device's logical coordinate space. */
data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val area: Long get() = width.toLong() * height.toLong()
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2
    fun contains(px: Int, py: Int): Boolean = px >= x && py >= y && px < x + width && py < y + height

    /** True when [other] lies completely inside this rectangle. */
    fun contains(other: Rect): Boolean = other.x >= x && other.y >= y && other.x + other.width <= x + width && other.y + other.height <= y + height
}
