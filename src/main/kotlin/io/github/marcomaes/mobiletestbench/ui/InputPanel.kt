package io.github.marcomaes.mobiletestbench.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import io.github.marcomaes.mobiletestbench.DeviceServices
import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.input.HardwareButton
import io.github.marcomaes.mobiletestbench.core.input.InputActions
import io.github.marcomaes.mobiletestbench.core.input.InputKey
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Hardware buttons, special keys, soft keyboard hiding and the clipboard of the connected device. Each action runs on a pooled
 * thread and the status line shows its exact result or the tool's own error. Pure logic is in `core/input`.
 */
class InputPanel : JPanel(BorderLayout()), DeviceToolTab {

    override val title = "Input"
    override val component: JComponent get() = this
    override val scrollable: Boolean get() = true

    override fun deviceChanged(device: Device?) {
        this.device = device
        gen++
        configure()
        setStatus(if (device == null) "Not connected" else " ", false)
    }

    override fun dispose() = Unit

    private var device: Device? = null

    /** Bumped on a device change: a result of an action started for another device is dropped. */
    private var gen = 0

    private val buttons = HardwareButton.values().associateWith { b -> JButton(b.label).apply { addActionListener { act(readStatus = true) { it.press(b) } } } }
    private val wake = JButton("Wake").apply {
        toolTipText = "Android: sends WAKEUP (the Power button only toggles)"
        addActionListener { act(readStatus = true) { it.wake() } }
    }
    private val keys = InputKey.values().associateWith { k -> JButton(k.label).apply { addActionListener { act { it.pressKey(k) } } } }
    private val hideKeyboard = JButton("Hide keyboard").apply { addActionListener { act { it.hideKeyboard() } } }

    private val clipboardText = JBTextArea(4, 1).apply { lineWrap = true }
    private val clipboardGet = JButton("Get from device").apply { addActionListener { getClipboard() } }
    private val clipboardSet = JButton("Set on device").apply { addActionListener { act { it.clipboardSet(clipboardText.text) } } }
    private val clipboardPaste = JButton("Set and paste").apply {
        toolTipText = "Sets the clipboard, then pastes into the focused field (ctrl+v on Android, cmd+v on iOS)"
        addActionListener { act { it.setAndPaste(clipboardText.text) } }
    }
    private val clipboardClear = JButton("Clear").apply { addActionListener { clipboardText.text = ""; act { it.clipboardSet("") } } }
    private val clipboardNote = WrappingLabel(" ").apply { foreground = JBColor.GRAY }
    private val keyboardNote = WrappingLabel(
        "<html>Tap a text field to show the keyboard. Hide keyboard sends Escape, never Back. Hiding the keyboard on iOS is not supported by the tools.</html>",
    ).apply { foreground = JBColor.GRAY }
    private val hardwareNote = WrappingLabel(" ").apply { foreground = JBColor.GRAY }
    private val status = WrappingLabel(" ").apply { border = JBUI.Borders.empty(2, 8) }

    init {
        fun row(vararg c: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { c.forEach { add(it) } }
        fun group(name: String, vararg rows: JComponent) = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createTitledBorder(name)
            rows.forEach { (it as? JComponent)?.alignmentX = 0f; add(it) }
        }
        val hardware = group("Hardware buttons", row(*buttons.values.toTypedArray(), wake), hardwareNote.also { it.border = JBUI.Borders.empty(0, 8) })
        val keyboard = group("Keyboard", row(*keys.values.toTypedArray(), hideKeyboard), keyboardNote.also { it.border = JBUI.Borders.empty(0, 8) })
        val clip = group(
            "Clipboard",
            JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(2, 8); add(JBScrollPane(clipboardText), BorderLayout.CENTER) },
            row(clipboardGet, clipboardSet, clipboardPaste, clipboardClear),
            clipboardNote.also { it.border = JBUI.Borders.empty(0, 8) },
        )
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4, 6)
            add(hardware); add(keyboard); add(clip)
        }
        add(content, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        configure()
        setStatus("Not connected", false)
    }

    private fun setStatus(text: String, error: Boolean) {
        status.text = text.ifBlank { " " }
        status.foreground = if (error) JBColor.RED else JBColor.foreground()
    }

    /** Enables what the connected device offers; a disabled control says why in its tooltip. */
    private fun configure() {
        val d = device
        val platform = d?.platform
        val mobilecli = d != null && DeviceServices.mobilecli() != null
        val android = platform == Platform.ANDROID
        HardwareButton.values().forEach { b ->
            val on = platform != null && b.supports(platform) && (android || mobilecli || b == HardwareButton.SHAKE && d?.kind == io.github.marcomaes.mobiletestbench.core.DeviceKind.SIMULATOR)
            val btn = buttons.getValue(b)
            btn.isEnabled = on
            btn.toolTipText = when {
                d == null -> null
                !b.supports(platform!!) -> b.hint(platform)
                !on -> "Needs mobilecli (or a simulator for Shake)"
                else -> b.hint(platform).ifEmpty { null }
            }
        }
        hardwareNote.text = when {
            d == null -> " "
            android -> "<html>Power toggles the screen. Volume presses show the result below: the first press may only show the panel.</html>"
            else -> "<html>Lock toggles the simulator lock; wake it, then press Home to unlock (sometimes twice). The status shows the lock state.</html>"
        }
        wake.isEnabled = android
        keys.values.forEach { it.isEnabled = d != null && (android || mobilecli) }
        keys.values.forEach { it.toolTipText = if (d != null && !android && !mobilecli) "Keys on iOS need mobilecli" else null }
        hideKeyboard.isEnabled = android
        hideKeyboard.toolTipText = if (d != null && !android) "Not supported on iOS: the tools cannot hide its keyboard" else null
        listOf(clipboardGet, clipboardSet, clipboardPaste, clipboardClear).forEach { it.isEnabled = d != null && mobilecli }
        clipboardPaste.isEnabled = d != null && mobilecli
        clipboardNote.text = when {
            d == null -> " "
            !mobilecli -> "<html>${InputActions.CLIPBOARD_NEEDS_MOBILECLI}</html>"
            d.kind == io.github.marcomaes.mobiletestbench.core.DeviceKind.EMULATOR -> "<html>Android emulator clipboards are shared with this computer: Set on device also changes your clipboard here.</html>"
            platform == Platform.IOS -> "<html>Paste on iOS shows an Allow Paste prompt on the device that you have to answer.</html>"
            else -> " "
        }
    }

    /** Runs [work] on a pooled thread and shows its result; with [readStatus] the device read-back (wakefulness, volume, lock state) is appended. */
    private fun act(readStatus: Boolean = false, work: (InputActions) -> String) {
        val dev = device ?: return
        val my = gen
        setStatus("Working...", false)
        ApplicationManager.getApplication().executeOnPooledThread {
            var readBack: String? = null
            val result = runCatching {
                val actions = DeviceServices.input(dev)
                val message = work(actions)
                if (readStatus) readBack = runCatching { actions.status() }.getOrNull()
                message
            }
            SwingUtilities.invokeLater {
                if (my != gen) return@invokeLater
                result.onSuccess { setStatus(it + (readBack?.let { s -> ". Device now: $s" } ?: ""), false) }
                    .onFailure { setStatus(it.message ?: it.javaClass.simpleName, true) }
            }
        }
    }

    private fun getClipboard() {
        val dev = device ?: return
        val my = gen
        setStatus("Reading the clipboard...", false)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { DeviceServices.input(dev).clipboardGet() }
            SwingUtilities.invokeLater {
                if (my != gen) return@invokeLater
                result.onSuccess {
                    clipboardText.text = it
                    setStatus(if (it.isEmpty()) "The device clipboard is empty" else "Read ${it.length} characters from the device clipboard", false)
                }.onFailure { setStatus(it.message ?: it.javaClass.simpleName, true) }
            }
        }
    }
}
