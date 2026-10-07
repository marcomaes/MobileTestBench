package io.github.marcomaes.mobiletestbench.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.marcomaes.mobiletestbench.DeviceServices
import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.apps.Apps
import io.github.marcomaes.mobiletestbench.core.files.EntryKind
import io.github.marcomaes.mobiletestbench.core.files.FileBackend
import io.github.marcomaes.mobiletestbench.core.files.FileEntry
import io.github.marcomaes.mobiletestbench.core.files.FileFormat
import io.github.marcomaes.mobiletestbench.core.files.FilePaths
import io.github.marcomaes.mobiletestbench.core.files.FilePreview
import io.github.marcomaes.mobiletestbench.core.files.FileSort
import io.github.marcomaes.mobiletestbench.core.files.Preview
import io.github.marcomaes.mobiletestbench.core.files.SortKey
import io.github.marcomaes.mobiletestbench.settings.MobileTestBenchSettings
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/**
 * File browser and app data viewer: device storage or an app's data folder, with download, upload, new folder, delete and a preview of text,
 * JSON, XML, SharedPreferences and property lists. Pure logic is in `core/files`. Every blocking call runs on a pooled thread; [gen] (device
 * or location changed), [listGen] and [previewGen] drop results that are no longer wanted.
 */
class FilesPanel(private val project: Project) : JPanel(BorderLayout()), DeviceToolTab {

    override val title = "Files"
    override val component: JComponent get() = this

    private var device: Device? = null
    private var backend: FileBackend? = null
    private var cwd = ""
    private var entries: List<FileEntry> = emptyList()
    private var sortKey = SortKey.NAME
    private var sortAscending = true
    private var visible = false
    private var busy = 0
    private var warnedAboutAppWrites = false
    private var appsLoaded = false

    private var gen = 0
    private var listGen = 0
    private var previewGen = 0

    private val locationCombo = JComboBox(arrayOf(DEVICE_STORAGE, APP_DATA))
    private val appCombo = JComboBox<String>().apply { isEditable = true; toolTipText = "Package name or bundle id of the app (list from the device; Enter opens it)" }
    private val foregroundButton = JButton("Foreground app").apply { toolTipText = "Use the app that is in front on the device" }
    private val appStatus = JBLabel(" ").apply { foreground = JBColor.GRAY }
    private val up = JButton(AllIcons.Actions.Back).apply { toolTipText = "Up" }
    private val refresh = JButton(AllIcons.Actions.Refresh).apply { toolTipText = "Refresh" }
    private val pathField = JBTextField()
    private val copyPath = JButton("Copy path")

    private val tableModel = object : AbstractTableModel() {
        override fun getRowCount() = entries.size
        override fun getColumnCount() = 4
        override fun getColumnName(c: Int) = listOf("Name", "Type", "Size", "Modified")[c] + if (SortKey.values()[c] == sortKey) (if (sortAscending) "  ▲" else "  ▼") else ""
        override fun getValueAt(r: Int, c: Int): Any = entries[r].let {
            when (c) {
                0 -> it.name
                1 -> FileFormat.typeLabel(it)
                2 -> if (it.isDir) "" else FileFormat.size(it.size)
                else -> FileFormat.modified(it.modifiedMillis)
            }
        }
    }
    private val table = JBTable(tableModel).apply {
        setShowGrid(false)
        selectionModel.selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
        columnModel.getColumn(0).preferredWidth = 260
        columnModel.getColumn(0).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable?, v: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int): Component {
                val c = super.getTableCellRendererComponent(t, v, sel, focus, row, col)
                entries.getOrNull(row)?.let { icon = if (it.isDir) AllIcons.Nodes.Folder else AllIcons.FileTypes.Any_type }
                return c
            }
        }
        columnModel.getColumn(2).cellRenderer = DefaultTableCellRenderer().apply { horizontalAlignment = SwingConstants.RIGHT }
    }

    // preview
    private val previewTitle = JBLabel(" ").apply { border = JBUI.Borders.empty(2, 6) }
    private val previewCards = CardLayout()
    private val previewHolder = JPanel(previewCards)
    private val previewText = JBTextArea().apply { isEditable = false; font = Font(Font.MONOSPACED, Font.PLAIN, font.size) }
    private val previewTableModel = DefaultTableModel()
    private val previewTable = JBTable(previewTableModel)
    private val previewMessage = JBLabel(" ").apply { verticalAlignment = SwingConstants.TOP; border = JBUI.Borders.empty(6) }
    private val sqliteButton = JButton("Pull and show in the file manager").apply { isVisible = false }

    private val download = JButton("Download")
    private val upload = JButton("Upload")
    private val newFolder = JButton("New folder")
    private val delete = JButton("Delete")
    private val progress = JProgressBar().apply { isIndeterminate = true; isVisible = false; preferredSize = java.awt.Dimension(80, 12) }
    private val status = JBLabel(" ").apply { border = JBUI.Borders.empty(2, 6) }

    init {
        val top = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { add(JBLabel("Location")); add(locationCombo); add(JBLabel("App")); add(appCombo); add(foregroundButton); add(appStatus) })
            add(JPanel(BorderLayout(6, 0)).apply {
                border = JBUI.Borders.empty(2, 6)
                add(JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply { add(up); add(refresh) }, BorderLayout.WEST)
                add(pathField, BorderLayout.CENTER)
                add(copyPath, BorderLayout.EAST)
            })
        }
        appCombo.preferredSize = java.awt.Dimension(300, appCombo.preferredSize.height)

        previewHolder.add(ScrollPaneFactory.createScrollPane(previewText), "text")
        previewHolder.add(ScrollPaneFactory.createScrollPane(previewTable), "table")
        previewHolder.add(JPanel(BorderLayout()).apply { add(previewMessage, BorderLayout.NORTH); add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(sqliteButton) }, BorderLayout.CENTER) }, "message")
        val previewPane = JPanel(BorderLayout()).apply { add(previewTitle, BorderLayout.NORTH); add(previewHolder, BorderLayout.CENTER) }
        add(top, BorderLayout.NORTH)
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, ScrollPaneFactory.createScrollPane(table), previewPane).apply { resizeWeight = 0.55 }, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { add(download); add(upload); add(newFolder); add(delete); add(progress) }, BorderLayout.WEST)
            add(status, BorderLayout.CENTER)
        }, BorderLayout.SOUTH)

        wire()
        showMessage("Select a file to preview it.")
        updateControls()
        setStatus("Not connected", false)
    }

    private fun wire() {
        locationCombo.addActionListener { if (!updatingCombos) { updateControls(); appsChanged() } }
        // An editable combo fires this for a pick from the list and for Enter in the field.
        appCombo.addActionListener { if (!updatingCombos && locationCombo.selectedItem == APP_DATA) appsChanged() }
        foregroundButton.addActionListener { useForegroundApp(openAfter = true) }
        up.addActionListener { backend?.let { b -> FilePaths.up(b.root, cwd)?.let(::navigate) } }
        refresh.addActionListener { if (backend == null) connect() else navigate(cwd) }
        pathField.addActionListener { navigate(FilePaths.normalize(pathField.text.trim().ifEmpty { cwd })) }
        copyPath.addActionListener { copyCurrentPath() }
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) { selectionChanged() } }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) selected()?.let(::open) }
            override fun mousePressed(e: MouseEvent) = popup(e)
            override fun mouseReleased(e: MouseEvent) = popup(e)
            private fun popup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val row = table.rowAtPoint(e.point)
                if (row >= 0) table.setRowSelectionInterval(row, row)
                menu().show(table, e.x, e.y)
            }
        })
        table.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) { if (e.keyCode == KeyEvent.VK_ENTER) { selected()?.let(::open); e.consume() } }
        })
        table.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val col = table.columnAtPoint(e.point).takeIf { it in 0..3 } ?: return
                val key = SortKey.values()[col]
                if (key == sortKey) sortAscending = !sortAscending else { sortKey = key; sortAscending = true }
                val keep = selected()?.path
                entries = FileSort.sort(entries, sortKey, sortAscending)
                tableModel.fireTableDataChanged()
                for (i in 0..3) table.columnModel.getColumn(i).headerValue = tableModel.getColumnName(i)
                table.tableHeader.repaint()
                keep?.let { p -> entries.indexOfFirst { it.path == p }.takeIf { it >= 0 }?.let { table.setRowSelectionInterval(it, it) } }
            }
        })
        download.addActionListener { download() }
        upload.addActionListener { upload() }
        newFolder.addActionListener { newFolder() }
        delete.addActionListener { delete() }
        sqliteButton.addActionListener { pullDatabase() }
    }

    private var updatingCombos = false

    private fun menu() = JBPopupMenu().apply {
        val e = selected()
        fun item(text: String, enabled: Boolean, run: () -> Unit) = add(JMenuItem(text).apply { isEnabled = enabled; addActionListener { run() } })
        item("Open", e != null) { e?.let(::open) }
        item("Download", download.isEnabled) { download() }
        item("Upload here", upload.isEnabled) { upload() }
        item("New folder", newFolder.isEnabled) { newFolder() }
        item("Delete", delete.isEnabled) { delete() }
        addSeparator()
        item("Copy path", backend != null) { copyCurrentPath() }
    }

    // lifecycle

    override fun deviceChanged(device: Device?) {
        this.device = device
        gen++; listGen++; previewGen++
        connecting = false
        backend = null
        appsLoaded = false
        entries = emptyList()
        cwd = ""
        warnedAboutAppWrites = false
        tableModel.fireTableDataChanged()
        pathField.text = ""
        appStatus.text = " "
        setCombo(emptyList())
        // The app id and location belong to the previous device: start the new one on its device storage.
        updatingCombos = true
        appCombo.selectedItem = ""
        locationCombo.selectedIndex = 0
        updatingCombos = false
        showMessage("Select a file to preview it.")
        updateControls()
        setStatus(
            when {
                device == null -> "Not connected"
                device.platform == Platform.IOS && device.kind != io.github.marcomaes.mobiletestbench.core.DeviceKind.SIMULATOR -> "Files supports Android devices and iOS simulators, not physical iPhones"
                else -> " "
            },
            false,
        )
        if (visible) activate()
    }

    override fun shown() { visible = true; activate() }
    override fun hidden() { visible = false }
    override fun dispose() { gen++; listGen++; previewGen++ }

    private fun supported(d: Device?) = d != null && (d.platform == Platform.ANDROID || d.kind == io.github.marcomaes.mobiletestbench.core.DeviceKind.SIMULATOR)

    /** Opens the browser on first show for a device, and loads the app list for the chooser. */
    private fun activate() {
        val d = device ?: return
        if (!supported(d)) return
        if (!appsLoaded) loadApps()
        if (backend == null && !connecting) connect()
    }

    private var connecting = false

    private fun setCombo(ids: List<String>) {
        updatingCombos = true
        val typed = (appCombo.editor.item as? String).orEmpty()
        appCombo.model = DefaultComboBoxModel(ids.toTypedArray())
        appCombo.selectedItem = typed
        updatingCombos = false
    }

    private fun loadApps() {
        val dev = device ?: return
        appsLoaded = true
        val my = gen
        ApplicationManager.getApplication().executeOnPooledThread {
            val ids = runCatching { Apps.parseList(DeviceServices.mobilecliData(dev, "apps", "list")).map { it.id } }.getOrDefault(emptyList())
            SwingUtilities.invokeLater { if (my == gen) setCombo(ids) }
        }
    }

    private fun appId(): String? =
        if (locationCombo.selectedItem == APP_DATA) (appCombo.editor.item as? String)?.trim()?.takeIf { it.isNotEmpty() } else null

    /** The location or the app changed: reopen. For App data without an app, the foreground app is the default. */
    private fun appsChanged() {
        if (device == null) return
        if (locationCombo.selectedItem == APP_DATA && appId() == null) { useForegroundApp(openAfter = true); return }
        connect()
    }

    private fun useForegroundApp(openAfter: Boolean) {
        val dev = device ?: return
        val my = gen
        setStatus("Reading the foreground app...", false)
        ApplicationManager.getApplication().executeOnPooledThread {
            val fg = runCatching { Apps.parseForeground(DeviceServices.mobilecliData(dev, "apps", "foreground")) }
            SwingUtilities.invokeLater {
                if (my != gen) return@invokeLater
                val app = fg.getOrNull()
                when {
                    app == null || app.isHome -> setStatus(fg.exceptionOrNull()?.message ?: "No app is in the foreground (home screen); type a package or bundle id", true)
                    else -> {
                        updatingCombos = true
                        locationCombo.selectedItem = APP_DATA
                        appCombo.selectedItem = app.id
                        updatingCombos = false
                        updateControls()
                        if (openAfter) connect() else setStatus("Foreground app: ${app.id}", false)
                    }
                }
            }
        }
    }

    // browsing

    private fun connect() {
        val dev = device ?: return
        if (!supported(dev)) return
        val id = appId()
        if (locationCombo.selectedItem == APP_DATA && id == null) { setStatus("Enter a package name or bundle id, or use Foreground app", true); return }
        if (id != null && !Regex("[A-Za-z0-9._-]+").matches(id)) { setStatus("'$id' is not a package name or bundle id", true); return }
        val my = ++gen
        listGen++; previewGen++
        backend = null
        connecting = true
        warnedAboutAppWrites = false
        entries = emptyList()
        tableModel.fireTableDataChanged()
        showMessage("Select a file to preview it.")
        updateControls()
        setStatus("Opening ${id ?: "device storage"}...", false)
        busy(+1)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val b = DeviceServices.files(dev, id)
                b to b.list(b.home)
            }
            SwingUtilities.invokeLater {
                busy(-1)
                if (my != gen) return@invokeLater
                connecting = false
                result.onSuccess { (b, list) ->
                    backend = b
                    show(b.home, list)
                    appStatus.text = if (id != null) "Data of $id" else " "
                    setStatus("${list.size} items", false)
                }.onFailure {
                    appStatus.text = " "
                    setStatus(it.message ?: it.javaClass.simpleName, true)
                }
                updateControls()
            }
        }
    }

    private fun show(path: String, list: List<FileEntry>) {
        cwd = path
        entries = FileSort.sort(list, sortKey, sortAscending)
        tableModel.fireTableDataChanged()
        pathField.text = path
        showMessage("Select a file to preview it.")
        previewTitle.text = " "
        updateControls()
    }

    private fun navigate(path: String) {
        val b = backend ?: return
        val my = gen
        val lg = ++listGen
        previewGen++
        setStatus("Loading $path...", false)
        busy(+1)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { b.list(path) }
            SwingUtilities.invokeLater {
                busy(-1)
                if (my != gen || lg != listGen) return@invokeLater
                result.onSuccess { show(path, it); setStatus("${it.size} items", false) }
                    .onFailure { pathField.text = cwd; setStatus(it.message ?: it.javaClass.simpleName, true) }
            }
        }
    }

    private fun selected(): FileEntry? = table.selectedRow.takeIf { it >= 0 }?.let { entries.getOrNull(it) }

    /** Double click or Enter: folders open; a link or (on iOS, where links look like files) any file is probed first. */
    private fun open(e: FileEntry) {
        val b = backend ?: return
        if (e.isDir) { navigate(e.path); return }
        if (!e.isLink && device?.platform == Platform.ANDROID) return // a regular file: already previewed
        val my = gen
        busy(+1)
        ApplicationManager.getApplication().executeOnPooledThread {
            val kind = runCatching { b.kindOf(e.path) }
            SwingUtilities.invokeLater {
                busy(-1)
                if (my != gen) return@invokeLater
                kind.onSuccess { if (it == EntryKind.DIR) navigate(e.path) }.onFailure { setStatus(it.message ?: "Could not open ${e.name}", true) }
            }
        }
    }

    // preview

    private fun selectionChanged() {
        updateControls()
        val e = selected()
        val b = backend
        val my = gen
        val pg = ++previewGen
        sqliteButton.isVisible = false
        if (e == null || b == null) { previewTitle.text = " "; showMessage("Select a file to preview it."); return }
        previewTitle.text = e.name
        if (e.isDir) { showMessage("Folder. Double click to open it."); return }
        showMessage("Loading...")
        ApplicationManager.getApplication().executeOnPooledThread {
            // A database or a big file only needs its first bytes (the SQLite header) to be classified.
            val want = if (e.size > FilePreview.MAX_BYTES) 16 else FilePreview.MAX_BYTES + 1
            val result = runCatching { FilePreview.build(e.name, b.read(e.path, want), e.size) }
            SwingUtilities.invokeLater {
                if (my != gen || pg != previewGen) return@invokeLater
                result.onSuccess { render(it) }.onFailure { showMessage(it.message ?: it.javaClass.simpleName, error = true) }
            }
        }
    }

    private fun render(p: Preview) {
        when (p) {
            is Preview.Text -> { previewText.text = p.text; previewText.caretPosition = 0; previewCards.show(previewHolder, "text"); previewTitle.text = "${selected()?.name.orEmpty()} (${p.format})" }
            is Preview.Table -> {
                previewTableModel.setDataVector(p.rows.map { it.toTypedArray<Any>() }.toTypedArray(), p.columns.toTypedArray<Any>())
                previewCards.show(previewHolder, "table")
                previewTitle.text = p.title
            }
            is Preview.Message -> showMessage(p.text)
            is Preview.Sqlite -> showMessage(p.text + " Pull it with its -wal and -shm files and open it in a database tool.", showSqlite = true)
        }
    }

    private fun showMessage(text: String, error: Boolean = false, showSqlite: Boolean = false) {
        previewMessage.text = "<html>${text.replace("&", "&amp;").replace("<", "&lt;")}</html>"
        previewMessage.foreground = if (error) JBColor.RED else JBColor.foreground()
        sqliteButton.isVisible = showSqlite
        previewCards.show(previewHolder, "message")
    }

    // actions

    private fun appWriteWarning(): Boolean {
        val b = backend ?: return false
        if (!b.isAppData || warnedAboutAppWrites) return true
        val ok = Messages.showYesNoDialog(project, "This changes the private data of ${b.appId}. The app may misbehave or crash if its files are changed. Continue?", "App Data", null) == Messages.YES
        if (ok) warnedAboutAppWrites = true
        return ok
    }

    private fun download() {
        val b = backend ?: return
        val e = selected() ?: return
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Download ${e.name} to")
        val initial = MobileTestBenchSettings.getInstance().state.filesDownloadFolder?.takeIf { it.isNotBlank() }?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val folder = FileChooser.chooseFile(descriptor, project, initial)?.path?.let(::File) ?: return
        MobileTestBenchSettings.getInstance().state.filesDownloadFolder = folder.path
        if (File(folder, e.name).exists() &&
            Messages.showYesNoDialog(project, "${File(folder, e.name).path} exists. Replace it?", "Download", null) != Messages.YES
        ) return
        runOp("Downloading ${e.name}", { b.pull(e, folder) }) { local ->
            val bytes = if (local.isFile) local.length() else null
            val note = if (!e.isDir && bytes != null && e.size > 0 && bytes != e.size) " (the device listed ${FileFormat.size(e.size)})" else ""
            "Downloaded ${e.name}${bytes?.let { " (${FileFormat.size(it)})" } ?: ""} to ${local.path}$note"
        }
    }

    private fun upload() {
        val b = backend ?: return
        val file = FileChooser.chooseFile(FileChooserDescriptorFactory.singleFile().withTitle("Upload to $cwd"), project, null)?.path?.let(::File) ?: return
        if (!appWriteWarning()) return
        val existing = entries.any { it.name == file.name }
        if (existing && Messages.showYesNoDialog(project, "${file.name} already exists in $cwd. Replace it?", "Upload", null) != Messages.YES) return
        val dir = cwd
        runOp("Uploading ${file.name}", { b.push(file, dir) }, refreshAfter = true) { "Uploaded ${file.name} (${FileFormat.size(file.length())}) to $it" }
    }

    private fun newFolder() {
        val b = backend ?: return
        val name = Messages.showInputDialog(project, "Folder name in $cwd", "New Folder", null, "", object : com.intellij.openapi.ui.InputValidator {
            override fun checkInput(input: String?) = input != null && FilePaths.nameProblem(input) == null
            override fun canClose(input: String?) = checkInput(input)
        })?.trim() ?: return
        if (!appWriteWarning()) return
        val path = FilePaths.join(cwd, name)
        runOp("Creating $name", { b.mkdir(path) }, refreshAfter = true) { "Created $path" }
    }

    private fun delete() {
        val b = backend ?: return
        val e = selected() ?: return
        if (!appWriteWarning()) return
        val what = if (e.isDir) "the folder ${e.name} and everything in it" else e.name
        if (Messages.showYesNoDialog(project, "Delete $what from the device? This cannot be undone.", "Delete", Messages.getWarningIcon()) != Messages.YES) return
        runOp("Deleting ${e.name}", { b.delete(e) }, refreshAfter = true) { "Deleted ${e.path}" }
    }

    /** Runs [work] on a pooled thread with the progress bar, shows [done]'s text (or the error) and optionally reloads the folder. */
    private fun <T> runOp(label: String, work: () -> T, refreshAfter: Boolean = false, done: (T) -> String) {
        val my = gen
        setStatus("$label...", false)
        busy(+1)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching(work)
            SwingUtilities.invokeLater {
                busy(-1)
                if (my != gen) return@invokeLater
                result.onSuccess { setStatus(done(it), false) }.onFailure { setStatus(it.message ?: it.javaClass.simpleName, true) }
                if (refreshAfter && backend != null) {
                    val msg = status.text; val err = status.foreground == JBColor.RED
                    reloadKeepingStatus(msg, err)
                }
            }
        }
    }

    private fun reloadKeepingStatus(message: String, error: Boolean) {
        val b = backend ?: return
        val my = gen
        val lg = ++listGen
        val path = cwd
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { b.list(path) }
            SwingUtilities.invokeLater {
                if (my != gen || lg != listGen) return@invokeLater
                result.onSuccess { val keep = selected()?.path; show(path, it); setStatus(message, error); keep?.let { p -> entries.indexOfFirst { e -> e.path == p }.takeIf { i -> i >= 0 }?.let { i -> table.setRowSelectionInterval(i, i) } } }
                    .onFailure { setStatus("$message (reload failed: ${it.message})", true) }
            }
        }
    }

    private fun pullDatabase() {
        val b = backend ?: return
        val e = selected() ?: return
        val siblings = listOf("-wal", "-shm", "-journal").mapNotNull { suffix -> entries.firstOrNull { it.name == e.name + suffix } }
        runOp("Pulling ${e.name}", {
            val dir = java.nio.file.Files.createTempDirectory("mobiletestbench-db").toFile()
            try {
                val main = b.pull(e, dir)
                siblings.forEach { b.pull(it, dir) }
                main
            } catch (t: Throwable) {
                dir.deleteRecursively()
                throw t
            }
        }) { main ->
            ApplicationManager.getApplication().invokeLater { RevealFileAction.openFile(main) }
            "Pulled ${e.name}${if (siblings.isEmpty()) "" else " with ${siblings.joinToString { it.name.removePrefix(e.name) }}"} to ${main.parent} and showed it in the file manager"
        }
    }

    private fun copyCurrentPath() {
        val text = selected()?.path ?: cwd
        if (text.isEmpty()) return
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        setStatus("Copied $text", false)
    }

    // state

    private fun busy(delta: Int) {
        busy += delta
        progress.isVisible = busy > 0
        updateControls()
    }

    private fun setStatus(text: String, error: Boolean) {
        status.text = text.ifBlank { " " }.lines().first()
        status.toolTipText = text.takeIf { it.length > 80 }
        status.foreground = if (error) JBColor.RED else JBColor.foreground()
    }

    private fun updateControls() {
        val d = device
        val b = backend
        val ok = supported(d)
        val e = selected()
        locationCombo.isEnabled = ok
        appCombo.isEnabled = ok && locationCombo.selectedItem == APP_DATA
        foregroundButton.isEnabled = ok
        up.isEnabled = b != null && FilePaths.up(b.root, cwd) != null
        refresh.isEnabled = ok
        pathField.isEnabled = b != null
        copyPath.isEnabled = b != null
        upload.isEnabled = b != null && busy == 0
        newFolder.isEnabled = b != null && busy == 0
        delete.isEnabled = b != null && e != null && busy == 0
        download.isEnabled = b != null && e != null && busy == 0 && (!e.isDir || b.canDownloadFolder)
        download.toolTipText = if (b != null && e != null && e.isDir && !b.canDownloadFolder) "Folders of an app's data cannot be downloaded; download its files one by one" else null
        sqliteButton.isEnabled = busy == 0
    }

    companion object {
        private const val DEVICE_STORAGE = "Device storage"
        private const val APP_DATA = "App data"
    }
}
