package io.github.marcomaes.mobiletestbench.core.project

import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.apps.ForegroundApp

/** What the panel remembers per project: the last device and the layout. Pure data, no IDE types. */
data class ProjectLayout(
    val lastDeviceId: String = "",
    val lastDeviceName: String = "",
    val inspectorOpen: Boolean = false,
    val toolsOpen: Boolean = false,
    /** Title of the selected Device Tools tab; empty means the first one. */
    val toolsTab: String = "",
    /** Share of the mirror in the mirror/inspector splitter. */
    val mirrorProportion: Float = DEFAULT_MIRROR,
    /** Share of the mirror area in the vertical splitter above Device Tools. */
    val toolsProportion: Float = DEFAULT_TOOLS,
) {
    /** Replaces unusable values (NaN, outside 0.1..0.9) by the defaults and trims the strings. */
    fun normalized(): ProjectLayout = copy(
        lastDeviceId = lastDeviceId.trim(),
        lastDeviceName = lastDeviceName.trim(),
        toolsTab = toolsTab.trim(),
        mirrorProportion = clamp(mirrorProportion, DEFAULT_MIRROR),
        toolsProportion = clamp(toolsProportion, DEFAULT_TOOLS),
    )

    /** Applies the fields that are set in [patch]; the others keep their value. */
    fun merge(patch: LayoutPatch): ProjectLayout = copy(
        lastDeviceId = patch.lastDeviceId ?: lastDeviceId,
        lastDeviceName = patch.lastDeviceName ?: lastDeviceName,
        inspectorOpen = patch.inspectorOpen ?: inspectorOpen,
        toolsOpen = patch.toolsOpen ?: toolsOpen,
        toolsTab = patch.toolsTab ?: toolsTab,
        mirrorProportion = patch.mirrorProportion ?: mirrorProportion,
        toolsProportion = patch.toolsProportion ?: toolsProportion,
    ).normalized()

    fun withDevice(device: Device): ProjectLayout = copy(lastDeviceId = device.id, lastDeviceName = device.name)

    /** The saved tab when it still exists (tabs get renamed between versions), else null. */
    fun tabIn(titles: List<String>): String? = toolsTab.takeIf { it in titles }

    companion object {
        const val DEFAULT_MIRROR = 0.5f
        const val DEFAULT_TOOLS = 0.68f
        private const val MIN = 0.1f
        private const val MAX = 0.9f

        fun clamp(value: Float, default: Float): Float =
            if (value.isNaN() || value < MIN || value > MAX) default else value
    }
}

/** A partial update of a [ProjectLayout]: null leaves a field alone. */
data class LayoutPatch(
    val lastDeviceId: String? = null,
    val lastDeviceName: String? = null,
    val inspectorOpen: Boolean? = null,
    val toolsOpen: Boolean? = null,
    val toolsTab: String? = null,
    val mirrorProportion: Float? = null,
    val toolsProportion: Float? = null,
)

/** Finds the remembered device among the discovered ones. */
object LastDevice {
    /** Same id first, else (an emulator serial changes between boots, a simulator keeps its UDID) the same name; null when [id] and [name] are blank or nothing matches. */
    fun find(devices: List<Device>, id: String, name: String): Device? {
        if (id.isBlank() && name.isBlank()) return null
        return devices.firstOrNull { id.isNotBlank() && it.id == id }
            ?: devices.firstOrNull { name.isNotBlank() && it.name == name && (it.isUsable || it.bootable) }
    }
}

/** How the live foreground app shows in the status area. */
object ForegroundText {
    const val UNKNOWN = "–"

    /** One line: app name and id; just the name when both are equal; the home screen is named as such. */
    fun label(app: ForegroundApp?): String = when {
        app == null -> UNKNOWN
        app.isHome -> "Home screen"
        app.name == app.id || app.name.isBlank() -> app.id
        else -> "${app.name} (${app.id})"
    }

    fun tooltip(app: ForegroundApp?): String = when {
        app == null -> "Foreground app: not available"
        else -> "Foreground app: ${app.name} (${app.id})" + (app.activity?.let { ", $it" } ?: "") + ". Click to copy the package id."
    }

    /** What a click copies; null when there is nothing to copy. */
    fun copyText(app: ForegroundApp?): String? = app?.id?.takeIf { it.isNotBlank() }
}
