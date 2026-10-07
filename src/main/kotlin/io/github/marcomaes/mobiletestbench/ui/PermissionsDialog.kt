package io.github.marcomaes.mobiletestbench.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.marcomaes.mobiletestbench.DeviceServices
import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.DeviceKind
import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.apps.AppInfo
import io.github.marcomaes.mobiletestbench.core.permissions.IosPrivacyAction
import io.github.marcomaes.mobiletestbench.core.permissions.IosPrivacyService
import io.github.marcomaes.mobiletestbench.core.permissions.PermissionResult
import io.github.marcomaes.mobiletestbench.core.permissions.PermissionSet
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel

/**
 * Permissions of one app. Android: the runtime permissions read from the device, Grant / Revoke per row and Reset all.
 * iOS simulator: the fixed list of simctl privacy services (the state cannot be read). Every action shows its command and result.
 */
class PermissionsDialog(project: Project, private val device: Device, private val app: AppInfo) : DialogWrapper(project) {
    private val android = device.platform == Platform.ANDROID
    // simctl only knows simulators; physical Android devices work through adb like emulators
    private val unsupported = device.platform == Platform.IOS && device.kind == DeviceKind.PHYSICAL
    private var set: PermissionSet? = null
    private var busy = false
    private var generation = 0

    private val services = IosPrivacyService.values().toList()
    private val note = JBLabel(" ")
    private val log = JBTextArea(6, 70).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val model = object : AbstractTableModel() {
        private val androidColumns = arrayOf("Permission", "State", "Flags")
        private val iosColumns = arrayOf("Service", "State", "Note")
        override fun getRowCount() = if (android) set?.runtime?.size ?: 0 else services.size
        override fun getColumnCount() = 3
        override fun getColumnName(column: Int) = (if (android) androidColumns else iosColumns)[column]
        override fun getValueAt(row: Int, col: Int): Any {
            if (!android) {
                val s = services[row]
                return when (col) { 0 -> s.label + " (" + s.simctl + ")"; 1 -> "Unknown"; else -> s.note ?: "" }
            }
            val p = set!!.runtime[row]
            return when (col) { 0 -> p.shortName; 1 -> if (p.granted) "Granted" else "Denied"; else -> p.flags.joinToString(" ").lowercase() }
        }
    }
    private val table = JBTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        setShowGrid(false)
        columnModel.getColumn(0).preferredWidth = JBUI.scale(300)
        columnModel.getColumn(1).preferredWidth = JBUI.scale(80)
        columnModel.getColumn(2).preferredWidth = JBUI.scale(300)
        selectionModel.addListSelectionListener { updateButtons() }
    }
    private val grant = JButton("Grant")
    private val revoke = JButton("Revoke")
    private val reset = JButton("Reset")
    private val resetAll = JButton("Reset all")
    private val refresh = JButton("Refresh")

    init {
        title = "Permissions - ${app.name} (${app.id})"
        setOKButtonText("Close")
        init()
        grant.addActionListener { act(true) }
        revoke.addActionListener { act(false) }
        reset.addActionListener { iosAction(IosPrivacyAction.RESET) }
        resetAll.addActionListener { resetAllAndroid() }
        refresh.addActionListener { load() }
        if (unsupported) {
            note.text = "Permissions can only be changed on iOS simulators and Android devices"
            listOf(grant, revoke, reset, resetAll, refresh).forEach { it.isEnabled = false }
        } else if (android) load() else {
            note.text = "<html>The current state cannot be read on iOS; simctl only sets it. Grant and revoke may terminate the app if it is running; reset did not in tests.</html>"
            updateButtons()
        }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
            add(grant); add(revoke)
            if (android) { add(resetAll); add(refresh) } else add(reset)
        }
        val top = JPanel(BorderLayout()).apply { add(note, BorderLayout.NORTH); add(buttons, BorderLayout.SOUTH) }
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            preferredSize = Dimension(JBUI.scale(760), JBUI.scale(420))
            add(top, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
            add(ScrollPaneFactory.createScrollPane(log), BorderLayout.SOUTH)
        }
    }

    private fun updateButtons() {
        if (unsupported) return
        val row = table.selectedRow
        val p = if (android && row >= 0) set?.runtime?.getOrNull(row) else null
        val has = row >= 0 && !busy
        grant.isEnabled = has && (!android || p?.granted == false)
        revoke.isEnabled = has && (!android || p?.granted == true)
        reset.isEnabled = has
        resetAll.isEnabled = !busy && set?.runtime?.isNotEmpty() == true
        refresh.isEnabled = !busy
        grant.toolTipText = null
        resetAll.toolTipText = "Revokes every runtime permission and clears its user-set and user-fixed flags (Android has no per-app system reset)"
    }

    private fun append(text: String) { log.append(text + "\n"); log.caretPosition = log.document.length }

    /** Reads the app's permissions from the device. Late results (a newer load started) are dropped. */
    private fun load(afterAction: Boolean = false) {
        val gen = ++generation
        val selectedName = set?.runtime?.getOrNull(table.selectedRow)?.name
        busy = true
        if (!afterAction) note.text = "Loading..."
        updateButtons()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { DeviceServices.androidPermissions(device).read(app.id) }
            SwingUtilities.invokeLater {
                if (gen != generation) return@invokeLater
                busy = false
                result.onSuccess {
                    set = it
                    note.text = if (it.runtime.isEmpty()) "This app requests no runtime permissions"
                    else "<html>${it.runtime.size} runtime permissions. ${it.installTimeCount} install-time permissions (e.g. INTERNET) cannot be changed. " +
                        "A permission marked user_fixed will not prompt again after a revoke until Reset all clears it.</html>"
                }.onFailure {
                    set = null
                    note.text = "Could not read permissions: ${it.message}"
                }
                model.fireTableDataChanged()
                // fireTableDataChanged clears the selection; put it back on the same permission
                val again = selectedName?.let { n -> set?.runtime?.indexOfFirst { it.name == n } } ?: -1
                if (again >= 0) table.setRowSelectionInterval(again, again)
                updateButtons()
            }
        }
    }

    /** Runs [work] off the EDT, appends each result to the log, then re-reads the device state (Android). */
    private fun runActions(work: () -> List<PermissionResult>) {
        busy = true
        updateButtons()
        ApplicationManager.getApplication().executeOnPooledThread {
            val results = runCatching(work).getOrElse { listOf(PermissionResult("action", false, it.message ?: it.javaClass.simpleName)) }
            SwingUtilities.invokeLater {
                results.forEach { append(it.text) }
                busy = false
                if (android) load(afterAction = true) else updateButtons()
            }
        }
    }

    private fun act(grantIt: Boolean) {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return
        if (!android) { iosAction(if (grantIt) IosPrivacyAction.GRANT else IosPrivacyAction.REVOKE); return }
        val perm = set?.runtime?.getOrNull(row)?.name ?: return
        runActions {
            val a = DeviceServices.androidPermissions(device)
            listOf(if (grantIt) a.grant(app.id, perm) else a.revoke(app.id, perm))
        }
    }

    private fun iosAction(action: IosPrivacyAction) {
        val service = services.getOrNull(table.selectedRow) ?: return
        val backend = DeviceServices.iosPermissions(device) ?: run { append("Not available: needs an iOS simulator on macOS"); return }
        if (Messages.showYesNoDialog(contentPanel, "${action.label} ${service.label} for ${app.name}? The app may be terminated if it is running.", "Permissions", Messages.getQuestionIcon()) != Messages.YES) return
        runActions { listOf(backend.apply(action, service, app.id)) }
    }

    private fun resetAllAndroid() {
        val perms = set?.runtime ?: return
        if (Messages.showYesNoDialog(contentPanel, "Revoke all ${perms.size} runtime permissions of ${app.name} and clear their user-set flags? The app may be terminated.", "Reset all", Messages.getWarningIcon()) != Messages.YES) return
        runActions { DeviceServices.androidPermissions(device).resetAll(app.id, perms) }
    }
}
