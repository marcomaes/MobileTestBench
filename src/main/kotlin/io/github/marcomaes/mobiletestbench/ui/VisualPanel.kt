package io.github.marcomaes.mobiletestbench.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.Rect
import io.github.marcomaes.mobiletestbench.core.Size
import io.github.marcomaes.mobiletestbench.core.visual.Baseline
import io.github.marcomaes.mobiletestbench.core.visual.BaselineNames
import io.github.marcomaes.mobiletestbench.core.visual.BaselineStore
import io.github.marcomaes.mobiletestbench.core.visual.DiffOptions
import io.github.marcomaes.mobiletestbench.core.visual.ImageDiff
import io.github.marcomaes.mobiletestbench.settings.MobileTestBenchSettings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.event.ListSelectionEvent

/**
 * Visual regression: save the mirror frame as a named baseline and compare the current (or frozen) frame against it.
 * Everything runs inside the plugin; no device command is needed. Pure logic is in `core/visual`.
 */
class VisualPanel(
    private val project: Project,
    private val currentFrame: () -> BufferedImage?,
    private val logicalSize: () -> Size?,
    private val showOnMirror: (List<Rect>) -> Unit,
    private val foregroundApp: () -> String? = { null },
) : JPanel(BorderLayout()), DeviceToolTab {

    override val title = "Visual"
    override val component: JComponent get() = this

    private var device: Device? = null
    private var visible = false

    /** Bumped on a device change and on dispose: a late list or comparison result is dropped. */
    private var generation = 0

    /** Logical coordinates of the regions of the last comparison; null when there is none. */
    private var lastRegions: List<Rect> = emptyList()

    private val nameField = JTextField(16)
    private val baselineModel = DefaultListModel<Baseline>()
    private val baselineList = JBList(baselineModel).apply {
        visibleRowCount = 5
        cellRenderer = javax.swing.ListCellRenderer { list, value, _, selected, _ ->
            val saved = if (value.info.savedAt > 0) "  (" + SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(value.info.savedAt)) + ")" else ""
            JBLabel(value.name + saved).apply {
                isOpaque = true
                border = JBUI.Borders.empty(1, 4)
                background = if (selected) list.selectionBackground else list.background
                foreground = if (selected) list.selectionForeground else list.foreground
            }
        }
    }
    private val save = JButton("Save baseline")
    private val update = JButton("Update")
    private val rename = JButton("Rename")
    private val delete = JButton("Delete")
    private val refresh = JButton("Refresh")
    private val compare = JButton("Compare")
    private val slider = JSlider(0, 64, MobileTestBenchSettings.getInstance().state.visualTolerance.coerceIn(0, 64))
    private val toleranceLabel = JBLabel()
    private val verdict = JBLabel("Not connected")

    private val ignoreModel = DefaultListModel<Rect>()
    private val ignoreList = JBList(ignoreModel).apply {
        visibleRowCount = 3
        cellRenderer = javax.swing.ListCellRenderer { list, r, _, selected, _ ->
            JBLabel("x=${r.x} y=${r.y} w=${r.width} h=${r.height}").apply {
                isOpaque = true
                background = if (selected) list.selectionBackground else list.background
                foreground = if (selected) list.selectionForeground else list.foreground
            }
        }
    }
    private val ignoreFields = listOf("x", "y", "w", "h").map { JTextField(4) }
    private val addIgnore = JButton("Add")
    private val removeIgnore = JButton("Remove")

    private val images = ImagesView()

    private val store: BaselineStore by lazy {
        val base = project.basePath?.let { File(it, ".mobiletestbench/baselines") } ?: File(PathManager.getSystemPath(), "mobiletestbench/baselines")
        BaselineStore(base)
    }

    init {
        slider.addChangeListener {
            toleranceLabel.text = "Tolerance ${slider.value}"
            if (!slider.valueIsAdjusting) MobileTestBenchSettings.getInstance().state.visualTolerance = slider.value
        }
        toleranceLabel.text = "Tolerance ${slider.value}"
        slider.toolTipText = "Largest per-channel (R, G, B) difference that still counts as equal"

        save.addActionListener { saveBaseline() }
        update.addActionListener { updateBaseline() }
        update.toolTipText = "Replace the selected baseline's image with the current frame (keeps its ignore areas)"
        rename.addActionListener { renameBaseline() }
        delete.addActionListener { deleteBaseline() }
        refresh.addActionListener { reloadList() }
        compare.addActionListener { runCompare() }
        addIgnore.addActionListener { addIgnoreRegion() }
        removeIgnore.addActionListener { removeIgnoreRegion() }
        baselineList.addListSelectionListener { e: ListSelectionEvent -> if (!e.valueIsAdjusting) onSelectionChanged() }
        ignoreList.addListSelectionListener { updateButtons() }

        val saveRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
            add(JBLabel("Name:")); add(nameField); add(save); add(update); add(rename); add(delete); add(refresh)
        }
        val ignoreRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))).apply {
            add(JBLabel("Ignore (device x, y, w, h):"))
            ignoreFields.forEach { add(it) }
            add(addIgnore); add(removeIgnore)
        }
        val compareRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
            add(compare); add(toleranceLabel); add(slider); add(verdict)
        }
        val left = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(saveRow); add(ignoreRow); add(compareRow)
        }
        val lists = JPanel(BorderLayout()).apply {
            add(JBScrollPane(baselineList), BorderLayout.CENTER)
            add(JBScrollPane(ignoreList).apply { preferredSize = java.awt.Dimension(JBUI.scale(180), JBUI.scale(60)) }, BorderLayout.EAST)
            preferredSize = java.awt.Dimension(JBUI.scale(300), JBUI.scale(90))
        }
        val top = JPanel(BorderLayout()).apply {
            add(left, BorderLayout.CENTER)
            add(lists, BorderLayout.EAST)
        }
        add(top, BorderLayout.NORTH)
        add(images, BorderLayout.CENTER)
        updateButtons()
    }

    override fun deviceChanged(device: Device?) {
        if (device?.id == this.device?.id && device != null) return
        this.device = device
        generation++
        baselineModel.clear()
        clearResult(if (device == null) "Not connected" else " ")
        if (device != null && visible) reloadList()
        updateButtons()
    }

    override fun shown() {
        visible = true
        if (device != null && nameField.text.isBlank()) nameField.text = BaselineNames.defaultName(foregroundApp())
        if (device != null) reloadList()
        showOnMirror(lastRegions)
    }

    override fun hidden() {
        visible = false
        showOnMirror(emptyList())
    }

    override fun dispose() {
        generation++
        showOnMirror(emptyList())
    }

    private fun selected(): Baseline? = baselineList.selectedValue

    private fun updateButtons() {
        val connected = device != null
        save.isEnabled = connected
        refresh.isEnabled = connected
        update.isEnabled = connected && selected() != null
        rename.isEnabled = connected && selected() != null
        delete.isEnabled = connected && selected() != null
        compare.isEnabled = connected && selected() != null
        addIgnore.isEnabled = connected && selected() != null
        removeIgnore.isEnabled = selected() != null && ignoreList.selectedIndex >= 0
    }

    private fun clearResult(text: String) {
        lastRegions = emptyList()
        showOnMirror(emptyList())
        images.set(null, null, null)
        verdict.text = text
        verdict.foreground = JBColor.foreground()
    }

    private fun say(text: String, error: Boolean) {
        verdict.text = text
        verdict.foreground = if (error) JBColor.RED else JBColor.foreground()
    }

    private fun reloadList(select: String? = null) {
        val dev = device ?: return
        val gen = generation
        val keep = select ?: selected()?.name
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { store.list(dev.name) }
            SwingUtilities.invokeLater {
                if (gen != generation) return@invokeLater
                result.onSuccess { list ->
                    baselineModel.clear()
                    list.forEach { baselineModel.addElement(it) }
                    val index = list.indexOfFirst { it.name == keep }
                    if (index >= 0) baselineList.selectedIndex = index
                    onSelectionChanged()
                }.onFailure { say("Could not read the baselines: ${it.message}", true) }
            }
        }
    }

    private fun onSelectionChanged() {
        ignoreModel.clear()
        selected()?.info?.ignoreRects?.forEach { ignoreModel.addElement(it) }
        updateButtons()
    }

    private fun saveBaseline() {
        val dev = device ?: return
        val frame = currentFrame()
        if (frame == null) { say("There is no frame to save yet", true); return }
        val name = nameField.text.trim().ifEmpty { BaselineNames.defaultName(foregroundApp()) }
        if (baselineModel.elements().asSequence().any { it.name == BaselineNames.sanitize(name) } &&
            Messages.showYesNoDialog(project, "A baseline named \"$name\" already exists. Replace its image with the current frame?", "Replace Baseline", Messages.getWarningIcon()) != Messages.YES
        ) return
        val logical = logicalSize()
        val gen = generation
        say("Saving...", false)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { store.save(dev.name, name, frame, logical) }
            SwingUtilities.invokeLater {
                if (gen != generation) return@invokeLater
                result.onSuccess { saved ->
                    say("Saved baseline \"${saved.name}\" (${frame.width}x${frame.height}) in ${saved.png.parentFile}", false)
                    nameField.text = saved.name
                    reloadList(saved.name)
                }.onFailure { say("Could not save: ${it.message}", true) }
            }
        }
    }

    private fun updateBaseline() {
        val dev = device ?: return
        val b = selected() ?: return
        val frame = currentFrame()
        if (frame == null) { say("There is no frame to save yet", true); return }
        if (Messages.showYesNoDialog(project, "Replace the image of \"${b.name}\" with the current frame? Its ignore areas are kept.", "Update Baseline", Messages.getWarningIcon()) != Messages.YES) return
        val logical = logicalSize()
        val gen = generation
        say("Updating...", false)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { store.replaceImage(b, frame, logical) }
            SwingUtilities.invokeLater {
                if (gen != generation) return@invokeLater
                result.onSuccess { saved ->
                    clearResult("Updated baseline \"${saved.name}\" (${frame.width}x${frame.height})")
                    reloadList(saved.name)
                }.onFailure { say("Could not update: ${it.message}", true) }
            }
        }
    }

    private fun renameBaseline() {
        val b = selected() ?: return
        val existing = baselineModel.elements().asSequence().map { it.name }.toList()
        val raw = Messages.showInputDialog(
            project, "New name for \"${b.name}\":", "Rename Baseline", null, b.name,
            object : com.intellij.openapi.ui.InputValidator {
                override fun checkInput(inputString: String?) = BaselineNames.renameError(inputString.orEmpty(), b.name, existing) == null
                override fun canClose(inputString: String?) = checkInput(inputString)
            },
        ) ?: return
        val gen = generation
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { store.rename(b, raw) }
            SwingUtilities.invokeLater {
                if (gen != generation) return@invokeLater
                result.onSuccess { renamed ->
                    say("Renamed \"${b.name}\" to \"${renamed.name}\"", false)
                    reloadList(renamed.name)
                }.onFailure { say("Could not rename: ${it.message}", true) }
            }
        }
    }

    private fun deleteBaseline() {
        val b = selected() ?: return
        val gen = generation
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { store.delete(b) }
            SwingUtilities.invokeLater {
                if (gen != generation) return@invokeLater
                result.onSuccess { say("Deleted \"${b.name}\"", false); clearResult("Deleted \"${b.name}\""); reloadList() }
                    .onFailure { say("Could not delete: ${it.message}", true) }
            }
        }
    }

    private fun addIgnoreRegion() {
        val b = selected() ?: return
        val rect = BaselineNames.parseRect(ignoreFields.joinToString(",") { it.text.trim() })
        if (rect == null) { say("Ignore area: enter x, y, width and height as whole numbers (width and height above 0)", true); return }
        saveIgnore(b, b.info.ignoreRects + rect)
        ignoreFields.forEach { it.text = "" }
    }

    private fun removeIgnoreRegion() {
        val b = selected() ?: return
        val index = ignoreList.selectedIndex
        if (index < 0) return
        saveIgnore(b, b.info.ignoreRects.filterIndexed { i, _ -> i != index })
    }

    private fun saveIgnore(b: Baseline, rects: List<Rect>) {
        val gen = generation
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { store.updateIgnore(b, rects) }
            SwingUtilities.invokeLater {
                if (gen != generation) return@invokeLater
                result.onSuccess { updated ->
                    val index = baselineModel.indexOf(b)
                    if (index >= 0) {
                        baselineModel.set(index, updated)
                        baselineList.selectedIndex = index
                    }
                    onSelectionChanged()
                }.onFailure { say("Could not save the ignore areas: ${it.message}", true) }
            }
        }
    }

    private fun runCompare() {
        val b = selected() ?: return
        val frame = currentFrame()
        if (frame == null) { say("There is no frame to compare yet", true); return }
        val logical = logicalSize()
        val options = DiffOptions(tolerance = slider.value)
        val gen = generation
        say("Comparing...", false)
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = runCatching { diff(b, frame, logical, options) }
            SwingUtilities.invokeLater {
                if (gen != generation) return@invokeLater
                outcome.onSuccess { show(it) }.onFailure { say("Could not compare: ${it.message}", true) }
            }
        }
    }

    private class Outcome(val message: String, val bad: Boolean, val base: BufferedImage?, val current: BufferedImage?, val diff: BufferedImage?, val regions: List<Rect>)

    private fun diff(b: Baseline, frame: BufferedImage, logical: Size?, options: DiffOptions): Outcome {
        val base = store.load(b)
        if (base.width != frame.width || base.height != frame.height) {
            return Outcome(
                "Not compared: the baseline is ${base.width}x${base.height} but the current frame is ${frame.width}x${frame.height} " +
                    "(rotated, another device or another scale). Save a new baseline for this screen size.",
                true, base, frame, null, emptyList(),
            )
        }
        val frameSize = Size(frame.width, frame.height)
        val baseLogical = b.info.logicalSize.takeUnless { it.isEmpty } ?: logical ?: frameSize
        val ignore = b.info.ignoreRects.map { ImageDiff.scale(it, baseLogical, frameSize) }
        val result = ImageDiff.compare(base, frame, options, ignore)!!
        val target = logical ?: frameSize
        val regions = result.regions.map { ImageDiff.scale(it, frameSize, target) }
        return Outcome(result.verdict(), false, base, frame, ImageDiff.render(frame, result), regions)
    }

    private fun show(o: Outcome) {
        images.set(o.base, o.current, o.diff)
        lastRegions = o.regions
        if (visible) showOnMirror(o.regions)
        say(o.message, o.bad)
    }

    /** Baseline | current | diff, each scaled to fit its third of the width. */
    private class ImagesView : JPanel() {
        private var base: BufferedImage? = null
        private var current: BufferedImage? = null
        private var diff: BufferedImage? = null

        fun set(base: BufferedImage?, current: BufferedImage?, diff: BufferedImage?) {
            this.base = base; this.current = current; this.diff = diff
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            val pad = JBUI.scale(6)
            val caption = g2.fontMetrics.height + pad
            val cell = width / 3
            listOf("Baseline" to base, "Current" to current, "Diff" to diff).forEachIndexed { i, (label, img) ->
                val x0 = i * cell
                g2.color = JBColor.foreground()
                g2.drawString(label, x0 + pad, g2.fontMetrics.ascent + 2)
                if (img == null) return@forEachIndexed
                val availW = cell - 2 * pad
                val availH = height - caption - pad
                if (availW <= 0 || availH <= 0) return@forEachIndexed
                val s = minOf(availW.toDouble() / img.width, availH.toDouble() / img.height)
                val w = (img.width * s).toInt().coerceAtLeast(1)
                val h = (img.height * s).toInt().coerceAtLeast(1)
                g2.drawImage(img, x0 + pad, caption, w, h, null)
                g2.color = Color.GRAY
                g2.drawRect(x0 + pad, caption, w, h)
            }
        }
    }
}
