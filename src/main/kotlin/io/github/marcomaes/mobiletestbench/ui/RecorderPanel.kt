package io.github.marcomaes.mobiletestbench.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.recording.RecorderOutput
import io.github.marcomaes.mobiletestbench.core.recording.ReplayOptions
import io.github.marcomaes.mobiletestbench.core.recording.ScriptPlayer
import io.github.marcomaes.mobiletestbench.core.recording.ScriptRecorder
import io.github.marcomaes.mobiletestbench.settings.MobileTestBenchSettings
import io.github.marcomaes.mobiletestbench.core.recording.ScrollUntilVisible as ScrollConversion
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BoxLayout
import java.awt.datatransfer.StringSelection
import javax.swing.DefaultListModel
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.ListSelectionModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JToggleButton

/** The recorded steps as Mobilewright or Appium code (Output combo), with copy, insert, clear and replay (the running step is highlighted). */
class RecorderPanel(
    private val project: Project,
    private val onClear: () -> Unit,
    /** Replays the steps in the range (indices in the list); null replays all of them. */
    private val onReplay: (IntRange?) -> Unit,
    private val onStop: () -> Unit,
    private val onAssert: (Boolean) -> Unit,
    private val onScrollUntilVisible: (List<Int>) -> Unit = {},
    /** Sets the own timeout (ms) of the wait and expect steps at the rows; null removes it. */
    private val onSetTimeout: (List<Int>, Long?) -> Unit = { _, _ -> },
    /** Sets direction and swipe limit of the scroll-until-visible steps at the rows. */
    private val onSetScroll: (List<Int>, String, Int) -> Unit = { _, _, _ -> },
) : JPanel(BorderLayout()) {

    private class Row(val step: ScriptRecorder.Step, var state: ScriptPlayer.State? = null)

    private var replaying = false
    private var platform = Platform.ANDROID
    private val outputBox = ComboBox(RecorderOutput.entries.toTypedArray()).apply {
        selectedItem = RecorderOutput.fromName(MobileTestBenchSettings.getInstance().state.recorderOutput)
        toolTipText = "What the steps are written as. Replay is not affected."
        addActionListener {
            MobileTestBenchSettings.getInstance().state.recorderOutput = output.name
            list.repaint()
        }
    }
    private val output: RecorderOutput get() = outputBox.selectedItem as? RecorderOutput ?: RecorderOutput.MOBILEWRIGHT
    private val model = DefaultListModel<Row>()
    private val list = JBList(model)
    private val status = JBLabel(" ").apply { border = JBUI.Borders.empty(2, 8) }
    private val replay = JButton("Replay").apply { addActionListener { onReplay(null) } }
    private val replayFrom = JButton("Replay from Step").apply {
        toolTipText = "Replays the selected step and everything after it"
        isEnabled = false
        addActionListener { replayFromSelected() }
    }
    private val replaySelected = JButton("Replay Selected").apply {
        toolTipText = "Replays only the selected steps"
        isEnabled = false
        addActionListener { replaySelectedSteps() }
    }
    private val assert = JToggleButton("Assert").apply {
        toolTipText = "Click an element on the screen to add an assertion about it"
        isEnabled = false
        addActionListener {
            status.text = if (isSelected) "Click an element on the screen to assert on it" else " "
            onAssert(isSelected)
        }
    }
    private val scroll = JButton("Scroll Until Visible").apply {
        toolTipText = SCROLL_TEXT + ": select the swipe steps, then press"
        isEnabled = false
        addActionListener { requestScroll() }
    }
    private val stop = JButton("Stop").apply {
        isVisible = false
        addActionListener { onStop() }
    }

    init {
        list.emptyText.text = "Press Record, then use the device"
        list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        list.addListSelectionListener { updateScrollButton(); updateReplayButtons() }
        list.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = popup(e)
            override fun mouseReleased(e: MouseEvent) = popup(e)

            private fun popup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val row = list.locationToIndex(e.point)
                if (row >= 0 && list.getCellBounds(row, row)?.contains(e.point) == true && !list.isSelectedIndex(row)) list.selectedIndex = row
                JPopupMenu().apply {
                    add(JMenuItem(SCROLL_TEXT).apply {
                        isEnabled = scroll.isEnabled
                        addActionListener { requestScroll() }
                    })
                    add(JMenuItem("Set scroll direction and limit...").apply {
                        isEnabled = canSetScroll()
                        addActionListener { askScroll() }
                    })
                    addSeparator()
                    add(JMenuItem("Replay from this step").apply {
                        isEnabled = replayFrom.isEnabled
                        addActionListener { replayFromSelected() }
                    })
                    add(JMenuItem("Replay selected steps").apply {
                        isEnabled = replaySelected.isEnabled
                        addActionListener { replaySelectedSteps() }
                    })
                    addSeparator()
                    add(JMenuItem("Set timeout...").apply {
                        isEnabled = canSetTimeout()
                        addActionListener { askTimeout() }
                    })
                }.show(list, e.x, e.y)
            }
        })
        list.cellRenderer = object : ColoredListCellRenderer<Row>() {
            override fun customizeCellRenderer(list: JList<out Row>, value: Row, index: Int, selected: Boolean, hasFocus: Boolean) {
                icon = when (value.state) {
                    ScriptPlayer.State.RUNNING -> AllIcons.Actions.Play_forward
                    ScriptPlayer.State.PASSED -> AllIcons.RunConfigurations.TestPassed
                    ScriptPlayer.State.FAILED -> AllIcons.RunConfigurations.TestFailed
                    ScriptPlayer.State.SKIPPED -> AllIcons.RunConfigurations.TestSkipped
                    null -> null
                }
                val attributes = when (value.state) {
                    ScriptPlayer.State.FAILED -> SimpleTextAttributes.ERROR_ATTRIBUTES
                    ScriptPlayer.State.SKIPPED -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                    ScriptPlayer.State.RUNNING -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                    else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                }
                append(output.summary(value.step, platform), attributes)
            }
        }
        val copy = JButton("Copy").apply {
            addActionListener {
                CopyPasteManager.getInstance().setContents(StringSelection(code()))
                status.text = "Copied"
            }
        }
        val insert = JButton("Insert at Caret").apply {
            addActionListener { status.text = EditorInsert.insertAtCaret(project, code()) ?: "Inserted" }
        }
        val clear = JButton("Clear").apply { addActionListener { onClear() } }
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4, 4, 0, 4)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                add(JBLabel("Output:"))
                add(outputBox)
                add(copy)
                add(insert)
                add(clear)
                add(assert)
                add(scroll)
            })
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                add(replay)
                add(replayFrom)
                add(replaySelected)
                add(stop)
            })
        }, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
    }

    private fun requestScroll() {
        val rows = list.selectedIndices.toList()
        if (rows.isNotEmpty()) onScrollUntilVisible(rows)
    }

    private fun replayFromSelected() {
        ReplayOptions.fromSelected(list.selectedIndices.toList(), model.size())?.let { onReplay(it) }
    }

    private fun replaySelectedSteps() {
        ReplayOptions.selectedRange(list.selectedIndices.toList(), model.size())?.let { onReplay(it) }
    }

    private fun updateReplayButtons() {
        val usable = replay.isEnabled && !replaying && list.selectedIndices.isNotEmpty()
        replayFrom.isEnabled = usable
        replaySelected.isEnabled = usable
    }

    private fun canSetTimeout() = !replaying && list.selectedIndices.isNotEmpty() && list.selectedValuesList.all { ReplayOptions.supportsTimeout(it.step) }

    /** Asks for seconds (empty = remove the timeout) and applies it to the selected wait and expect steps. */
    private fun askTimeout() {
        if (!canSetTimeout()) return
        val rows = list.selectedIndices.toList()
        val current = list.selectedValuesList.firstNotNullOfOrNull { ReplayOptions.timeoutOf(it.step) }
        val text = Messages.showInputDialog(
            project,
            "Timeout in seconds for the selected wait and expect steps (${ReplayOptions.STEP_TIMEOUT_SECONDS.first} to ${ReplayOptions.STEP_TIMEOUT_SECONDS.last}). Leave empty to use the replay timeout from the settings.",
            "Set Timeout",
            null,
            current?.let { ReplayOptions.wholeSeconds(it).toString() }.orEmpty(),
            object : InputValidator {
                override fun checkInput(inputString: String?) = inputString.isNullOrBlank() || ReplayOptions.parseStepSeconds(inputString) != null
                override fun canClose(inputString: String?) = checkInput(inputString)
            },
        ) ?: return
        val seconds = if (text.isBlank()) null else ReplayOptions.parseStepSeconds(text) ?: return
        onSetTimeout(rows, seconds?.let { it * 1000L })
    }

    private fun canSetScroll() = !replaying && list.selectedIndices.isNotEmpty() && list.selectedValuesList.all { it.step is ScriptRecorder.ScrollUntilVisible }

    /** Asks for direction and swipe limit and applies them to the selected scroll steps. */
    private fun askScroll() {
        if (!canSetScroll()) return
        val rows = list.selectedIndices.toList()
        val first = list.selectedValuesList.first().step as ScriptRecorder.ScrollUntilVisible
        val direction = ComboBox(ScrollConversion.DIRECTIONS.toTypedArray()).apply { selectedItem = first.direction }
        val limit = javax.swing.JSpinner(javax.swing.SpinnerNumberModel(first.maxSwipes, ScrollConversion.LIMIT_RANGE.first, ScrollConversion.LIMIT_RANGE.last, 1))
        val form = JPanel(java.awt.GridLayout(2, 2, 8, 4)).apply {
            add(javax.swing.JLabel("Swipe direction")); add(direction)
            add(javax.swing.JLabel("Max swipes")); add(limit)
        }
        val ok = javax.swing.JOptionPane.showConfirmDialog(this, form, "Scroll Until Visible", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE)
        if (ok != javax.swing.JOptionPane.OK_OPTION) return
        onSetScroll(rows, direction.selectedItem as String, limit.value as Int)
    }

    private fun updateScrollButton() {
        scroll.isEnabled = !replaying && list.selectedIndices.isNotEmpty() && list.selectedValuesList.all { it.step is ScriptRecorder.Swipe }
    }

    private fun code(): String = output.render(model.elements().toList().map { it.step }, platform)

    /** Replaces the list; [recording] only changes the hint text. */
    fun setSteps(steps: List<ScriptRecorder.Step>, recording: Boolean, platform: Platform? = null) {
        if (platform != null) this.platform = platform
        model.clear()
        steps.forEach { model.addElement(Row(it)) }
        list.emptyText.text = if (recording) "Recording: tap, swipe and type on the device" else "Press Record, then use the device"
        status.text = if (recording) "Recording..." else " "
        replay.isEnabled = steps.isNotEmpty() && !recording
        updateReplayButtons()
        assert.isEnabled = recording
        if (!recording) assert.isSelected = false
        updateScrollButton()
        if (recording && model.size() > 0) list.ensureIndexIsVisible(model.size() - 1)
    }

    fun setAssertArmed(armed: Boolean) {
        assert.isSelected = armed
    }

    fun showMessage(text: String) {
        status.text = text
    }

    fun setReplaying(replaying: Boolean) {
        replay.isVisible = !replaying
        replayFrom.isVisible = !replaying
        replaySelected.isVisible = !replaying
        stop.isVisible = replaying
        this.replaying = replaying
        updateScrollButton()
        updateReplayButtons()
        if (replaying) {
            (0 until model.size()).forEach { model[it].state = null }
            list.repaint()
        }
    }

    /** Shows how far the replay is: highlights the running step and marks the finished ones. */
    fun setStepState(index: Int, state: ScriptPlayer.State, message: String?) {
        val row = model.getElementAt(index) ?: return
        row.state = state
        model.setElementAt(row, index)
        if (state == ScriptPlayer.State.RUNNING) {
            list.selectedIndex = index
            list.ensureIndexIsVisible(index)
            status.text = "Replaying step ${index + 1} of ${model.size()}"
        }
        if (state == ScriptPlayer.State.FAILED) status.text = "Step ${index + 1} failed: ${message.orEmpty()}"
    }

    fun replayFinished(passed: Boolean, stopped: Boolean) {
        if (passed) status.text = "Replay finished: all steps passed"
        else if (stopped) status.text = "Replay stopped"
    }

    private companion object {
        const val SCROLL_TEXT = "Scroll until the next element is visible"
    }
}
