package io.github.marcomaes.mobiletestbench.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.marcomaes.mobiletestbench.DeviceServices
import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.crashes.CrashEntry
import io.github.marcomaes.mobiletestbench.core.crashes.Crashes
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel

/** Crash reports of the connected device (mobilecli `device crashes`): a filterable list, newest first, and the full report of the selected one. */
class CrashesPanel(private val project: Project) : JPanel(BorderLayout()), DeviceToolTab {

    override val title = "Crashes"
    override val component: JComponent get() = this
    override fun deviceChanged(device: Device?) {
        this.device = device
        listGeneration++
        reportGeneration++
        all = emptyList()
        applyFilter()
        clearReport()
        note.text = device?.let { Crashes.listNote(it) } ?: " "
        if (device == null) status.text = "Not connected" else if (visible) refresh()
    }
    override fun shown() {
        visible = true
        if (device != null && all.isEmpty()) refresh()
    }
    override fun hidden() { visible = false }
    override fun dispose() { listGeneration++; reportGeneration++ }

    private var device: Device? = null
    private var visible = false
    private var all: List<CrashEntry> = emptyList()
    private var shownEntries: List<CrashEntry> = emptyList()
    private var report = ""
    private var displayedId: String? = null
    private var rebuilding = false
    private var listGeneration = 0
    private var reportGeneration = 0

    private val filter = JBTextField(18).apply { emptyText.text = "Filter by process, id or time" }
    private val status = JBLabel("Not connected").apply { border = JBUI.Borders.empty(2, 8) }
    private val note = JBLabel(" ").apply { foreground = JBColor.GRAY }
    private val summary = JBLabel(" ").apply {
        font = font.deriveFont(Font.BOLD, font.size2D + 1f)
        foreground = JBColor.RED
        border = JBUI.Borders.empty(4, 8)
    }
    private val model = CrashModel()
    private val table = JBTable(model)
    private val text = JTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
    }
    private val copy = JButton("Copy").apply { isEnabled = false }
    private val save = JButton("Save...").apply { isEnabled = false }

    init {
        table.setShowGrid(false)
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        table.columnModel.getColumn(0).preferredWidth = JBUI.scale(170)
        table.columnModel.getColumn(1).preferredWidth = JBUI.scale(260)
        table.columnModel.getColumn(2).preferredWidth = JBUI.scale(320)
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) loadSelected() }
        filter.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = applyFilter()
        })
        copy.addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(report)) }
        save.addActionListener { save() }

        val top = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
            add(JButton("Refresh").apply { addActionListener { refresh() } })
            add(filter)
            add(note)
        }
        val detailButtons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
            add(copy)
            add(save)
        }
        val detailHeader = JPanel(BorderLayout()).apply {
            add(summary, BorderLayout.CENTER)
            add(detailButtons, BorderLayout.EAST)
        }
        val detail = JPanel(BorderLayout()).apply {
            add(detailHeader, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(text), BorderLayout.CENTER)
        }
        val splitter = JBSplitter(false, 0.4f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(table)
            secondComponent = detail
        }
        add(top, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
    }

    private fun selected(): CrashEntry? = shownEntries.getOrNull(table.selectedRow)

    private fun refresh() {
        val dev = device ?: return
        val gen = ++listGeneration
        status.text = "Loading crash reports..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { Crashes.parseList(DeviceServices.mobilecliData(dev, *Crashes.LIST_ARGS.toTypedArray())) }
            SwingUtilities.invokeLater {
                if (gen != listGeneration) return@invokeLater
                result.onSuccess {
                    all = it
                    applyFilter()
                    status.text = if (it.isEmpty()) "No crash reports" else "${it.size} crash reports"
                }.onFailure { status.text = "Could not list crash reports: ${it.message}" }
            }
        }
    }

    private fun applyFilter() {
        val keep = selected()?.id
        shownEntries = Crashes.filter(all, filter.text)
        // Rebuilding the rows drops the table selection; keep the selected crash (and its open report) if it is still listed.
        rebuilding = true
        try {
            model.fireTableDataChanged()
            val index = keep?.let { id -> shownEntries.indexOfFirst { it.id == id } } ?: -1
            if (index >= 0) table.setRowSelectionInterval(index, index)
        } finally {
            rebuilding = false
        }
        if (table.selectedRow < 0 && (keep != null || shownEntries.isEmpty())) clearReport()
    }

    /** Clears the report area and invalidates any report fetch still in flight. */
    private fun clearReport() {
        reportGeneration++
        displayedId = null
        showReport(null, "")
    }

    /** Fetches the full report of the selected row (iOS reports are about 200 KB, so only on selection). */
    private fun loadSelected() {
        if (rebuilding) return
        val dev = device ?: return
        val entry = selected() ?: run { clearReport(); return }
        if (entry.id == displayedId) return
        displayedId = entry.id
        val gen = ++reportGeneration
        showReport(null, "Loading ${entry.id}...")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                Crashes.parseReport(DeviceServices.mobilecliData(dev, *Crashes.getArgs(entry.id).toTypedArray(), timeout = java.time.Duration.ofSeconds(60)))
                    ?: error("The report is empty")
            }
            val extracted = result.getOrNull()?.let { Crashes.summary(dev.platform, it) }
            SwingUtilities.invokeLater {
                if (gen != reportGeneration) return@invokeLater
                result.onSuccess { showReport(extracted, it, available = true) }
                    .onFailure { displayedId = null; showReport(null, "Could not load the report: ${it.message}") }
            }
        }
    }

    private fun showReport(summaryText: String?, content: String, available: Boolean = false) {
        report = if (available) content else ""
        summary.text = summaryText ?: " "
        text.text = content
        text.caretPosition = 0
        copy.isEnabled = available
        save.isEnabled = available
    }

    private fun save() {
        val dev = device ?: return
        val entry = selected() ?: return
        val content = report
        val descriptor = FileSaverDescriptor("Save Crash Report", "Save the full report").apply { withExtensionFilter("Crash reports", "txt", "ips") }
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(null as VirtualFile?, Crashes.fileName(entry, dev.platform)) ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val message = try {
                target.file.writeText(content)
                "Saved to ${target.file.path}"
            } catch (e: Exception) {
                "Could not save the report: ${e.message}"
            }
            SwingUtilities.invokeLater { status.text = message }
        }
    }

    private inner class CrashModel : AbstractTableModel() {
        private val columns = arrayOf("Time", "Process / app", "Id")
        override fun getRowCount() = shownEntries.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val e = shownEntries[rowIndex]
            return when (columnIndex) {
                0 -> e.timestamp
                1 -> e.process
                else -> e.id
            }
        }
    }
}
