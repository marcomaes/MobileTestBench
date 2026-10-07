package io.github.marcomaes.mobiletestbench.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.github.marcomaes.mobiletestbench.DeviceServices
import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.apps.AppInfo
import io.github.marcomaes.mobiletestbench.core.apps.Apps
import io.github.marcomaes.mobiletestbench.core.apps.PlannedStep
import io.github.marcomaes.mobiletestbench.core.apps.ResetChoices
import io.github.marcomaes.mobiletestbench.core.apps.ResetPlan
import io.github.marcomaes.mobiletestbench.core.apps.ResetStep
import io.github.marcomaes.mobiletestbench.core.permissions.IosPrivacyAction
import io.github.marcomaes.mobiletestbench.core.permissions.IosPrivacyService
import io.github.marcomaes.mobiletestbench.settings.MobileTestBenchSettings
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Resets one app to a repeatable starting point: terminate, clear data, reset permissions, relaunch (each optional, always in that order).
 * The steps run on one pooled thread, each row shows its result as it goes, and the first failure stops the run.
 */
class ResetStateDialog(project: Project, private val device: Device, private val app: AppInfo) : DialogWrapper(project) {

    private val settings = MobileTestBenchSettings.getInstance().state
    private val boxes = ResetStep.values().associateWith { JBCheckBox(it.label, initial(it)) }
    private val rows = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private var started = false
    private var finished = false
    @Volatile private var closed = false

    private fun initial(step: ResetStep) = when (step) {
        ResetStep.TERMINATE -> settings.resetTerminate
        ResetStep.CLEAR_DATA -> settings.resetClear
        ResetStep.RESET_PERMISSIONS -> settings.resetPermissions
        ResetStep.RELAUNCH -> settings.resetRelaunch
    }

    init {
        title = "Reset state of ${app.name}"
        setOKButtonText("Reset")
        if (!Apps.canClearData(device)) boxes.getValue(ResetStep.CLEAR_DATA).apply { isSelected = false; isEnabled = false; toolTipText = "Not available on a real iPhone" }
        if (!ResetPlan.canResetPermissions(device)) boxes.getValue(ResetStep.RESET_PERMISSIONS).apply { isSelected = false; isEnabled = false; toolTipText = "Not available on a real iPhone" }
        boxes.values.forEach { it.addActionListener { showPlan(pending = true) } }
        showPlan(pending = true)
        init()
    }

    private fun choices() = ResetChoices(
        boxes.getValue(ResetStep.TERMINATE).isSelected, boxes.getValue(ResetStep.CLEAR_DATA).isSelected,
        boxes.getValue(ResetStep.RESET_PERMISSIONS).isSelected, boxes.getValue(ResetStep.RELAUNCH).isSelected,
    )

    private fun showPlan(pending: Boolean) {
        rows.removeAll()
        val plan = ResetPlan.plan(device, choices())
        if (plan.isEmpty()) rows.add(JBLabel("Nothing is ticked"))
        plan.forEach { rows.add(JBLabel(describe(it, if (it.applies) "pending" else it.note ?: "skipped"))) }
        rows.revalidate(); rows.repaint()
        isOKActionEnabled = !started && plan.isNotEmpty()
    }

    private fun describe(p: PlannedStep, state: String) = "${p.step.label}: $state"

    override fun createCenterPanel(): JComponent = JPanel(GridBagLayout()).apply {
        val c = GridBagConstraints().apply { gridx = 0; anchor = GridBagConstraints.WEST; insets = JBUI.insets(2) }
        add(JBLabel("<html>${app.name} (${app.id}) on ${device.name}</html>"), c)
        boxes.values.forEach { add(it, c) }
        if (device.platform == Platform.ANDROID) {
            add(JBLabel("<html><small>On Android, clearing data also kills the app and revokes its runtime permissions.</small></html>"), c)
        }
        add(JBLabel("Steps"), c.apply { insets = JBUI.insets(10, 2, 2, 2) })
        add(rows, c.apply { insets = JBUI.insets(2); fill = GridBagConstraints.HORIZONTAL; weightx = 1.0 })
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)

    override fun doOKAction() {
        if (finished) { super.doOKAction(); return }
        if (started) return
        val plan = ResetPlan.plan(device, choices())
        if (plan.isEmpty()) return
        started = true
        isOKActionEnabled = false
        settings.resetTerminate = boxes.getValue(ResetStep.TERMINATE).isSelected
        boxes.getValue(ResetStep.CLEAR_DATA).let { if (it.isEnabled) settings.resetClear = it.isSelected }
        boxes.getValue(ResetStep.RESET_PERMISSIONS).let { if (it.isEnabled) settings.resetPermissions = it.isSelected }
        settings.resetRelaunch = boxes.getValue(ResetStep.RELAUNCH).isSelected
        boxes.values.forEach { it.isEnabled = false }
        rows.removeAll()
        val labels = plan.map { JBLabel(describe(it, if (it.applies) "pending" else it.note ?: "skipped")).also { l -> rows.add(l) } }
        rows.revalidate()
        ApplicationManager.getApplication().executeOnPooledThread { runSteps(plan, labels) }
    }

    override fun dispose() { closed = true; super.dispose() }

    private fun update(label: JBLabel, text: String) = SwingUtilities.invokeLater { if (!closed) label.text = text }

    private fun runSteps(plan: List<PlannedStep>, labels: List<JBLabel>) {
        var failed = false
        plan.forEachIndexed { i, p ->
            val label = labels[i]
            if (closed) return@forEachIndexed
            if (failed) { update(label, describe(p, "not run")); return@forEachIndexed }
            if (!p.applies) return@forEachIndexed
            update(label, describe(p, "running..."))
            val started = System.currentTimeMillis()
            val result = runCatching { execute(p.step) }
            val secs = "%.1fs".format((System.currentTimeMillis() - started) / 1000.0)
            result.onSuccess { note -> update(label, describe(p, if (note.isEmpty()) "done ($secs)" else "$note ($secs)")) }
                .onFailure {
                    failed = true
                    update(label, describe(p, "FAILED: " + Apps.friendlyError(ResetPlan.oneLine(it.message ?: it.javaClass.simpleName))))
                }
        }
        SwingUtilities.invokeLater {
            if (closed) return@invokeLater
            finished = true
            setOKButtonText("Close")
            isOKActionEnabled = true
        }
    }

    /** Runs one step (blocking, off the EDT). Returns an optional note for the row; throws with the exact error on failure. */
    private fun execute(step: ResetStep): String = when (step) {
        ResetStep.TERMINATE -> { DeviceServices.mobilecliCall(device, *Apps.terminateArgs(app.id).toTypedArray()); "" }
        ResetStep.CLEAR_DATA -> { DeviceServices.mobilecliCall(device, *Apps.clearArgs(app.id).toTypedArray()); "" }
        ResetStep.RELAUNCH -> { DeviceServices.mobilecliCall(device, *Apps.launchArgs(device, app.id).toTypedArray()); "launched" }
        ResetStep.RESET_PERMISSIONS ->
            if (device.platform == Platform.ANDROID) {
                val perms = DeviceServices.androidPermissions(device).read(app.id).runtime
                if (perms.isEmpty()) "no runtime permissions"
                else {
                    DeviceServices.androidPermissions(device).resetAll(app.id, perms).firstOrNull { !it.ok }?.let { throw IllegalStateException(it.text) }
                    ""
                }
            } else {
                val backend = DeviceServices.iosPermissions(device) ?: throw IllegalStateException("needs an iOS simulator on macOS")
                backend.apply(IosPrivacyAction.RESET, IosPrivacyService.ALL, app.id).let { if (!it.ok) throw IllegalStateException(it.text) }
                ""
            }
    }
}
