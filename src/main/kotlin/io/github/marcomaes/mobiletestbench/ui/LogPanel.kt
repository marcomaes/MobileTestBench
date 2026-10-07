package io.github.marcomaes.mobiletestbench.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.marcomaes.mobiletestbench.DeviceServices
import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.apps.Apps
import io.github.marcomaes.mobiletestbench.core.logs.LogEntry
import io.github.marcomaes.mobiletestbench.core.logs.LogFilter
import io.github.marcomaes.mobiletestbench.core.logs.LogLevel
import io.github.marcomaes.mobiletestbench.core.logs.LogStream
import io.github.marcomaes.mobiletestbench.core.logs.StackFrames
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.datatransfer.StringSelection
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JToggleButton
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/** The live log of the connected device (logcat, simulator log stream) with level, text, tag and app filters, regular expressions, highlighting, jump to source and restart of the app. */
class LogPanel(private val project: Project) : JPanel(BorderLayout()), DeviceToolTab {

    override val title = "Log"
    override val component: JComponent get() = this
    override fun deviceChanged(device: Device?) = attach(device)
    override fun hidden() = Unit
    override fun dispose() = stop()

    /** Sets the App filter (package or process name), used by the app manager. */
    fun setAppFilter(name: String) {
        app.text = name
    }


    private val level = ComboBox(LogLevel.entries.toTypedArray()).apply { selectedItem = LogLevel.INFO }
    private val text = JBTextField(14).apply { emptyText.text = "Message contains" }
    private val tag = JBTextField(10).apply { emptyText.text = "Tag / process" }
    private val app = JBTextField(14).apply { emptyText.text = "App (package or process)" }
    private val regexToggle = JToggleButton(".*").apply { toolTipText = "Regular expressions (case-insensitive) for the message and tag filters" }
    private val restart = JButton("Restart app").apply {
        toolTipText = "Terminate and relaunch the app in the App field (a package or bundle id) and clear the list, to catch the first lines of its start"
        isEnabled = false
    }
    private val pause = JToggleButton("Pause")
    private val autoScroll = JBCheckBox("Scroll to end", true)
    private val status = JBLabel(" ").apply { border = JBUI.Borders.empty(2, 8) }

    /** Everything received (capped), and the part of it that passes the filter. */
    private val all = ArrayDeque<LogEntry>()
    private val view = ArrayList<LogEntry>()
    private val incoming = ConcurrentLinkedQueue<LogEntry>()
    private val incomingSize = AtomicInteger()
    private val model = LogModel()
    private val table = JBTable(model)

    private var device: Device? = null
    private var stream: LogStream? = null
    @Volatile private var appPids: Set<String>? = null
    private var filter = LogFilter()

    private val restartGeneration = AtomicInteger()
    private val drainTimer = Timer(200) { drain() }
    private val pidTimer = Timer(3000) { refreshPids() }

    init {
        table.setShowGrid(false)
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        table.columnModel.getColumn(0).preferredWidth = JBUI.scale(120)
        table.columnModel.getColumn(1).preferredWidth = JBUI.scale(50)
        table.columnModel.getColumn(2).preferredWidth = JBUI.scale(150)
        table.columnModel.getColumn(3).preferredWidth = JBUI.scale(800)
        table.setDefaultRenderer(String::class.java, MessageRenderer())
        table.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) = menu(e)
            override fun mouseReleased(e: java.awt.event.MouseEvent) = menu(e)

            private fun menu(e: java.awt.event.MouseEvent) {
                if (!e.isPopupTrigger) return
                val row = table.rowAtPoint(e.point)
                if (row >= 0 && !table.isRowSelected(row)) table.setRowSelectionInterval(row, row)
                val frame = view.getOrNull(row)?.let { StackFrames.extractFromMessage(it.message) }
                com.intellij.openapi.ui.JBPopupMenu().apply {
                    add(JMenuItem("Open in Editor").apply {
                        isEnabled = frame != null
                        addActionListener { openInEditor(row) }
                    })
                    add(JMenuItem("Copy").apply { addActionListener { copySelected() } })
                }.show(table, e.x, e.y)
            }

            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount != 2 || !SwingUtilities.isLeftMouseButton(e)) return
                val row = table.rowAtPoint(e.point)
                if (row >= 0) openInEditor(row)
            }
        })

        val onChange = object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = filterChanged()
        }
        text.document.addDocumentListener(onChange)
        tag.document.addDocumentListener(onChange)
        app.document.addDocumentListener(onChange)
        level.addActionListener { filterChanged() }
        regexToggle.addActionListener { filterChanged() }
        restart.addActionListener { restartApp() }

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
            add(JBLabel("Level:"))
            add(level)
            add(text)
            add(tag)
            add(app)
            add(restart)
            add(regexToggle)
            add(pause)
            add(autoScroll)
            add(JButton("Clear").apply { addActionListener { clear() } })
            add(JButton("Save...").apply { addActionListener { save() } })
        }
        add(controls, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        drainTimer.start()
    }

    /** Streams the log of [newDevice] (null stops it). */
    fun attach(newDevice: Device?) {
        stop()
        device = newDevice
        restartGeneration.incrementAndGet()
        clear()
        updateRestartEnabled()
        if (newDevice == null) {
            status.text = "Not connected"
            return
        }
        status.text = "Starting the log of ${newDevice.name}..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val started = try {
                DeviceServices.startLog(newDevice, ::receive) { error -> SwingUtilities.invokeLater { ended(newDevice, error) } }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { status.text = "Could not start the log: ${e.message?.lineSequence()?.firstOrNull()}" }
                return@executeOnPooledThread
            }
            SwingUtilities.invokeLater {
                if (device !== newDevice) {
                    started?.close()
                } else if (started == null) {
                    status.text = if (newDevice.platform == Platform.IOS) "No log is available for a physical iPhone (it needs mobilecli)" else "No log source for this device"
                } else {
                    stream = started
                    status.text = "Streaming the log of ${newDevice.name}" + (DeviceServices.logSourceNote(newDevice)?.let { " $it" } ?: "")
                    filterChanged()
                }
            }
        }
    }

    fun stop() {
        restartGeneration.incrementAndGet()
        pidTimer.stop()
        stream?.close()
        stream = null
    }

    private fun ended(ended: Device, error: String?) {
        if (device !== ended || stream == null) return
        stream = null
        status.text = "The log stopped" + (error?.let { ": ${it.lineSequence().first()}" } ?: "")
    }

    /** Called from the reader thread. */
    private fun receive(entry: LogEntry) {
        incoming.add(entry)
        if (incomingSize.incrementAndGet() > MAX_ENTRIES * 2) {
            incoming.poll()
            incomingSize.decrementAndGet()
        }
    }

    /** Moves what arrived into the list (not while paused). */
    private fun drain() {
        if (pause.isSelected) return
        val before = view.size
        var added = 0
        while (true) {
            val e = incoming.poll() ?: break
            incomingSize.decrementAndGet()
            all.addLast(e)
            if (all.size > MAX_ENTRIES) all.removeFirst()
            if (filter.matches(e)) {
                view.add(e)
                added++
            }
        }
        if (added == 0) return
        // The list is capped like the buffer, so the table stays fast.
        var removed = 0
        while (view.size > MAX_ENTRIES) {
            view.removeAt(0)
            removed++
        }
        if (removed > 0) model.fireTableDataChanged() else model.fireTableRowsInserted(before, view.size - 1)
        if (autoScroll.isSelected && view.isNotEmpty()) table.scrollRectToVisible(table.getCellRect(view.size - 1, 0, true))
        updateCount()
    }

    private fun currentFilter(): LogFilter {
        // On Android an app is its process ids; until they are known nothing of the app can match.
        val pids = if (app.text.isNotBlank() && device?.platform == Platform.ANDROID) appPids ?: emptySet() else null
        return LogFilter(level.selectedItem as LogLevel, text.text, tag.text, app.text, pids, regexToggle.isSelected, device?.platform == Platform.IOS)
    }

    private fun filterChanged() {
        filter = currentFilter()
        showRegexErrors()
        updateRestartEnabled()
        val current = device
        // On Android an app is a set of process ids that changes when the app restarts: look them up now and every few seconds.
        if (current?.platform == Platform.ANDROID && app.text.isNotBlank()) {
            pidTimer.start()
            refreshPids()
        } else {
            pidTimer.stop()
            appPids = null
        }
        rebuild()
    }

    /** An invalid pattern is shown in red with the reason; it is ignored until it is fixed. */
    private fun showRegexErrors() {
        for ((field, error) in listOf(text to filter.textError, tag to filter.tagError)) {
            field.putClientProperty("JComponent.outline", if (error != null) "error" else null)
            field.toolTipText = error?.let { "Invalid regular expression: $it" }
            field.repaint()
        }
    }

    private fun updateRestartEnabled() {
        restart.isEnabled = device != null && app.text.isNotBlank()
    }

    private fun refreshPids() {
        val current = device ?: return
        val name = app.text.trim()
        if (name.isEmpty()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val pids = runCatching { DeviceServices.androidPids(current, name) }.getOrDefault(emptySet())
            SwingUtilities.invokeLater {
                if (device === current && app.text.trim() == name && pids != appPids) {
                    appPids = pids
                    filter = currentFilter()
                    rebuild()
                }
            }
        }
    }

    private fun rebuild() {
        view.clear()
        all.filterTo(view) { filter.matches(it) }
        model.fireTableDataChanged()
        if (autoScroll.isSelected && view.isNotEmpty()) table.scrollRectToVisible(table.getCellRect(view.size - 1, 0, true))
        updateCount()
    }

    private fun updateCount() {
        val base = status.text.substringBefore("  |  ")
        status.text = "$base  |  ${view.size} of ${all.size} lines" +
            (if (app.text.isNotBlank() && filter.appPids?.isEmpty() == true) "  |  the app is not running" else "") +
            (filter.textError?.let { "  |  Invalid message regex (ignored): $it" } ?: "") +
            (filter.tagError?.let { "  |  Invalid tag regex (ignored): $it" } ?: "")
    }

    private fun clear() {
        all.clear()
        view.clear()
        incoming.clear()
        incomingSize.set(0)
        model.fireTableDataChanged()
    }

    /** Opens the source of the stack trace line in the given row (the first frame when the message has several). */
    private fun openInEditor(row: Int) {
        val entry = view.getOrNull(row) ?: return
        val frame = StackFrames.extractFromMessage(entry.message)
        if (frame == null) {
            status.text = "No stack trace line such as at com.example.Foo.bar(Foo.java:12) in this row"
            return
        }
        StackFrameOpener.open(project, frame) { status.text = it }
    }

    /**
     * Terminates and relaunches the app of the App filter and empties the list first, so the lines of the start are captured.
     * The log stream keeps running. On Android the new process id is looked up (every 250 ms for up to 5 s) so the first lines show.
     */
    private fun restartApp() {
        val current = device ?: return
        val name = app.text.trim()
        if (name.isEmpty()) return
        val generation = restartGeneration.incrementAndGet()
        pause.isSelected = false
        clear()
        status.text = "Restarting $name..."
        ApplicationManager.getApplication().executeOnPooledThread {
            // A terminate that fails (not running, simctl status 3) is not fatal: the launch is what counts.
            runCatching { DeviceServices.mobilecliCall(current, *Apps.terminateArgs(name).toTypedArray()) }
            val error = runCatching { DeviceServices.mobilecliCall(current, *Apps.launchArgs(current, name).toTypedArray()) }
                .exceptionOrNull()?.let { Apps.friendlyError(it.message?.lineSequence()?.firstOrNull() ?: it.toString()) }
            var pids = emptySet<String>()
            if (error == null && current.platform == Platform.ANDROID) {
                repeat(20) {
                    if (pids.isEmpty() && restartGeneration.get() == generation) {
                        pids = runCatching { DeviceServices.androidPids(current, name) }.getOrDefault(emptySet())
                        if (pids.isEmpty()) Thread.sleep(250)
                    }
                }
            }
            SwingUtilities.invokeLater {
                if (device !== current || restartGeneration.get() != generation) return@invokeLater // another device or restart meanwhile
                if (error != null) {
                    status.text = "Could not restart $name: $error"
                    return@invokeLater
                }
                status.text = "Restarted $name" + if (current.platform == Platform.ANDROID && pids.isEmpty()) " (process id not found yet)" else ""
                if (pids.isNotEmpty() && app.text.trim() == name) {
                    appPids = pids
                    filter = currentFilter()
                    rebuild()
                }
            }
        }
    }

    private fun copySelected() {
        val rows = table.selectedRows.toList().mapNotNull { view.getOrNull(it) }
        if (rows.isNotEmpty()) CopyPasteManager.getInstance().setContents(StringSelection(rows.joinToString("\n")))
    }

    private fun save() {
        val name = "${device?.name?.replace(Regex("[^A-Za-z0-9._-]+"), "-") ?: "device"}.log"
        val descriptor = FileSaverDescriptor("Save Log", "Save the lines that are shown").apply { withExtensionFilter("Log files", "log", "txt") }
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(null as VirtualFile?, name) ?: return
        val lines = view.toList()
        ApplicationManager.getApplication().executeOnPooledThread {
            val message = try {
                target.file.writeText(lines.joinToString("\n"))
                "Saved ${lines.size} lines to ${target.file.path}"
            } catch (e: Exception) {
                "Could not save the log: ${e.message}"
            }
            SwingUtilities.invokeLater { status.text = message }
        }
    }

    private inner class LogModel : AbstractTableModel() {
        override fun getRowCount() = view.size
        override fun getColumnCount() = 4
        override fun getColumnName(column: Int) = listOf("Time", "Level", "Tag / process", "Message")[column]
        override fun getColumnClass(column: Int): Class<*> = String::class.java
        override fun getValueAt(row: Int, column: Int): Any {
            val e = view[row]
            return when (column) {
                0 -> e.time
                1 -> e.level.name.first().toString()
                2 -> e.tag
                else -> e.message.lineSequence().first().replace("\t", "    ") + if (e.message.contains('\n')) "  [...]" else ""
            }
        }
    }

    /** Colours by level and paints the part of the message that the message filter matches. */
    private inner class MessageRenderer : DefaultTableCellRenderer() {
        private var ranges: List<IntRange> = emptyList()
        private var selected = false

        override fun getTableCellRendererComponent(t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int): Component {
            val c = super.getTableCellRendererComponent(t, value, sel, focus, row, col)
            if (!sel) {
                foreground = when (view.getOrNull(row)?.level) {
                    LogLevel.ERROR, LogLevel.FATAL -> JBColor(0xC7222D, 0xE55765)
                    LogLevel.WARN -> JBColor(0x9A6700, 0xE0B341)
                    LogLevel.DEBUG, LogLevel.VERBOSE -> JBColor.GRAY
                    else -> t.foreground
                }
            }
            selected = sel
            ranges = if (col == 3 && value is String) filter.matchRanges(value) else emptyList()
            return c
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (ranges.isNotEmpty()) {
                // After the text, translucent, so it also shows on a selected (opaque) row.
                val fm = getFontMetrics(font)
                val shown = text ?: ""
                g.color = if (selected) Color(0xFF, 0xD0, 0x40, 110) else HIGHLIGHT
                for (r in ranges) {
                    if (r.last >= shown.length) continue
                    val x = insets.left + fm.stringWidth(shown.substring(0, r.first))
                    g.fillRect(x, 1, fm.stringWidth(shown.substring(r.first, r.last + 1)), height - 2)
                }
            }
        }
    }

    companion object {
        /** How many lines are kept: the oldest go first. */
        private const val MAX_ENTRIES = 20_000

        /** Translucent so the text stays readable when painted over it. */
        private val HIGHLIGHT = JBColor(Color(0xFF, 0xE0, 0x66, 150), Color(0xB0, 0x95, 0x00, 110))
    }
}
