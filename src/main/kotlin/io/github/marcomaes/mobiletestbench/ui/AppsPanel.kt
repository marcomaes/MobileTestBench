package io.github.marcomaes.mobiletestbench.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.openapi.ui.Messages
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.marcomaes.mobiletestbench.DeviceServices
import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.apps.AppInfo
import io.github.marcomaes.mobiletestbench.core.apps.Apps
import io.github.marcomaes.mobiletestbench.core.apps.ForegroundApp
import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.settings.MobileTestBenchSettings
import com.intellij.ui.components.JBCheckBox
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.TransferHandler
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/** Installed apps of the connected device with launch, terminate, clear data, uninstall and install (button or drag and drop). */
class AppsPanel(private val project: Project, private val showLog: (String) -> Unit) : JPanel(BorderLayout()), DeviceToolTab {

    override val title = "Apps"
    override val component: JComponent get() = this
    override fun deviceChanged(device: Device?) {
        this.device = device
        delayed?.stop()
        generation++ // drops the result of a refresh still running for the previous device
        apps = emptyList()
        userPackages = null
        userPackagesFailed = false
        loading = false
        foreground = null
        applyFilter()
        updateForegroundLabel()
        if (device == null) setStatus("Not connected") else if (visible) refresh()
        updateButtons()
    }
    override fun shown() {
        visible = true
        if (device != null && apps.isEmpty()) refresh()
    }
    override fun hidden() { visible = false }
    override fun dispose() { generation++; delayed?.stop() }

    private var device: Device? = null
    private var visible = false
    private var apps: List<AppInfo> = emptyList()
    private var shownApps: List<AppInfo> = emptyList()
    private var foreground: ForegroundApp? = null
    private var userPackages: Set<String>? = null
    private var userPackagesFailed = false
    private var loading = false
    private val settings = MobileTestBenchSettings.getInstance().state
    private var generation = 0
    private var delayed: Timer? = null

    private val filter = JBTextField(16).apply { emptyText.text = "Filter by name or id" }
    private val hideSystem = JBCheckBox("Hide system apps", settings.hideSystemApps).apply {
        toolTipText = "Android: apps not in pm list packages -3. iOS: bundle ids starting with com.apple."
    }
    private val foregroundLabel = JBLabel(" ")
    private val status = JBLabel(" ").apply { border = JBUI.Borders.empty(2, 8) }
    private val model = AppsModel()
    private val table = JBTable(model)

    private val launch = JButton("Launch")
    private val launchOptions = JButton("Launch with options...")
    private val terminate = JButton("Terminate")
    private val clear = JButton("Clear data")
    private val resetState = JButton("Reset state...")
    private val uninstall = JButton("Uninstall")
    private val permissions = JButton("Permissions...")
    private val copyId = JButton("Copy id")
    private val showLogButton = JButton("Show log of this app")
    private val install = JButton("Install...")

    init {
        table.setShowGrid(false)
        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION)
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        table.columnModel.getColumn(0).preferredWidth = JBUI.scale(200)
        table.columnModel.getColumn(1).preferredWidth = JBUI.scale(320)
        table.columnModel.getColumn(2).preferredWidth = JBUI.scale(200)
        table.setDefaultRenderer(String::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int): Component {
                val c = super.getTableCellRendererComponent(t, value, sel, focus, row, col)
                val isForeground = shownApps.getOrNull(row)?.id == this@AppsPanel.foreground?.id
                font = if (isForeground) font.deriveFont(Font.BOLD) else font.deriveFont(Font.PLAIN)
                return c
            }
        })
        table.selectionModel.addListSelectionListener { updateButtons() }
        table.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) = menu(e)
            override fun mouseReleased(e: java.awt.event.MouseEvent) = menu(e)
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2 && selected() != null) launchSelected()
            }

            private fun menu(e: java.awt.event.MouseEvent) {
                if (!e.isPopupTrigger) return
                val row = table.rowAtPoint(e.point)
                if (row >= 0) table.setRowSelectionInterval(row, row)
                val dev = device ?: return
                if (selected() == null) return
                JBPopupMenu().apply {
                    add(JMenuItem("Launch").apply { addActionListener { launchSelected() } })
                    add(JMenuItem("Launch with options...").apply { addActionListener { launchWithOptions() } })
                    add(JMenuItem("Terminate").apply { addActionListener { terminateSelected() } })
                    if (Apps.canClearData(dev)) add(JMenuItem("Clear data...").apply { addActionListener { clearSelected() } })
                    add(JMenuItem("Reset state...").apply { addActionListener { resetStateSelected() } })
                    add(JMenuItem("Uninstall...").apply { addActionListener { uninstallSelected() } })
                    add(JMenuItem("Permissions...").apply { addActionListener { permissionsSelected() } })
                    add(JMenuItem("Copy id").apply { addActionListener { copySelected() } })
                    add(JMenuItem("Show log of this app").apply { addActionListener { showLogSelected() } })
                }.show(table, e.x, e.y)
            }
        })

        filter.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = applyFilter()
        })
        hideSystem.addActionListener { settings.hideSystemApps = hideSystem.isSelected; applyFilter(); if (!loading) setStatus(countText()) }
        launch.addActionListener { launchSelected() }
        launchOptions.addActionListener { launchWithOptions() }
        terminate.addActionListener { terminateSelected() }
        clear.addActionListener { clearSelected() }
        resetState.addActionListener { resetStateSelected() }
        uninstall.addActionListener { uninstallSelected() }
        permissions.addActionListener { permissionsSelected() }
        copyId.addActionListener { copySelected() }
        showLogButton.addActionListener { showLogSelected() }
        install.addActionListener { chooseAndInstall() }

        val top = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
            add(JButton("Refresh").apply { addActionListener { refresh() } })
            add(filter)
            add(hideSystem)
            add(foregroundLabel)
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
            listOf(launch, launchOptions, terminate, clear, resetState, uninstall, permissions, copyId, showLogButton, install).forEach { add(it) }
        }
        val north = JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(buttons, BorderLayout.SOUTH)
        }
        add(north, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)

        // Drop an .apk, .zip or .ipa anywhere on the tab to install it.
        val handler = object : TransferHandler() {
            override fun canImport(support: TransferSupport) = support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                @Suppress("UNCHECKED_CAST")
                val files = runCatching { support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File> }.getOrNull() ?: return false
                val file = files.firstOrNull() ?: return false
                installFile(file)
                return true
            }
        }
        this.transferHandler = handler
        table.transferHandler = handler
        updateButtons()
        setStatus("Not connected")
    }

    private fun selected(): AppInfo? = table.selectedRow.takeIf { it >= 0 }?.let { shownApps.getOrNull(it) }

    private fun setStatus(text: String) { status.text = text.ifBlank { " " } }

    private fun updateButtons() {
        val dev = device
        val has = dev != null && selected() != null
        launch.isEnabled = has
        launchOptions.isEnabled = has
        terminate.isEnabled = has
        clear.isEnabled = has && Apps.canClearData(dev!!)
        clear.toolTipText = if (dev != null && !Apps.canClearData(dev)) "Clearing data is not available on a real iPhone" else "Deletes the app's data and kills it; on Android also revokes its runtime permissions"
        resetState.isEnabled = has && dev!!.isUsable
        uninstall.isEnabled = has
        permissions.isEnabled = has && dev!!.isUsable
        copyId.isEnabled = has
        showLogButton.isEnabled = has
        install.isEnabled = dev != null
    }

    private fun updateForegroundLabel() {
        val f = foreground
        foregroundLabel.text = when {
            f == null -> " "
            f.isHome -> "Foreground: Home screen"
            else -> "Foreground: ${f.name} (${f.id})" + (f.activity?.let { " / $it" } ?: "")
        }
    }

    private fun countText(): String {
        val dev = device ?: return "${apps.size} apps"
        if (!hideSystem.isSelected) return "${apps.size} apps"
        if (dev.platform == Platform.ANDROID && userPackagesFailed) return "${apps.size} apps, could not tell system apps apart"
        return "${apps.size} apps, ${apps.size - Apps.hideSystem(apps, dev, userPackages, true).size} system hidden"
    }

    private fun applyFilter() {
        val keep = selected()?.id
        val dev = device
        val candidates = if (dev == null) apps else Apps.hideSystem(apps, dev, userPackages, hideSystem.isSelected, keep)
        shownApps = Apps.filter(candidates, filter.text)
        model.fireTableDataChanged()
        keep?.let { id -> shownApps.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { table.setRowSelectionInterval(it, it) } }
        updateButtons()
    }

    /** Loads the app list and the foreground app. A [note] (the result of an action) stays visible in the status line. */
    private fun refresh(note: String? = null) {
        val dev = device ?: return
        val gen = ++generation
        loading = true
        setStatus(if (note == null) "Loading apps..." else "$note (loading apps...)")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { Apps.parseList(DeviceServices.mobilecliData(dev, "apps", "list")) }
            val fg = runCatching { Apps.parseForeground(DeviceServices.mobilecliData(dev, "apps", "foreground")) }.getOrNull()
            // Only Android needs a second source; a failure leaves every app visible.
            val user = if (dev.platform == Platform.ANDROID) runCatching { DeviceServices.androidPackages(dev).userPackages() } else null
            SwingUtilities.invokeLater {
                if (gen != generation) return@invokeLater
                loading = false
                result.onSuccess {
                    val keep = selected()?.id
                    apps = it
                    userPackages = user?.getOrNull()
                    userPackagesFailed = user != null && user.isFailure
                    foreground = fg
                    applyFilter()
                    updateForegroundLabel()
                    keep?.let { id -> shownApps.indexOfFirst { a -> a.id == id }.takeIf { i -> i >= 0 }?.let { i -> table.setRowSelectionInterval(i, i) } }
                    val count = countText()
                    setStatus(if (note == null) count else "$note ($count)")
                }.onFailure { setStatus(if (note == null) "Could not list apps: ${message(it)}" else "$note (could not reload apps: ${message(it)})") }
            }
        }
    }

    /** Re-reads only the foreground app. */
    private fun refreshForeground() {
        val dev = device ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val fg = runCatching { Apps.parseForeground(DeviceServices.mobilecliData(dev, "apps", "foreground")) }.getOrNull()
            SwingUtilities.invokeLater {
                if (dev !== device) return@invokeLater
                foreground = fg
                updateForegroundLabel()
                table.repaint()
            }
        }
    }

    private fun message(t: Throwable) = Apps.friendlyError(t.message ?: t.javaClass.simpleName)

    /** Runs one mobilecli action off the EDT, reports the result, then refreshes the foreground (and the list when [reloadList]). */
    private fun run(label: String, reloadList: Boolean, vararg args: String, timeout: java.time.Duration = java.time.Duration.ofSeconds(20)) {
        val dev = device ?: return
        setStatus("$label...")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { DeviceServices.mobilecliCall(dev, *args, timeout = timeout) }
            SwingUtilities.invokeLater {
                if (dev !== device) return@invokeLater
                result.onSuccess {
                    // The list is reloaded only after a success; the action's message stays in the status line.
                    if (reloadList) refresh("$label: done") else setStatus("$label: done")
                }.onFailure { setStatus("$label failed: ${message(it)}") }
                // Launch returns before the app is up, so the foreground is read after a short delay.
                delayed?.stop()
                delayed = Timer(1200) { refreshForeground() }.apply { isRepeats = false; start() }
            }
        }
    }

    /** Launches at once (double-click, Launch button). */
    private fun launchSelected() {
        val dev = device ?: return
        val app = selected() ?: return
        run("Launch ${app.name}", false, *Apps.launchArgs(dev, app.id).toTypedArray())
    }

    /** Asks for the optional locale (and, on Android, activity) first. */
    private fun launchWithOptions() {
        val dev = device ?: return
        val app = selected() ?: return
        val dialog = LaunchOptionsDialog(project, app, Apps.supportsActivity(dev))
        if (!dialog.showAndGet()) return
        run("Launch ${app.name}", false, *Apps.launchArgs(dev, app.id, dialog.locale, dialog.activity).toTypedArray())
    }

    private fun terminateSelected() {
        val app = selected() ?: return
        run("Terminate ${app.name}", false, *Apps.terminateArgs(app.id).toTypedArray())
    }

    private fun clearSelected() {
        val app = selected() ?: return
        if (Messages.showYesNoDialog(project, "Delete all data of ${app.name} (${app.id})? This cannot be undone. The app is killed, and on Android its runtime permissions are revoked too.", "Clear Data", Messages.getWarningIcon()) != Messages.YES) return
        run("Clear data of ${app.name}", false, *Apps.clearArgs(app.id).toTypedArray())
    }

    private fun resetStateSelected() {
        val dev = device ?: return
        val app = selected() ?: return
        ResetStateDialog(project, dev, app).show()
        refreshForeground()
    }

    private fun uninstallSelected() {
        val app = selected() ?: return
        if (Messages.showYesNoDialog(project, "Uninstall ${app.name} (${app.id}) from the device?", "Uninstall", Messages.getWarningIcon()) != Messages.YES) return
        run("Uninstall ${app.name}", true, *Apps.uninstallArgs(app.id).toTypedArray())
    }

    private fun permissionsSelected() {
        val dev = device ?: return
        val app = selected() ?: return
        PermissionsDialog(project, dev, app).show()
    }

    private fun copySelected() {
        val app = selected() ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(app.id))
        setStatus("Copied ${app.id}")
    }

    private fun showLogSelected() {
        val app = selected() ?: return
        showLog(app.id)
    }

    private fun chooseAndInstall() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withExtensionFilter("Apps (.apk, .zip, .ipa)", *Apps.INSTALL_EXTENSIONS.toTypedArray())
            .withTitle("Install App")
        FileChooser.chooseFile(descriptor, project, null)?.let { installFile(File(it.path)) }
    }

    private fun installFile(file: File) {
        val dev = device ?: run { setStatus("Connect a device first"); return }
        Apps.installProblem(dev, file.path)?.let { setStatus(it); return }
        run("Install ${file.name}", true, *Apps.installArgs(file.path).toTypedArray(), timeout = java.time.Duration.ofMinutes(3))
    }

    private inner class AppsModel : AbstractTableModel() {
        private val columns = arrayOf("Name", "Id", "Version")
        override fun getRowCount() = shownApps.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val a = shownApps[rowIndex]
            return when (columnIndex) {
                0 -> a.name
                1 -> a.id
                else -> a.version
            }
        }
    }
}

/** Asks for the optional locale (and, on Android, activity) of a launch. */
private class LaunchOptionsDialog(project: Project, private val app: AppInfo, private val withActivity: Boolean) : com.intellij.openapi.ui.DialogWrapper(project) {
    private val localeField = JBTextField(24).apply { emptyText.text = "e.g. fr-FR,en-GB (optional)" }
    private val activityField = JBTextField(24).apply { emptyText.text = "e.g. .MainActivity (optional)" }
    val locale: String get() = localeField.text
    val activity: String get() = activityField.text

    init {
        title = "Launch ${app.name}"
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(java.awt.GridBagLayout()).apply {
        val c = java.awt.GridBagConstraints().apply { insets = JBUI.insets(2); anchor = java.awt.GridBagConstraints.WEST }
        add(JBLabel("Locale:"), c)
        add(localeField, c.apply { gridx = 1 })
        if (withActivity) {
            add(JBLabel("Activity:"), c.apply { gridx = 0; gridy = 1 })
            add(activityField, c.apply { gridx = 1 })
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = localeField
}
