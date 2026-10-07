package io.github.marcomaes.mobiletestbench.core.apps

import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.DeviceKind
import io.github.marcomaes.mobiletestbench.core.Platform

/** The steps of "Reset state", in the order they run. */
enum class ResetStep(val label: String) {
    TERMINATE("Terminate the app"),
    CLEAR_DATA("Clear its data"),
    RESET_PERMISSIONS("Reset its permissions"),
    RELAUNCH("Relaunch it afterwards"),
}

/** Which steps the user ticked. */
data class ResetChoices(
    val terminate: Boolean = true,
    val clear: Boolean = true,
    val permissions: Boolean = true,
    val relaunch: Boolean = true,
) {
    fun wants(step: ResetStep) = when (step) {
        ResetStep.TERMINATE -> terminate
        ResetStep.CLEAR_DATA -> clear
        ResetStep.RESET_PERMISSIONS -> permissions
        ResetStep.RELAUNCH -> relaunch
    }
}

/** One planned step. When [applies] is false the step is shown with [note] and not run. */
data class PlannedStep(val step: ResetStep, val applies: Boolean, val note: String? = null)

/** Pure planning of "Reset state": which steps apply to a device and in which order. */
object ResetPlan {

    /** Ticked steps only, always in the order terminate, clear, permissions, relaunch. Unsupported ticked steps are kept with a note. */
    fun plan(device: Device, choices: ResetChoices): List<PlannedStep> =
        ResetStep.values().filter { choices.wants(it) }.map { step ->
            when {
                step == ResetStep.CLEAR_DATA && !Apps.canClearData(device) ->
                    PlannedStep(step, false, "skipped: not available on a real iPhone")
                step == ResetStep.RESET_PERMISSIONS && !canResetPermissions(device) ->
                    PlannedStep(step, false, "skipped: not available on a real iPhone")
                else -> PlannedStep(step, true)
            }
        }

    /** Android (adb) and iOS simulators (simctl, macOS only; the runner checks that) can reset permissions; a real iPhone cannot. */
    fun canResetPermissions(device: Device): Boolean = device.platform == Platform.ANDROID || device.kind == DeviceKind.SIMULATOR

    /** One line for a step row: first non-blank line of [text], trimmed. */
    fun oneLine(text: String): String = text.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "failed"
}
