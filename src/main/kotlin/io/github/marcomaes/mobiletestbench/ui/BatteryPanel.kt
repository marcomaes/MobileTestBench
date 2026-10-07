package io.github.marcomaes.mobiletestbench.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.JBUI
import io.github.marcomaes.mobiletestbench.DeviceServices
import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.battery.AndroidBattery
import io.github.marcomaes.mobiletestbench.core.battery.BatteryChange
import io.github.marcomaes.mobiletestbench.core.battery.BatteryState
import io.github.marcomaes.mobiletestbench.core.battery.BatteryStatus
import io.github.marcomaes.mobiletestbench.core.apps.Apps
import io.github.marcomaes.mobiletestbench.core.memory.TrimLevel
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ItemEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities

/**
 * Simulated battery of an Android device (`adb shell dumpsys battery set level|status|ac|usb`, `dumpsys battery reset`). Every control
 * applies at once on a pooled thread, then the device is read back. iOS simulators cannot do this with simctl, so the tab says so.
 * Pure logic in `core/battery`.
 */
class BatteryPanel : JPanel(GridBagLayout()), DeviceToolTab {

    override val title = "Battery"
    override val component: JComponent get() = this
    override val scrollable: Boolean get() = true

    override fun deviceChanged(device: Device?) {
        this.device = device
        gen++
        busy = false
        backend = null
        configure()
        if (device != null && visible) refresh()
    }

    override fun shown() {
        visible = true
        if (device != null && !busy) refresh()
    }

    override fun hidden() {
        visible = false
    }

    override fun dispose() {
        gen++
    }

    private var device: Device? = null
    private var backend: AndroidBattery? = null
    private var loaded = false
    private var visible = false
    private var busy = false
    private var updating = false
    private var trimBusy = false

    /** Bumped for every read, write and device change: a result of an older run is dropped. */
    @Volatile private var gen = 0

    private val level = JSlider(0, 100, 100)
    private val levelText = WrappingLabel(" ")
    private val status = JComboBox<BatteryStatus>()
    private val ac = JBCheckBox("AC powered")
    private val usb = JBCheckBox("USB powered")
    private val reset = JButton("Reset to real battery")
    private val refreshButton = JButton("Refresh")
    private val general = WrappingLabel(" ")
    private val note = WrappingLabel(" ")
    private val iosNote = WrappingLabel().apply { foreground = JBColor.GRAY }
    private val controls = mutableListOf<JComponent>()
    private val trimLevel = JComboBox<TrimLevel>()
    private val trimApp = JComboBox<String>(arrayOf(FOREGROUND)).apply { isEditable = true; toolTipText = "Package or process name; \"$FOREGROUND\" uses the app in front" }
    private val trimSend = JButton("Send memory warning")
    private val trimStatus = WrappingLabel(" ")
    private val trimControls = mutableListOf<JComponent>()

    init {
        border = JBUI.Borders.empty(6, 8)
        val base = DefaultListCellRenderer()
        status.renderer = ListCellRenderer { list, value, index, sel, focus -> base.getListCellRendererComponent(list, value?.label ?: "", index, sel, focus) }
        BatteryStatus.values().forEach { status.addItem(it) }

        level.addChangeListener {
            levelText.text = "${level.value} %"
            if (!level.valueIsAdjusting && !updating) change(BatteryChange.Level(level.value))
        }
        status.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED && !updating) (status.selectedItem as? BatteryStatus)?.let { change(BatteryChange.Status(it)) }
        }
        ac.addActionListener { change(BatteryChange.Ac(ac.isSelected)) }
        usb.addActionListener { change(BatteryChange.Usb(usb.isSelected)) }
        reset.addActionListener { resetBattery() }
        TrimLevel.values().forEach { trimLevel.addItem(it) }
        trimLevel.selectedItem = TrimLevel.RUNNING_CRITICAL
        trimLevel.renderer = ListCellRenderer { list, value, index, sel, focus -> base.getListCellRendererComponent(list, value?.label ?: "", index, sel, focus) }
        trimLevel.addItemListener { trimLevel.toolTipText = (trimLevel.selectedItem as? TrimLevel)?.hint }
        trimLevel.toolTipText = TrimLevel.RUNNING_CRITICAL.hint
        trimSend.addActionListener { sendTrim() }
        refreshButton.addActionListener { refresh() }

        var row = 0
        fun add(label: String?, control: JComponent, extra: JComponent? = null) {
            val c = GridBagConstraints().apply { gridy = row; anchor = GridBagConstraints.WEST; insets = JBUI.insets(3, 0, 3, 10) }
            if (label != null) add(WrappingLabel(label), c.apply { gridx = 0 })
            add(control, c.apply { gridx = 1 })
            if (extra != null) add(extra, c.apply { gridx = 2; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL })
            row++
        }
        fun full(c: JComponent, top: Int = 3) {
            add(c, GridBagConstraints().apply { gridy = row; gridx = 0; gridwidth = 3; anchor = GridBagConstraints.WEST; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0; insets = JBUI.insets(top, 0, 0, 0) })
            row++
        }
        full(iosNote)
        add("Level:", level, levelText)
        add("Status:", status)
        add("Power:", ac)
        add(null, usb)
        val buttons = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)).apply { add(reset); add(refreshButton) }
        add(null, buttons, general)
        full(note, 6)
        full(WrappingLabel(
            "<html>The values are set with <code>dumpsys battery set</code>; Android then stops following the real battery until you press " +
                "Reset to real battery (<code>dumpsys battery reset</code>). The simulated state stays on the device after the tab or IDE closes. " +
                "Whether an app reacts (low battery warnings, power saver) depends on the app and Android version and is not verified here.</html>",
        ).apply { foreground = JBColor.GRAY }, 8)
        val sep = com.intellij.ui.SeparatorComponent()
        full(sep, 10)
        val memTitle = WrappingLabel("<html><b>Memory warning</b></html>")
        full(memTitle, 6)
        val memApp = WrappingLabel("App:")
        add(memApp.also { trimControls += it }, GridBagConstraints().apply { gridy = row; gridx = 0; anchor = GridBagConstraints.WEST; insets = JBUI.insets(3, 0, 3, 10) })
        add(trimApp, GridBagConstraints().apply { gridy = row; gridx = 1; gridwidth = 2; anchor = GridBagConstraints.WEST; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0; insets = JBUI.insets(3, 0, 3, 10) })
        row++
        add("Level:", trimLevel, trimSend)
        full(trimStatus)
        full(WrappingLabel(
            "<html>Runs <code>adb shell am send-trim-memory &lt;package&gt; &lt;level&gt;</code>, which makes the running app's " +
                "<code>onTrimMemory</code> callbacks fire (the app must be running). iOS simulators have no command for this: " +
                "use Simulator &gt; Debug &gt; Simulate Memory Warning.</html>",
        ).apply { foreground = JBColor.GRAY }, 6)
        trimControls += listOf(memTitle, sep, trimApp, trimLevel, trimSend)
        add(JPanel(), GridBagConstraints().apply { gridy = row; weighty = 1.0; fill = GridBagConstraints.VERTICAL })
        controls += listOf(level, status, ac, usb, reset, refreshButton)
        controls += trimControls
        configure()
    }

    private fun isAndroid(d: Device?) = d?.platform == Platform.ANDROID

    private fun setStatus(label: WrappingLabel, text: String, error: Boolean = false) {
        label.text = text.ifBlank { " " }
        label.foreground = if (error) JBColor.RED else JBColor.GRAY
    }

    private fun configure() {
        val d = device
        loaded = false
        updating = true
        level.value = 100
        status.selectedItem = null
        ac.isSelected = false
        usb.isSelected = false
        updating = false
        levelText.text = " "
        val android = isAndroid(d)
        iosNote.isVisible = d != null && !android
        iosNote.text = if (d != null && !android) IOS_NOTE else " "
        controls.forEach { it.isVisible = android || d == null }
        setStatus(general, if (d == null) "Not connected" else " ")
        setStatus(note, " ")
        setStatus(trimStatus, " ")
        trimBusy = false
        updateEnabled()
    }

    private fun updateEnabled() {
        val ok = isAndroid(device) && !busy && loaded
        listOf(level, status, ac, usb, reset).forEach { it.isEnabled = ok }
        listOf(trimApp, trimLevel, trimSend).forEach { it.isEnabled = isAndroid(device) && !trimBusy }
        refreshButton.isEnabled = isAndroid(device) && !busy
    }

    private fun show(st: BatteryState) {
        updating = true
        st.level?.let { level.value = it; levelText.text = "$it %" }
        status.selectedItem = st.status
        ac.isSelected = st.ac == true
        usb.isSelected = st.usb == true
        updating = false
        setStatus(note, if (st.updatesStopped) "A simulated battery state is active. It stays until you press Reset to real battery." else " ", st.updatesStopped)
        updateEnabled()
    }

    private fun refresh() {
        val dev = device ?: return
        if (!isAndroid(dev)) return
        val my = ++gen
        busy = true
        setStatus(general, "Reading...")
        updateEnabled()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val b = DeviceServices.battery(dev) ?: error("Not supported for this device")
                b to b.read()
            }
            SwingUtilities.invokeLater {
                if (my != gen) return@invokeLater
                busy = false
                result.onSuccess { (b, st) ->
                    backend = b
                    loaded = true
                    setStatus(general, " ")
                    show(st)
                }.onFailure {
                    loaded = false
                    setStatus(general, it.message ?: it.javaClass.simpleName, true)
                    updateEnabled()
                }
            }
        }
    }

    private fun sendTrim() {
        val dev = device ?: return
        if (!isAndroid(dev) || trimBusy) return
        val level = trimLevel.selectedItem as? TrimLevel ?: return
        val typed = (trimApp.editor.item as? String)?.trim().orEmpty()
        val my = gen
        trimBusy = true
        setStatus(trimStatus, "Sending...")
        updateEnabled()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val process = if (typed.isEmpty() || typed == FOREGROUND) {
                    Apps.parseForeground(DeviceServices.mobilecliData(dev, "apps", "foreground"))?.id
                        ?: error("No foreground app found: type a package name")
                } else typed
                val m = DeviceServices.memory(dev) ?: error("Not supported for this device")
                m.trim(process, level)
                process
            }
            SwingUtilities.invokeLater {
                if (my != gen) return@invokeLater
                trimBusy = false
                result.onSuccess { setStatus(trimStatus, "Sent ${level.label} to $it") }
                    .onFailure { setStatus(trimStatus, it.message ?: it.javaClass.simpleName, true) }
                updateEnabled()
            }
        }
    }

    private fun change(c: BatteryChange) = run("Applying...") { it.apply(c) }

    private fun resetBattery() = run("Resetting...") { it.reset() }

    /** Runs [work] on a pooled thread, then reads the device back. A failure is shown and the state is re-read so the controls show the truth. */
    private fun run(message: String, work: (AndroidBattery) -> Unit) {
        val b = backend ?: return
        if (device == null || !loaded || busy) return
        val my = ++gen
        busy = true
        setStatus(general, message)
        updateEnabled()
        ApplicationManager.getApplication().executeOnPooledThread {
            val failure = runCatching { work(b) }.exceptionOrNull()
            val read = runCatching { b.read() }
            SwingUtilities.invokeLater {
                if (my != gen) return@invokeLater
                busy = false
                read.getOrNull()?.let { show(it) }
                when {
                    failure != null -> setStatus(general, failure.message ?: failure.javaClass.simpleName, true)
                    read.isFailure -> setStatus(general, "Applied, but reading the device back failed: ${read.exceptionOrNull()?.message}", true)
                    else -> setStatus(general, " ")
                }
                updateEnabled()
            }
        }
    }

    private companion object {
        const val FOREGROUND = "Foreground app"
        const val IOS_NOTE = "<html>Battery simulation is not possible on iOS simulators: <code>simctl</code> has no command for the battery " +
            "level or charging state (<code>simctl status_bar --batteryLevel</code> only changes the icon in the status bar, it does not " +
            "change what apps read). Memory warnings have no <code>simctl</code> command either: use Simulator &gt; Debug &gt; Simulate Memory Warning. " +
            "Use an Android emulator or device for the Battery and Memory controls here.</html>"
    }
}
