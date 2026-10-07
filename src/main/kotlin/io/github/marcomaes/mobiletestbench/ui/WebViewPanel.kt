package io.github.marcomaes.mobiletestbench.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import io.github.marcomaes.mobiletestbench.DeviceServices
import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.Rect
import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import io.github.marcomaes.mobiletestbench.core.webview.DomNode
import io.github.marcomaes.mobiletestbench.core.webview.DomPage
import io.github.marcomaes.mobiletestbench.core.webview.DomTree
import io.github.marcomaes.mobiletestbench.core.webview.LiveCheck
import io.github.marcomaes.mobiletestbench.core.webview.WebLocator
import io.github.marcomaes.mobiletestbench.core.webview.WebLocators
import io.github.marcomaes.mobiletestbench.core.webview.WebRectMapper
import io.github.marcomaes.mobiletestbench.core.webview.WebViewEval
import io.github.marcomaes.mobiletestbench.core.webview.WebViewInfo
import io.github.marcomaes.mobiletestbench.core.webview.WebViews
import io.github.marcomaes.mobiletestbench.core.webview.matchText
import java.awt.BorderLayout
import java.awt.Font
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.DefaultListCellRenderer

/**
 * Inspects the web views of the foreground app (`mobilecli webview ...`): a DOM tree, CSS/XPath locators with live match counts, node
 * attributes, a highlight of the selected element on the mirror and a small JavaScript console. Works for debug builds only (mobilecli
 * injects an agent into the app). Pure logic in `core/webview`; every call runs on a pooled thread and a late result of another device,
 * web view, selection or run is dropped through the generation counters.
 */
class WebViewPanel(
    private val highlight: (Rect?) -> Unit,
    private val nativeHierarchy: () -> UiNode?,
) : JPanel(BorderLayout()), DeviceToolTab {

    override val title = "WebView"
    override val component: JComponent get() = this

    override fun deviceChanged(device: Device?) {
        this.device = device
        deviceGen++
        listGen++
        treeGen++
        selGen++
        evalGen++
        loaded = false
        webViews = emptyList()
        current = null
        page = null
        selected = null
        updating = true
        combo.model = DefaultComboBoxModel()
        updating = false
        treeModel.setRoot(null)
        clearSelectionView()
        clearHighlight()
        owner.text = " "
        output.text = ""
        setStatus(if (device == null) "Not connected" else "Press Refresh to list the web views of the foreground app", false)
        updateButtons()
        if (device != null && visible) refresh()
    }

    override fun shown() {
        visible = true
        if (device != null && !loaded) refresh() else selected?.let { onSelected(it) }
    }

    override fun hidden() {
        visible = false
        clearHighlight()
    }

    override fun dispose() {
        deviceGen++
        listGen++
        treeGen++
        selGen++
        evalGen++
        clearHighlight()
    }

    private var device: Device? = null
    private var visible = false
    private var loaded = false
    private var updating = false
    private var webViews: List<WebViewInfo> = emptyList()
    private var current: WebViewInfo? = null
    private var page: DomPage? = null
    private var selected: DomNode? = null
    private var highlighted = false

    @Volatile private var deviceGen = 0
    @Volatile private var listGen = 0
    @Volatile private var treeGen = 0
    @Volatile private var selGen = 0
    @Volatile private var evalGen = 0

    private val refreshButton = JButton("Refresh")
    private val loadButton = JButton("Reload DOM")
    private val combo = JComboBox<WebViewInfo>()
    private val showNonVisual = JBCheckBox("Show head, script and style nodes")
    private val status = JBLabel(" ")
    private val owner = JBLabel(" ").apply { foreground = JBColor.GRAY }

    private val treeModel = DefaultTreeModel(null)
    private val tree = Tree(treeModel).apply {
        isRootVisible = true
        showsRootHandles = true
        cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(tree: javax.swing.JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
                val n = (value as? DefaultMutableTreeNode)?.userObject as? DomNode ?: return
                append(n.label, if (n.isNonVisual) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (n.text.isNotEmpty()) append("  \"${n.text.take(40)}\"", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }

    private val cssField = readOnlyField()
    private val xpathField = readOnlyField()
    private val cssMatch = JBLabel(" ")
    private val xpathMatch = JBLabel(" ")
    private val cssCopy = JButton("Copy")
    private val xpathCopy = JButton("Copy")
    private val attrModel = DefaultTableModel(arrayOf("Attribute", "Value"), 0)
    private val attrTable = JBTable(attrModel).apply { setShowGrid(false) }
    private val selectedNote = JBLabel(" ").apply { foreground = JBColor.GRAY }

    private val input = JBTextArea(4, 40).apply { font = Font(Font.MONOSPACED, Font.PLAIN, font.size); lineWrap = true }
    private val run = JButton("Run")
    private val output = JBTextArea().apply { font = Font(Font.MONOSPACED, Font.PLAIN, font.size); isEditable = false; lineWrap = true }

    init {
        border = JBUI.Borders.empty(2)
        val top = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.empty(2, 4)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { add(refreshButton) }, BorderLayout.WEST)
            add(combo, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply { add(loadButton) }, BorderLayout.EAST)
        }
        val statusRows = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 8)
            add(status, BorderLayout.NORTH)
            add(owner, BorderLayout.SOUTH)
        }
        add(JPanel(BorderLayout()).apply { add(top, BorderLayout.NORTH); add(statusRows, BorderLayout.SOUTH) }, BorderLayout.NORTH)

        val left = JPanel(BorderLayout()).apply {
            add(JBScrollPane(tree), BorderLayout.CENTER)
            add(showNonVisual, BorderLayout.SOUTH)
        }
        val locators = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(2, 6)
            add(locatorRow("CSS", cssField, cssMatch, cssCopy))
            add(locatorRow("XPath", xpathField, xpathMatch, xpathCopy))
            add(selectedNote.apply { alignmentX = LEFT_ALIGNMENT })
        }
        val details = JPanel(BorderLayout()).apply {
            add(locators, BorderLayout.NORTH)
            add(JBScrollPane(attrTable), BorderLayout.CENTER)
        }
        val console = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            border = JBUI.Borders.empty(2, 6)
            add(JBLabel("JavaScript console (runs in the page: it can change the app; Ctrl/Cmd+Enter runs). Return primitives or JSON.stringify(value)."), BorderLayout.NORTH)
            val center = OnePixelSplitter(true, 0.4f).apply {
                firstComponent = JBScrollPane(input)
                secondComponent = JBScrollPane(output)
            }
            add(center, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(run) }, BorderLayout.SOUTH)
        }
        val right = OnePixelSplitter(true, 0.5f).apply { firstComponent = details; secondComponent = console }
        add(OnePixelSplitter(false, 0.4f).apply { firstComponent = left; secondComponent = right }, BorderLayout.CENTER)

        combo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): java.awt.Component {
                val c = super.getListCellRendererComponent(list, (value as? WebViewInfo)?.label ?: "No web view", index, isSelected, cellHasFocus)
                return c
            }
        }
        combo.addActionListener {
            if (updating) return@addActionListener
            val info = combo.selectedItem as? WebViewInfo ?: return@addActionListener
            if (info !== current) {
                current = info
                loadDom()
            }
        }
        refreshButton.addActionListener { refresh() }
        loadButton.addActionListener { loadDom() }
        showNonVisual.addActionListener { page?.let { showTree(it) } }
        tree.addTreeSelectionListener { onSelected((tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? DomNode) }
        cssCopy.addActionListener { copy(cssField.text) }
        xpathCopy.addActionListener { copy(xpathField.text) }
        run.addActionListener { runScript() }
        val shortcut = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
        input.inputMap.put(shortcut, "run-js")
        input.actionMap.put("run-js", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = runScript()
        })
        setStatus("Not connected", false)
        updateButtons()
    }

    private fun readOnlyField() = JTextField().apply { isEditable = false; font = Font(Font.MONOSPACED, Font.PLAIN, font.size) }

    private fun locatorRow(label: String, field: JTextField, match: JBLabel, copy: JButton) = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        alignmentX = LEFT_ALIGNMENT
        add(JBLabel(label).apply { preferredSize = java.awt.Dimension(JBUI.scale(44), preferredSize.height) }, BorderLayout.WEST)
        add(field, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { add(match); add(copy) }, BorderLayout.EAST)
        maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height + JBUI.scale(4))
    }

    private fun copy(text: String) {
        if (text.isBlank()) return
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        setStatus("Copied: $text", false)
    }

    private fun setStatus(text: String, error: Boolean) {
        status.text = "<html>" + text.replace("&", "&amp;").replace("<", "&lt;") + "</html>"
        status.foreground = if (error) JBColor.RED else JBColor.foreground()
    }

    private fun updateButtons() {
        val on = device != null
        refreshButton.isEnabled = on
        loadButton.isEnabled = on && current != null
        run.isEnabled = on && current != null
    }

    private fun clearHighlight() {
        if (highlighted) highlight(null)
        highlighted = false
    }

    private fun clearSelectionView() {
        cssField.text = ""
        xpathField.text = ""
        cssMatch.text = " "
        xpathMatch.text = " "
        attrModel.rowCount = 0
        selectedNote.text = " "
    }

    // list

    private fun refresh(retried: Boolean = false) {
        val dev = device ?: return
        val my = ++listGen
        val dg = deviceGen
        loaded = true
        refreshButton.isEnabled = false
        setStatus("Listing web views...", false)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { WebViews.parseList(DeviceServices.mobilecliCall(dev, "webview", "list")) }
            SwingUtilities.invokeLater {
                if (my != listGen || dg != deviceGen) return@invokeLater
                refreshButton.isEnabled = true
                result.onFailure {
                    webViews = emptyList()
                    current = null
                    setCombo(emptyList(), null)
                    treeGen++
                    treeModel.setRoot(null)
                    page = null
                    setStatus(WebViews.describeError(it.message ?: "webview list failed"), true)
                    updateButtons()
                }.onSuccess { list ->
                    val previous = current
                    webViews = list
                    // Ids change when the screen is re-entered, so a web view is recognised by url, title and app.
                    val pick = list.firstOrNull { previous != null && it.url == previous.url && it.title == previous.title && it.bundleId == previous.bundleId }
                        ?: list.firstOrNull { it.visible } ?: list.firstOrNull()
                    current = pick
                    setCombo(list, pick)
                    if (pick == null) {
                        treeGen++
                        treeModel.setRoot(null)
                        page = null
                        setStatus("No web view in the foreground app. Open a screen that shows one (debug builds only), then press Refresh.", false)
                        owner.text = " "
                    } else {
                        setStatus("${list.size} web view${if (list.size == 1) "" else "s"}", false)
                        loadDom(retried)
                    }
                    updateButtons()
                }
            }
        }
    }

    private fun setCombo(list: List<WebViewInfo>, pick: WebViewInfo?) {
        updating = true
        combo.model = DefaultComboBoxModel(list.toTypedArray())
        combo.selectedItem = pick
        updating = false
    }

    // DOM

    private fun loadDom(retried: Boolean = false) {
        val dev = device ?: return
        val info = current ?: return
        val my = ++treeGen
        val dg = deviceGen
        selGen++
        selected = null
        clearSelectionView()
        clearHighlight()
        loadButton.isEnabled = false
        setStatus("Loading the DOM...", false)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val args = WebViewEval.args(info.id, DomTree.script()).toTypedArray()
                DomTree.parse(DeviceServices.mobilecliCall(dev, *args))
            }
            // The native element with the same rect owns the web view (best effort, never fails the load).
            val ownerText = if (result.isSuccess) info.bounds?.let { b ->
                runCatching { nativeHierarchy()?.let { root -> WebViews.nativeOwner(root, b)?.let(WebViews::describeOwner) } }.getOrNull()
            } else null
            SwingUtilities.invokeLater {
                if (my != treeGen || dg != deviceGen) return@invokeLater
                loadButton.isEnabled = true
                result.onFailure {
                    val raw = it.message ?: "webview eval failed"
                    page = null
                    treeModel.setRoot(null)
                    // One automatic re-list per user action; a second "not found" is shown as is instead of looping.
                    if (WebViews.isNotFound(raw) && !retried) {
                        setStatus("The web view is gone (its id changes when the screen is left). Listing again...", true)
                        refresh(retried = true)
                    } else setStatus(WebViews.describeError(raw), true)
                }.onSuccess { p ->
                    page = p
                    showTree(p)
                    setStatus("DOM of ${info.title.ifBlank { info.url }}: ${p.note}${if (p.truncated) ". Larger pages are cut so the tab stays fast." else ""}", false)
                }
                val b = info.bounds
                owner.text = when {
                    b == null -> " "
                    ownerText != null -> "Native element: $ownerText"
                    else -> "Web view bounds ${b.x},${b.y} ${b.width}x${b.height}" + (if (device?.platform == io.github.marcomaes.mobiletestbench.core.Platform.IOS) " (points); the native tree has no web view node, so only the bounds are known" else "; no native element with the same bounds found")
                }
            }
        }
    }

    private fun showTree(p: DomPage) {
        val withHidden = showNonVisual.isSelected
        fun build(n: DomNode): DefaultMutableTreeNode {
            val t = DefaultMutableTreeNode(n)
            n.children.filter { withHidden || !it.isNonVisual }.forEach { t.add(build(it)) }
            return t
        }
        val root = build(p.root)
        treeModel.setRoot(root)
        // Open html and body so the page content is visible straight away.
        tree.expandPath(TreePath(root.path))
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i) as DefaultMutableTreeNode
            tree.expandPath(TreePath(c.path))
        }
    }

    // selection

    private fun onSelected(node: DomNode?) {
        selected = node
        val my = ++selGen
        clearHighlight()
        clearSelectionView()
        val p = page ?: return
        if (node == null) return
        val css = WebLocators.cssSelector(node, p.root)
        val xp = WebLocators.xpath(node, p.root)
        showLocators(css, xp, null)
        attrModel.rowCount = 0
        if (node.text.isNotEmpty()) attrModel.addRow(arrayOf("(own text)", node.text))
        node.attributes.forEach { (k, v) -> attrModel.addRow(arrayOf(k, v)) }
        val dev = device ?: return
        val info = current ?: return
        val dg = deviceGen
        ApplicationManager.getApplication().executeOnPooledThread {
            // One eval for both counts and the current rect (the page may have scrolled since the DOM was loaded).
            val result = runCatching {
                val args = WebViewEval.args(info.id, LiveCheck.script(css.value, xp.value, node.path)).toTypedArray()
                LiveCheck.parse(DeviceServices.mobilecliCall(dev, *args))
            }
            SwingUtilities.invokeLater {
                if (my != selGen || dg != deviceGen) return@invokeLater
                result.onFailure {
                    selectedNote.text = "Live check failed: " + WebViews.describeError(it.message ?: "")
                }.onSuccess { live ->
                    showLocators(css, xp, live)
                    val b = info.bounds
                    val rect = live.rect?.let { r -> b?.let { WebRectMapper.toDevice(r, live.viewportWidth, it) } }
                    val shown = if (rect != null && b != null) WebRectMapper.clip(rect, b) else rect
                    if (rect != null && shown == null) {
                        selectedNote.text = "The element is outside the visible part of the web view"
                    } else if (shown != null && visible) {
                        highlight(shown)
                        highlighted = true
                        selectedNote.text = "Highlighted on the mirror: ${shown.x},${shown.y} ${shown.width}x${shown.height}"
                    } else {
                        selectedNote.text = if (live.rect == null) "The element is no longer in the page" else "Not shown on the mirror (the element has no size, so it is not rendered)"
                    }
                }
            }
        }
    }

    private fun showLocators(css: WebLocator, xp: WebLocator, live: LiveCheck?) {
        cssField.text = css.value
        xpathField.text = xp.value
        cssField.caretPosition = 0
        xpathField.caretPosition = 0
        // The live count is the page's own answer; before it arrives the count in the loaded DOM is shown.
        val cssText = if (live != null) matchText(live.cssCount, live.cssError) else "${matchText(css.domCount)} in the loaded DOM"
        val xpText = if (live != null) matchText(live.xpathCount, live.xpathError) else "${matchText(xp.domCount)} in the loaded DOM"
        cssMatch.text = cssText
        xpathMatch.text = xpText
        cssMatch.foreground = matchColor(if (live != null) live.cssCount else css.domCount, live?.cssError)
        xpathMatch.foreground = matchColor(if (live != null) live.xpathCount else xp.domCount, live?.xpathError)
    }

    private fun matchColor(count: Int?, error: String?) = when {
        error != null || count == 0 -> JBColor.RED
        count == 1 -> JBColor(java.awt.Color(0x208A3C), java.awt.Color(0x5FB865))
        else -> JBColor.ORANGE
    }

    // console

    private fun runScript() {
        val dev = device ?: return
        val info = current ?: return
        val code = input.text
        if (code.isBlank()) return
        val my = ++evalGen
        val dg = deviceGen
        run.isEnabled = false
        output.foreground = JBColor.GRAY
        output.text = "Running..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = runCatching { WebViewEval.format(DeviceServices.mobilecliCall(dev, *WebViewEval.args(info.id, code).toTypedArray())) }
                .getOrElse { WebViewEval.formatError(it.message ?: "webview eval failed") }
            SwingUtilities.invokeLater {
                if (my != evalGen || dg != deviceGen) return@invokeLater
                run.isEnabled = true
                output.foreground = if (outcome.error) JBColor.RED else JBColor.foreground()
                output.text = outcome.text
                output.caretPosition = 0
                if (outcome.error && WebViews.isNotFound(outcome.text)) setStatus("The web view is gone. Press Refresh.", true)
            }
        }
    }
}
