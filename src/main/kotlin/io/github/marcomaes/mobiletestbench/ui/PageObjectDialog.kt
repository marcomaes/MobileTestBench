package io.github.marcomaes.mobiletestbench.ui

import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.marcomaes.mobiletestbench.core.pageobject.LocatorStyle
import io.github.marcomaes.mobiletestbench.core.pageobject.PageCandidate
import io.github.marcomaes.mobiletestbench.core.pageobject.PageElement
import io.github.marcomaes.mobiletestbench.core.pageobject.PageLocator
import io.github.marcomaes.mobiletestbench.core.pageobject.PageObjectGenerator
import io.github.marcomaes.mobiletestbench.core.pageobject.PageObjectLanguage
import io.github.marcomaes.mobiletestbench.core.pageobject.PageObjectOptions
import io.github.marcomaes.mobiletestbench.settings.MobileTestBenchSettings
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Builds a page object class from the elements of the current screen: tick the elements, rename them, pick
 * the framework, language and where the locators live, then copy, insert or save the code. The choices
 * (language, style, base class, sorting) are remembered.
 */
class PageObjectDialog(
    private val project: Project,
    private val mobilewright: List<PageCandidate>,
    private val appium: List<PageCandidate>,
) : DialogWrapper(project) {

    private class Row(val candidate: PageCandidate, var name: String, var selected: Boolean)

    private val settings = MobileTestBenchSettings.getInstance().state

    /** Name and tick of each element survive a switch between Mobilewright and Appium (their locators differ). */
    private val choices = HashMap<Any, Pair<String, Boolean>>()

    private val className = JBTextField("ScreenPage", 14)
    private val language = ComboBox(PageObjectLanguage.entries.toTypedArray())
    private val style = ComboBox(LocatorStyle.entries.toTypedArray())
    private val baseClass = JBTextField(settings.pageObjectBaseClass.orEmpty().ifBlank { "BasePage" }, 12)
    private val generateBase = JBCheckBox("Generate the base class", settings.pageObjectGenerateBaseClass)
    private val sortByName = JBCheckBox("Sort by name", settings.pageObjectSortByName)

    private val rows = ArrayList<Row>()
    private val tableModel = RowModel()
    private val table = JBTable(tableModel)
    private val pagePreview = previewArea()
    private val basePreview = previewArea()
    private val tabs = JBTabbedPane()
    private val status = JBLabel(" ")

    private fun previewArea() = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        border = JBUI.Borders.empty(6)
    }

    init {
        title = "Generate Page Object"
        setOKButtonText("Close")
        language.selectedItem = runCatching { PageObjectLanguage.valueOf(settings.pageObjectLanguage.orEmpty()) }.getOrDefault(PageObjectLanguage.MOBILEWRIGHT_TS)
        style.selectedItem = runCatching { LocatorStyle.valueOf(settings.pageObjectLocatorStyle.orEmpty()) }.getOrDefault(LocatorStyle.BASE_CLASS_FIELDS)
        init()
        loadRows()
        val refresh = object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = optionsChanged()
        }
        className.document.addDocumentListener(refresh)
        baseClass.document.addDocumentListener(refresh)
        language.addActionListener { loadRows() }
        style.addActionListener { optionsChanged() }
        generateBase.addActionListener { optionsChanged() }
        sortByName.addActionListener { optionsChanged() }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    private val appiumSelected: Boolean get() = (language.selectedItem as PageObjectLanguage).appium

    private fun loadRows() {
        rows.forEach { choices[it.candidate.node] = it.name to it.selected }
        rows.clear()
        (if (appiumSelected) appium else mobilewright).forEach { c ->
            val remembered = choices[c.node]
            rows += Row(c, remembered?.first ?: c.suggestedName, remembered?.second ?: (c.selectedByDefault && c.locator != null))
        }
        tableModel.fireTableDataChanged()
        optionsChanged()
    }

    private fun elements(): List<PageElement> =
        rows.filter { it.selected && it.candidate.locator != null && it.name.isNotBlank() }
            .map { PageElement(it.name, it.candidate.actions, it.candidate.locator!!) }

    private fun identifier(text: String, fallback: String) = text.filter { it.isLetterOrDigit() || it == '_' }.ifEmpty { fallback }

    private fun options() = PageObjectOptions(
        className = identifier(className.text, "ScreenPage"),
        language = language.selectedItem as PageObjectLanguage,
        style = style.selectedItem as LocatorStyle,
        baseClass = identifier(baseClass.text, "BasePage"),
        sortByName = sortByName.isSelected,
    )

    /** Remembers the choices and rewrites the code. */
    private fun optionsChanged() {
        val o = options()
        settings.pageObjectLanguage = o.language.name
        settings.pageObjectLocatorStyle = o.style.name
        settings.pageObjectBaseClass = o.baseClass
        settings.pageObjectGenerateBaseClass = generateBase.isSelected
        settings.pageObjectSortByName = o.sortByName

        val usesBase = o.style.usesBaseClass
        baseClass.isEnabled = usesBase
        generateBase.isEnabled = usesBase
        val chosen = elements()
        pagePreview.text = if (chosen.isEmpty()) "// Tick the elements to include." else PageObjectGenerator.generate(chosen, o)
        val showBase = usesBase && generateBase.isSelected
        basePreview.text = if (showBase) PageObjectGenerator.generateBaseClass(o) else "// No base class is generated."
        tabs.setEnabledAt(1, showBase)
        if (!showBase && tabs.selectedIndex == 1) tabs.selectedIndex = 0
        pagePreview.caretPosition = 0
        basePreview.caretPosition = 0
        val noLocator = rows.count { it.candidate.locator == null }
        status.text = "${chosen.size} elements" + if (noLocator > 0) ", $noLocator without a unique locator are not offered" else ""
    }

    private fun currentText() = if (tabs.selectedIndex == 1) basePreview.text else pagePreview.text

    override fun createCenterPanel(): JComponent {
        table.columnModel.getColumn(0).apply { preferredWidth = JBUI.scale(40); maxWidth = JBUI.scale(50) }
        table.columnModel.getColumn(1).preferredWidth = JBUI.scale(190)
        table.columnModel.getColumn(2).preferredWidth = JBUI.scale(90)
        table.columnModel.getColumn(3).preferredWidth = JBUI.scale(420)
        table.setDefaultRenderer(String::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int): Component {
                val c = super.getTableCellRendererComponent(t, value, sel, focus, row, col)
                if (!sel) foreground = if (rows.getOrNull(row)?.candidate?.locator == null) JBColor.GRAY else t.foreground
                return c
            }
        })
        val top = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JBLabel("Class name:"))
            add(className)
            add(JBLabel("Language:"))
            add(language)
            add(JBLabel("Locators:"))
            add(style)
        }
        val second = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JBLabel("Base class:"))
            add(baseClass)
            add(generateBase)
            add(sortByName)
        }
        tabs.addTab("Page object", ScrollPaneFactory.createScrollPane(pagePreview))
        tabs.addTab("Base class", ScrollPaneFactory.createScrollPane(basePreview))
        tabs.preferredSize = Dimension(JBUI.scale(780), JBUI.scale(250))
        val buttons = JPanel().apply {
            add(JButton("Copy").apply {
                addActionListener {
                    CopyPasteManager.getInstance().setContents(StringSelection(currentText()))
                    status.text = "Copied"
                }
            })
            add(JButton("Insert at Caret").apply { addActionListener { status.text = EditorInsert.insertAtCaret(project, currentText()) ?: "Inserted" } })
            add(JButton("Save...").apply { addActionListener { save() } })
        }
        val header = JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(second, BorderLayout.SOUTH)
        }
        val tableArea = ScrollPaneFactory.createScrollPane(table).apply { preferredSize = Dimension(JBUI.scale(780), JBUI.scale(200)) }
        val footer = JPanel(BorderLayout()).apply {
            add(tabs, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                add(status, BorderLayout.WEST)
                add(buttons, BorderLayout.EAST)
            }, BorderLayout.SOUTH)
        }
        return JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(header, BorderLayout.NORTH)
            add(tableArea, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
        }
    }

    private fun save() {
        val o = options()
        val name = PageObjectGenerator.fileName(o.language, if (tabs.selectedIndex == 1) o.baseClass else o.className)
        val descriptor = FileSaverDescriptor("Save Page Object", "Save the generated code", o.language.extension)
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(null as VirtualFile?, name) ?: return
        status.text = try {
            target.file.writeText(currentText())
            "Saved to ${target.file.path}"
        } catch (e: Exception) {
            "Could not save: ${e.message}"
        }
    }

    private inner class RowModel : AbstractTableModel() {
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 4
        override fun getColumnName(column: Int) = listOf("", "Name", "Type", "Locator")[column]
        override fun getColumnClass(column: Int): Class<*> = if (column == 0) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(row: Int, column: Int) = rows[row].candidate.locator != null && (column == 0 || column == 1)

        override fun getValueAt(row: Int, column: Int): Any {
            val r = rows[row]
            return when (column) {
                0 -> r.selected
                1 -> r.name
                2 -> r.candidate.typeName
                else -> when (val l = r.candidate.locator) {
                    is PageLocator.Mobilewright -> l.expression
                    is PageLocator.Appium -> "${l.strategy.name.lowercase().replace('_', ' ')}: ${l.value}"
                    null -> "no unique locator"
                }
            }
        }

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            val r = rows[row]
            if (column == 0) r.selected = value as Boolean else if (column == 1) r.name = value as String
            fireTableRowsUpdated(row, row)
            optionsChanged()
        }
    }
}
