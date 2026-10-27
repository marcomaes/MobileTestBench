package io.github.marcomaes.mobiletestbench.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import io.github.marcomaes.mobiletestbench.core.inspector.AccessibilityAudit
import io.github.marcomaes.mobiletestbench.core.inspector.AccessibilityIssue
import io.github.marcomaes.mobiletestbench.core.inspector.FilterCriteria
import io.github.marcomaes.mobiletestbench.core.inspector.HierarchyFilter
import io.github.marcomaes.mobiletestbench.core.inspector.LocatorGenerator
import io.github.marcomaes.mobiletestbench.core.inspector.LocatorHints
import io.github.marcomaes.mobiletestbench.core.inspector.LocatorQuery
import io.github.marcomaes.mobiletestbench.core.inspector.LocatorSuggestion
import io.github.marcomaes.mobiletestbench.core.inspector.MobilewrightLocators
import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import io.github.marcomaes.mobiletestbench.core.pageobject.LocatorCode
import io.github.marcomaes.mobiletestbench.core.pageobject.LocatorLanguage
import io.github.marcomaes.mobiletestbench.settings.MobileTestBenchSettings
import java.awt.BorderLayout
import java.awt.Component
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

private const val ACCESSIBILITY_TAB = "Accessibility"
private const val ISSUE_TAB_INDEX = 2

/**
 * Element tree, locator suggestions (Mobilewright first, then Appium) and raw properties.
 */
class InspectorPanel(
    private val project: Project,
    private val onNodeSelected: (UiNode?) -> Unit,
    private val onQueryMatches: (List<UiNode>) -> Unit,
    private val onIssueNodes: (List<UiNode>) -> Unit,
    /** Screen density of the device, may block (called on a pooled thread). */
    private val density: () -> Int? = { null },
    private val currentFrame: () -> java.awt.image.BufferedImage? = { null },
    private val logicalSize: () -> io.github.marcomaes.mobiletestbench.core.Size? = { null },
) : JPanel(BorderLayout()) {

    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode("No hierarchy"))
    private val tree = Tree(treeModel)
    private val locatorModel = LocatorTableModel()
    private val locatorTable = object : JBTable(locatorModel) {
        override fun getToolTipText(event: MouseEvent): String? {
            val row = rowAtPoint(event.point).takeIf { it >= 0 } ?: return null
            val suggestion = locatorModel.rows.getOrNull(convertRowIndexToModel(row)) ?: return null
            val text = LocatorHints.hintWithAlternative(suggestion, locatorModel.rows) ?: return null
            return "<html><body style='width:${JBUI.scale(360)}px'>${StringUtil.escapeXmlEntities(text)}</body></html>"
        }
    }
    private val propertyModel = PropertyTableModel()
    private val propertyTable = JBTable(propertyModel)
    private val issueModel = IssueTableModel()
    private val issueTable = object : JBTable(issueModel) {
        override fun getToolTipText(event: MouseEvent): String? {
            val row = rowAtPoint(event.point).takeIf { it >= 0 } ?: return null
            val message = issueModel.rows.getOrNull(convertRowIndexToModel(row))?.message ?: return null
            return "<html><body style='width:${JBUI.scale(360)}px'>${StringUtil.escapeXmlEntities(message)}</body></html>"
        }
    }
    private val auditStatus = JBLabel(" ")
    private val contrastBox = JBCheckBox("Contrast hints", MobileTestBenchSettings.getInstance().state.contrastHints).apply {
        toolTipText = "Estimates text contrast (WCAG 1.4.3) from the screen image. A heuristic: treat results as hints."
    }
    private val auditGeneration = java.util.concurrent.atomic.AtomicInteger()
    private var auditInput: AuditInput? = null

    private class AuditInput(val root: UiNode, val frame: java.awt.image.BufferedImage?, val logical: io.github.marcomaes.mobiletestbench.core.Size?)
    private val tabs: JBTabbedPane
    private val queryField = JBTextField().apply {
        emptyText.text = "Try a locator: screen.getByTestId('...'), //XPath, id=..., By.id(\"...\"), -android uiautomator=..."
    }
    private val languageBox = ComboBox(LocatorLanguage.entries.toTypedArray()).apply {
        selectedItem = LocatorLanguage.fromName(MobileTestBenchSettings.getInstance().state.locatorLanguage)
        toolTipText = "What double-click, Copy and Insert at Caret put on the clipboard or into the editor"
        addActionListener { MobileTestBenchSettings.getInstance().state.locatorLanguage = language.name }
    }
    private val wholeLineBox = JBCheckBox("Whole line", MobileTestBenchSettings.getInstance().state.locatorWholeLine).apply {
        toolTipText = "Copy as a statement, e.g. driver.findElement(By.id(\"x\")) or await \$('~x'), instead of the bare locator"
        addActionListener { MobileTestBenchSettings.getInstance().state.locatorWholeLine = isSelected }
    }
    private val language: LocatorLanguage get() = languageBox.selectedItem as? LocatorLanguage ?: LocatorLanguage.AS_SHOWN
    private val queryResult = JBLabel(" ").apply { border = JBUI.Borders.empty(0, 8, 4, 8) }
    private val hint = JBLabel("Double-click a locator to copy it, right-click to insert it at the caret").apply {
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(4, 8)
    }

    private val filterField = JBTextField().apply {
        text = MobileTestBenchSettings.getInstance().state.elementFilter
        emptyText.text = "Filter: text, or clickable=true class~Button"
        toolTipText = "<html>Free text matches type, id, label, text and value.<br>" +
            "A word with a leading - or ! must not match: -error, -clickable=true.<br>" +
            "/regex/ is a case-insensitive regular expression (quote it if it has spaces).<br>" +
            "OR (or |) between groups shows elements matching either group: Login OR Sign.<br>" +
            "Attribute terms, separated by spaces, must all match:<br>" +
            "&nbsp;key=value (equals), key!=value (not equal), key~value (contains), case-insensitive.<br>" +
            "Quote a value with spaces: text=\"Log in\".<br>" +
            "Keys: enabled, clickable, focused, checked, selected, visible (true/false),<br>" +
            "&nbsp;type or class, id or resource-id, label, text, content-desc, value.<br>" +
            "Any other key is read from the raw attributes. Example: clickable=true enabled=false class~Button</html>"
    }
    private val filterProblem = JBLabel(" ").apply {
        foreground = JBColor.RED
        border = JBUI.Borders.empty(0, 8, 2, 8)
        isVisible = false
    }
    private val visibleOnlyBox = JBCheckBox("Visible")
    private val interactiveOnlyBox = JBCheckBox("Interactive")
    private val treePanel = JPanel(BorderLayout()).apply {
        add(JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(4, 8)
                add(filterField, BorderLayout.CENTER)
                add(JPanel().apply {
                    layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
                    add(visibleOnlyBox)
                    add(interactiveOnlyBox)
                }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(filterProblem, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
    }
    private val errorText = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8)
    }
    private val errorScroll = ScrollPaneFactory.createScrollPane(errorText)
    private val splitter = OnePixelSplitter(true, 0.55f)

    private var root: UiNode? = null
    private var suppressSelectionEvent = false

    init {
        tree.isRootVisible = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = NodeRenderer()
        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)
        tree.addTreeSelectionListener {
            val node = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? UiNode
            showDetails(node)
            if (!suppressSelectionEvent) onNodeSelected(node)
        }

        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = showTreeMenu(e)
            override fun mouseReleased(e: MouseEvent) = showTreeMenu(e)
        })

        locatorTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        locatorTable.setShowGrid(false)
        locatorTable.columnModel.getColumn(0).preferredWidth = JBUI.scale(90)
        locatorTable.columnModel.getColumn(1).preferredWidth = JBUI.scale(130)
        locatorTable.columnModel.getColumn(2).preferredWidth = JBUI.scale(380)
        locatorTable.columnModel.getColumn(3).preferredWidth = JBUI.scale(80)
        locatorTable.setDefaultRenderer(Any::class.java, LocatorRenderer())
        locatorTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val row = locatorTable.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
                copy(locatorModel.rows[locatorTable.convertRowIndexToModel(row)])
            }
        })
        locatorTable.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = showMenu(e)
            override fun mouseReleased(e: MouseEvent) = showMenu(e)

            private fun showMenu(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val row = locatorTable.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
                locatorTable.setRowSelectionInterval(row, row)
                val suggestion = locatorModel.rows[locatorTable.convertRowIndexToModel(row)]
                JBPopupMenu().apply {
                    add(JMenuItem("Copy").apply { addActionListener { copy(suggestion) } })
                    add(JMenuItem("Insert at Caret").apply { addActionListener { insertAtCaret(suggestion) } })
                }.show(locatorTable, e.x, e.y)
            }
        })
        filterField.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                MobileTestBenchSettings.getInstance().state.elementFilter = filterField.text
                rebuildTree()
            }
        })
        visibleOnlyBox.addActionListener { rebuildTree() }
        interactiveOnlyBox.addActionListener { rebuildTree() }
        queryField.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = runQuery()
        })
        propertyTable.setShowGrid(false)

        issueTable.columnModel.getColumn(1).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
            ): Component {
                val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                if (!isSelected) {
                    // AA failures red, guidance orange, hints gray.
                    foreground = when (issueModel.rows.getOrNull(table.convertRowIndexToModel(row))?.rule) {
                        AccessibilityIssue.Rule.MISSING_NAME, AccessibilityIssue.Rule.TARGET_BELOW_MINIMUM -> JBColor(0xC7222D, 0xE55765)
                        AccessibilityIssue.Rule.TARGET_BELOW_RECOMMENDED -> JBColor(0xB26B00, 0xD9A343)
                        else -> JBColor.GRAY
                    }
                }
                return c
            }
        }
        contrastBox.addActionListener {
            MobileTestBenchSettings.getInstance().state.contrastHints = contrastBox.isSelected
            runAudit()
        }
        issueTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        issueTable.columnModel.getColumn(1).preferredWidth = JBUI.scale(140)
        issueTable.columnModel.getColumn(2).preferredWidth = JBUI.scale(500)
        issueTable.setShowGrid(false)
        // The expanded cell popup runs past the window edge; the tooltip shows the full text wrapped instead.
        issueTable.expandableItemsHandler.isEnabled = false
        issueTable.selectionModel.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            val row = issueTable.selectedRow.takeIf { r -> r >= 0 } ?: return@addListSelectionListener
            issueModel.rows.getOrNull(issueTable.convertRowIndexToModel(row))?.node?.let(::select)
        }

        tabs = JBTabbedPane().apply {
            addTab("Locators", JPanel(BorderLayout()).apply {
                add(JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(4, 8, 0, 8)
                    add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                        add(queryField, BorderLayout.CENTER)
                        add(JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                            add(JBLabel("Copy as:"), BorderLayout.WEST)
                            add(languageBox, BorderLayout.CENTER)
                            add(wholeLineBox, BorderLayout.EAST)
                        }, BorderLayout.EAST)
                    }, BorderLayout.NORTH)
                    add(queryResult, BorderLayout.SOUTH)
                }, BorderLayout.NORTH)
                add(ScrollPaneFactory.createScrollPane(locatorTable), BorderLayout.CENTER)
                add(hint, BorderLayout.SOUTH)
            })
            addTab("Properties", ScrollPaneFactory.createScrollPane(propertyTable))
            addTab(ACCESSIBILITY_TAB, JPanel(BorderLayout()).apply {
                add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                    border = JBUI.Borders.empty(2, 8)
                    add(auditStatus, BorderLayout.CENTER)
                    add(contrastBox, BorderLayout.EAST)
                }, BorderLayout.NORTH)
                add(ScrollPaneFactory.createScrollPane(issueTable), BorderLayout.CENTER)
            })
            addChangeListener { publishIssues() }
        }

        splitter.firstComponent = treePanel
        splitter.secondComponent = tabs
        add(splitter, BorderLayout.CENTER)
    }

    /**
     * [frame] is the screen image the contrast rule samples; the caller passes the one that was current when the
     * hierarchy was captured, by default the current one (the frozen one while frozen).
     */
    fun setHierarchy(newRoot: UiNode?, frame: java.awt.image.BufferedImage? = currentFrame()) {
        showTree()
        root = newRoot
        rebuildTree()
        runQuery()
        auditInput = newRoot?.let { AuditInput(it, frame, logicalSize()) }
        runAudit()
    }

    /** The audit samples pixels and may ask the device for its density, so it runs off the UI thread; a newer run drops an older result. */
    private fun runAudit() {
        val generation = auditGeneration.incrementAndGet()
        val input = auditInput
        if (input == null) {
            issueModel.rows = emptyList()
            auditStatus.text = " "
            updateIssueTab()
            return
        }
        val contrast = contrastBox.isSelected
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                val dpi = if (input.root.platform == io.github.marcomaes.mobiletestbench.core.Platform.ANDROID) runCatching { density() }.getOrNull() else null
                AccessibilityAudit.analyse(input.root, dpi, { input.frame }, input.logical, contrast)
            } catch (e: Exception) {
                null
            }
            SwingUtilities.invokeLater {
                if (auditGeneration.get() != generation) return@invokeLater
                issueModel.rows = result?.issues.orEmpty()
                auditStatus.text = result?.statusLine ?: "Accessibility audit failed"
                updateIssueTab()
            }
        }
    }

    /** Fills the tree from [root], narrowed by the filter. */
    private fun rebuildTree() {
        val current = root
        val criteria = FilterCriteria(filterField.text, visibleOnlyBox.isSelected, interactiveOnlyBox.isSelected)
        val shown = current?.let { HierarchyFilter.visibleNodes(it, criteria) }
        val problems = criteria.invalidTerms
        filterProblem.isVisible = problems.isNotEmpty()
        filterProblem.text = problems.joinToString("; ", "Not understood: ") { "${it.text} (${it.reason})" }
        val treeRoot = when {
            current == null -> DefaultMutableTreeNode("No hierarchy")
            shown != null && shown.isEmpty() -> DefaultMutableTreeNode("No element matches the filter")
            else -> buildTree(current, shown)
        }
        treeModel.setRoot(treeRoot)
        TreeUtil.expandAll(tree)
        showDetails(null)
    }

    /** Shows a long, possibly multi line error in full (a tree node would cut it off). */
    fun setError(message: String) {
        root = null
        auditInput = null
        auditGeneration.incrementAndGet()
        auditStatus.text = " "
        onQueryMatches(emptyList())
        issueModel.rows = emptyList()
        updateIssueTab()
        showDetails(null)
        errorText.text = message
        errorText.caretPosition = 0
        if (splitter.firstComponent !== errorScroll) splitter.firstComponent = errorScroll
    }

    private fun showTree() {
        if (splitter.firstComponent !== treePanel) splitter.firstComponent = treePanel
    }

    fun setMessage(message: String) {
        showTree()
        root = null
        auditInput = null
        auditGeneration.incrementAndGet()
        auditStatus.text = " "
        onQueryMatches(emptyList())
        issueModel.rows = emptyList()
        updateIssueTab()
        treeModel.setRoot(DefaultMutableTreeNode(message))
        showDetails(null)
    }

    /** Selects the node in the tree, e.g. after clicking on the screen. */
    fun select(node: UiNode) {
        val treeRoot = treeModel.root as? DefaultMutableTreeNode ?: return
        val match = treeRoot.breadthFirstEnumeration().asSequence()
            .filterIsInstance<DefaultMutableTreeNode>()
            .firstOrNull { it.userObject === node } ?: return
        val path = TreePath(match.path)
        suppressSelectionEvent = true
        try {
            tree.selectionPath = path
            tree.scrollPathToVisible(path)
        } finally {
            suppressSelectionEvent = false
        }
        onNodeSelected(node)
    }

    private fun buildTree(node: UiNode, shown: Set<UiNode>?): DefaultMutableTreeNode =
        DefaultMutableTreeNode(node).also { treeNode ->
            node.children.filter { shown == null || it in shown }.forEach { treeNode.add(buildTree(it, shown)) }
        }

    private fun propertiesOf(n: UiNode): List<Pair<String, String>> = buildList {
        add("type" to n.type)
        n.bounds?.let { add("bounds" to "x=${it.x} y=${it.y} w=${it.width} h=${it.height}") }
        add("xpath" to n.absoluteXPath())
        n.attributes.forEach { (k, v) -> add(k to v) }
    }

    /** Right-click on an element: copy any of its locators, or all of its properties. */
    private fun showTreeMenu(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val path = tree.getPathForLocation(e.x, e.y) ?: return
        val node = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? UiNode ?: return
        val currentRoot = root ?: return
        tree.selectionPath = path
        JBPopupMenu().apply {
            if (!node.isRoot) {
                LocatorGenerator.suggest(node, currentRoot).forEach { suggestion ->
                    add(JMenuItem(LocatorCode.menuLabel(suggestion, language)).apply {
                        addActionListener { copy(suggestion) }
                    })
                }
                addSeparator()
            }
            add(JMenuItem("Copy All Properties").apply {
                addActionListener {
                    val text = propertiesOf(node).joinToString("\n") { (k, v) -> "$k=$v" }
                    CopyPasteManager.getInstance().setContents(StringSelection(text))
                    hint.text = "Copied ${node.displayLabel()} properties"
                }
            })
        }.show(tree, e.x, e.y)
    }

    private fun showDetails(node: UiNode?) {
        val currentRoot = root
        locatorModel.rows = if (node != null && currentRoot != null && !node.isRoot) LocatorGenerator.suggest(node, currentRoot) else emptyList()
        propertyModel.rows = node?.let(::propertiesOf) ?: emptyList()
        if (locatorModel.rows.isNotEmpty()) locatorTable.setRowSelectionInterval(0, 0)
    }

    private fun copy(suggestion: LocatorSuggestion) {
        val out = LocatorCode.textFor(suggestion, language, wholeLineBox.isSelected)
        CopyPasteManager.getInstance().setContents(StringSelection(out.text))
        hint.text = "Copied: ${out.text}" + (out.note?.let { " ($it)" } ?: "")
    }

    private fun updateIssueTab() {
        val count = issueModel.rows.size
        tabs.setTitleAt(ISSUE_TAB_INDEX, if (count == 0) ACCESSIBILITY_TAB else "$ACCESSIBILITY_TAB ($count)")
        publishIssues()
    }

    /** The issues are outlined on the screen only while their tab is open. */
    private fun publishIssues() {
        val open = tabs.selectedIndex == ISSUE_TAB_INDEX
        onIssueNodes(if (open) issueModel.rows.map { it.node } else emptyList())
    }

    /** Adds a tab after the built-in ones. */
    fun addTab(title: String, component: java.awt.Component) {
        tabs.addTab(title, component)
    }

    fun showTab(title: String) {
        val index = tabs.indexOfTab(title)
        if (index >= 0) tabs.selectedIndex = index
    }

    /** Evaluates the typed locator against the current hierarchy and highlights the matches on the screen. */
    private fun runQuery() {
        val tree = root
        if (queryField.text.isBlank() || tree == null) {
            queryResult.text = " "
            onQueryMatches(emptyList())
            return
        }
        try {
            val matches = LocatorQuery.find(queryField.text, tree)
            queryResult.foreground = if (matches.size == 1) JBColor(0x208A3C, 0x5FB865) else JBColor(0xC7222D, 0xE55765)
            queryResult.text = when (matches.size) {
                0 -> "No match"
                1 -> "1 match (unique)"
                else -> "${matches.size} matches"
            }
            onQueryMatches(matches)
        } catch (e: IllegalArgumentException) {
            queryResult.foreground = JBColor.GRAY
            queryResult.text = e.message ?: "Invalid locator"
            onQueryMatches(emptyList())
        }
    }

    private fun insertAtCaret(suggestion: LocatorSuggestion) {
        val out = LocatorCode.textFor(suggestion, language, wholeLineBox.isSelected)
        hint.text = EditorInsert.insertAtCaret(project, out.text) ?: ("Inserted: ${out.text}" + (out.note?.let { " ($it)" } ?: ""))
    }

    private class LocatorTableModel : AbstractTableModel() {
        var rows: List<LocatorSuggestion> = emptyList()
            set(value) {
                field = value
                fireTableDataChanged()
            }

        override fun getRowCount() = rows.size
        override fun getColumnCount() = 4
        override fun getColumnName(column: Int) = listOf("Framework", "Strategy", "Locator", "Matches")[column]
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows[rowIndex]
            return when (columnIndex) {
                0 -> row.framework
                1 -> if (row.isBest) "${row.strategy} (best)" else row.strategy
                2 -> row.expression
                else -> when (row.matchCount) {
                    0 -> "none"
                    1 -> "unique"
                    else -> "dup (${row.matchCount})"
                }
            }
        }
    }

    private class IssueTableModel : AbstractTableModel() {
        var rows: List<AccessibilityIssue> = emptyList()
            set(value) {
                field = value
                fireTableDataChanged()
            }

        override fun getRowCount() = rows.size
        override fun getColumnCount() = 3
        override fun getColumnName(column: Int) = listOf("Element", "Kind", "Issue")[column]
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows[rowIndex]
            return when (columnIndex) {
                0 -> row.node.displayLabel()
                1 -> row.rule.title
                else -> row.message
            }
        }
    }

    private class PropertyTableModel : AbstractTableModel() {
        var rows: List<Pair<String, String>> = emptyList()
            set(value) {
                field = value
                fireTableDataChanged()
            }

        override fun getRowCount() = rows.size
        override fun getColumnCount() = 2
        override fun getColumnName(column: Int) = if (column == 0) "Attribute" else "Value"
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any =
            if (columnIndex == 0) rows[rowIndex].first else rows[rowIndex].second
    }

    private inner class LocatorRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val suggestion = locatorModel.rows.getOrNull(table.convertRowIndexToModel(row))
            // The renderer is shared by all cells, so the icon must be reset for every cell.
            icon = if (column == 1 && suggestion?.hint != null) AllIcons.General.Warning else null
            if (!isSelected && column == 3 && suggestion != null) {
                foreground = if (suggestion.isUnique) JBColor(0x208A3C, 0x5FB865) else JBColor(0xC7222D, 0xE55765)
            } else if (!isSelected) {
                foreground = table.foreground
            }
            return c
        }
    }

    private class NodeRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: javax.swing.JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            val node = (value as? DefaultMutableTreeNode)?.userObject as? UiNode
            if (node == null) {
                append(value?.toString() ?: "", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                return
            }
            icon = if (node.children.isEmpty()) AllIcons.Nodes.Tag else AllIcons.Nodes.Folder
            val attributes = if (node.isVisible || node.isRoot) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES
            append(node.type, attributes)
            (node.label ?: node.text ?: node.value)?.let { append("  \"${it.take(40)}\"", SimpleTextAttributes.REGULAR_ATTRIBUTES) }
            (node.identifier ?: node.resourceId?.substringAfter(":id/"))?.let { append("  #$it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            if (!node.isRoot && MobilewrightLocators.derive(node) == null) {
                append("  no locator", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
    }
}
