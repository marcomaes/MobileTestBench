package io.github.marcomaes.mobiletestbench.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.github.marcomaes.mobiletestbench.DeviceServices
import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.network.AndroidNetwork
import io.github.marcomaes.mobiletestbench.core.network.DelayPreset
import io.github.marcomaes.mobiletestbench.core.network.NetworkActions
import io.github.marcomaes.mobiletestbench.core.network.NetworkChange
import io.github.marcomaes.mobiletestbench.core.network.NetworkKey
import io.github.marcomaes.mobiletestbench.core.network.NetworkParsers
import io.github.marcomaes.mobiletestbench.core.network.NetworkState
import io.github.marcomaes.mobiletestbench.core.network.SpeedPreset
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ItemEvent
import javax.swing.Box
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities

/**
 * Airplane mode, Wi-Fi, mobile data and (emulators only) a speed and latency profile of an Android device. Every control applies at once
 * on a pooled thread, then the device is read back until the change has settled (airplane mode takes several seconds to recover).
 * iOS simulators share the Mac network, so there the tab only explains what to use instead. Pure logic in `core/network`.
 */
class NetworkPanel : JPanel(GridBagLayout()), DeviceToolTab {

    override val title = "Network"
    override val component: JComponent get() = this
    override val scrollable: Boolean get() = true

    override fun deviceChanged(device: Device?) {
        this.device = device
        gen++
        busy = false
        backend = null
        state = null
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
    private var backend: AndroidNetwork? = null
    private var state: NetworkState? = null
    private var visible = false
    private var busy = false
    private var updating = false

    /** Bumped for every read, write and device change: a result of an older run is dropped. */
    @Volatile private var gen = 0

    private val actions = NetworkActions()

    private val airplane = JBCheckBox("Airplane mode")
    private val wifi = JBCheckBox("Wi-Fi")
    private val data = JBCheckBox("Mobile data")
    private val speed = JComboBox<SpeedPreset>()
    private val delay = JComboBox<DelayPreset>()
    private val reset = JButton("Reset to normal")
    private val refreshButton = JButton("Refresh")

    private val connection = WrappingLabel(" ")
    private val airplaneStatus = WrappingLabel(" ")
    private val wifiStatus = WrappingLabel(" ")
    private val dataStatus = WrappingLabel(" ")
    private val speedStatus = WrappingLabel(" ")
    private val delayStatus = WrappingLabel(" ")
    private val general = WrappingLabel(" ")
    private val changedNote = WrappingLabel(" ")
    private val iosNote = WrappingLabel().apply { foreground = JBColor.GRAY }
    private val androidControls = mutableListOf<JComponent>()

    /** The preset the user picked last, so full / 5g and gprs / umts (identical numbers) keep the chosen name when read back. */
    private var pickedSpeed: SpeedPreset? = null
    private var pickedDelay: DelayPreset? = null

    init {
        border = JBUI.Borders.empty(6, 8)
        speed.renderer = labelRenderer<SpeedPreset> { it?.label ?: "(custom)" }
        delay.renderer = labelRenderer<DelayPreset> { it?.label ?: "(custom)" }
        SpeedPreset.values().forEach { speed.addItem(it) }
        DelayPreset.values().forEach { delay.addItem(it) }

        airplane.addActionListener {
            val on = airplane.isSelected
            change(listOf(NetworkChange(NetworkKey.AIRPLANE, on = on)), airplaneStatus, 15) { it.airplane == on && (on || it.online == true || it.wifi == true) }
        }
        wifi.addActionListener {
            val on = wifi.isSelected
            change(listOf(NetworkChange(NetworkKey.WIFI, on = on)), wifiStatus, 8) { it.wifi == on }
        }
        data.addActionListener {
            val on = data.isSelected
            change(listOf(NetworkChange(NetworkKey.DATA, on = on)), dataStatus, 4) { it.mobileData == on }
        }
        speed.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED && !updating) {
                val choice = speed.selectedItem as? SpeedPreset ?: return@addItemListener
                pickedSpeed = choice
                change(listOf(NetworkChange(NetworkKey.SPEED, arg = choice.arg)), speedStatus, 1) { true }
            }
        }
        delay.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED && !updating) {
                val choice = delay.selectedItem as? DelayPreset ?: return@addItemListener
                pickedDelay = choice
                change(listOf(NetworkChange(NetworkKey.DELAY, arg = choice.arg)), delayStatus, 1) { true }
            }
        }
        reset.addActionListener { resetToNormal() }
        refreshButton.addActionListener { refresh() }

        var row = 0
        fun add(label: String?, control: JComponent, status: JComponent) {
            val c = GridBagConstraints().apply { gridy = row; anchor = GridBagConstraints.WEST; insets = JBUI.insets(3, 0, 3, 10) }
            if (label != null) add(WrappingLabel(label), c.apply { gridx = 0 })
            add(control, c.apply { gridx = 1 })
            add(status, c.apply { gridx = 2; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL })
            row++
        }
        fun full(c: JComponent, top: Int = 3) {
            add(c, GridBagConstraints().apply { gridy = row; gridx = 0; gridwidth = 3; anchor = GridBagConstraints.WEST; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0; insets = JBUI.insets(top, 0, 0, 0) })
            row++
        }
        full(iosNote)
        add("Connectivity:", airplane, airplaneStatus)
        add(null, wifi, wifiStatus)
        add(null, data, dataStatus)
        add("Emulator speed:", speed, speedStatus)
        add("Emulator latency:", delay, delayStatus)
        val buttons = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
            add(reset)
            add(Box.createHorizontalStrut(JBUI.scale(6)))
            add(refreshButton)
        }
        add(null, buttons, general)
        full(connection, 6)
        full(changedNote)
        val hint = WrappingLabel(
            "<html>Wi-Fi off alone or mobile data off alone stays online (the other one takes over); only airplane mode, or both off, goes offline. " +
                "Turning airplane mode off takes several seconds to reconnect. Speed and latency are set through the emulator console and stay " +
                "on the running emulator until you change them or press Reset to normal, which restores what this tab changed. " +
                "Whether the throttling really slows an app's traffic is not verified here: the tab only checks what the console reports.</html>",
        ).apply { foreground = JBColor.GRAY }
        full(hint, 8)
        add(JPanel(), GridBagConstraints().apply { gridy = row; weighty = 1.0; fill = GridBagConstraints.VERTICAL })
        androidControls += listOf(airplane, wifi, data, speed, delay, reset, refreshButton)
        configure()
    }

    private fun <T> labelRenderer(text: (T?) -> String): ListCellRenderer<T> {
        val base = DefaultListCellRenderer()
        return ListCellRenderer { list, value, index, sel, focus -> base.getListCellRendererComponent(list, text(value), index, sel, focus) }
    }

    private fun isAndroid(d: Device?) = d?.platform == Platform.ANDROID
    private fun isEmulator() = backend?.emulator ?: device?.let { io.github.marcomaes.mobiletestbench.core.android.EmulatorDiscovery.consolePort(it.id) != null } ?: false

    private fun setStatus(label: JBLabel, text: String, error: Boolean = false) {
        label.text = text.ifBlank { " " }
        label.foreground = if (error) JBColor.RED else JBColor.GRAY
    }

    /** Sets what the connected device kind offers, before anything is read. */
    private fun configure() {
        val d = device
        updating = true
        airplane.isSelected = false
        wifi.isSelected = false
        data.isSelected = false
        speed.selectedItem = null
        delay.selectedItem = null
        pickedSpeed = null
        pickedDelay = null
        updating = false
        listOf(connection, airplaneStatus, wifiStatus, dataStatus, speedStatus, delayStatus, changedNote).forEach { setStatus(it, " ") }
        val android = isAndroid(d)
        iosNote.isVisible = d != null && !android
        iosNote.text = if (d != null && !android) IOS_NOTE else " "
        androidControls.forEach { it.isVisible = android || d == null }
        listOf(airplaneStatus, wifiStatus, dataStatus, speedStatus, delayStatus, changedNote, connection).forEach { it.isVisible = android || d == null }
        setStatus(general, if (d == null) "Not connected" else " ")
        if (android && !isEmulator()) {
            setStatus(speedStatus, "Speed profiles need an emulator")
            setStatus(delayStatus, "Latency profiles need an emulator")
        }
        updateEnabled()
    }

    private fun updateEnabled() {
        val ok = isAndroid(device) && !busy && state != null
        airplane.isEnabled = ok && state!!.airplane != null
        wifi.isEnabled = ok && state!!.wifi != null
        data.isEnabled = ok && state!!.mobileData != null
        speed.isEnabled = ok && state!!.emulator != null
        delay.isEnabled = ok && state!!.emulator != null
        reset.isEnabled = ok
        refreshButton.isEnabled = isAndroid(device) && !busy
    }

    /** Shows what the device reported: the controls follow it, the status labels give the detail or the read error. */
    private fun showState(st: NetworkState) {
        updating = true
        airplane.isSelected = st.airplane == true
        wifi.isSelected = st.wifi == true
        data.isSelected = st.mobileData == true
        val emu = st.emulator
        speed.selectedItem = emu?.let { NetworkParsers.matchSpeed(it, pickedSpeed) }
        delay.selectedItem = emu?.let { NetworkParsers.matchDelay(it, pickedDelay) }
        updating = false

        fun status(label: JBLabel, key: String, value: Boolean?) {
            val err = st.errors[key]
            if (err != null) setStatus(label, err, true) else setStatus(label, "Device reports: ${if (value == true) "on" else "off"}")
        }
        status(airplaneStatus, "airplane", st.airplane)
        status(wifiStatus, "wifi", st.wifi)
        status(dataStatus, "data", st.mobileData)
        when {
            !isEmulator() -> Unit
            st.errors["emulator"] != null -> { setStatus(speedStatus, st.errors["emulator"]!!, true); setStatus(delayStatus, " ") }
            emu != null -> {
                setStatus(speedStatus, if (emu.unlimited) "Console reports: unlimited" else "Console reports: ${emu.downBits} bits/s down, ${emu.upBits} bits/s up" +
                    if (NetworkParsers.matchSpeed(emu) == null) " (custom)" else "")
                setStatus(delayStatus, "Console reports: " + if (emu.noDelay) "no delay" else "${emu.minMs}-${emu.maxMs} ms" +
                    if (NetworkParsers.matchDelay(emu) == null) " (custom)" else "")
            }
        }
        val online = st.online
        setStatus(connection, when (online) {
            true -> "Connection: online (Android has a default network)"
            false -> "Connection: offline (no default network)"
            null -> st.errors["online"]?.let { "Connection: unknown ($it)" } ?: "Connection: unknown"
        }, online == false)
        val d = device
        val changed = d != null && actions.baseline.originals(d.id) != null
        val throttled = emu != null && !emu.normal
        setStatus(changedNote, when {
            throttled -> "Conditions changed by Mobile Test Bench or earlier: the emulator is throttled (${emu!!.describe()}). It stays so until you press Reset to normal."
            changed -> "Conditions changed by MobileTestBench. They stay on the device until you press Reset to normal."
            else -> " "
        }, throttled || changed)
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
                val b = DeviceServices.network(dev) ?: error("Not supported for this device")
                b to b.read()
            }
            SwingUtilities.invokeLater {
                if (my != gen) return@invokeLater
                busy = false
                result.onSuccess { (b, st) ->
                    backend = b
                    state = st
                    setStatus(general, " ")
                    configureEmulatorNote()
                    showState(st)
                }.onFailure {
                    state = null
                    setStatus(general, it.message ?: it.javaClass.simpleName, true)
                    updateEnabled()
                }
            }
        }
    }

    private fun configureEmulatorNote() {
        if (!isEmulator()) {
            setStatus(speedStatus, "Speed profiles need an emulator")
            setStatus(delayStatus, "Latency profiles need an emulator")
        }
    }

    /**
     * Runs [work] on a pooled thread (returns the first error or null), then reads the device back every second for up to [settleSeconds]
     * until [settled] holds, showing each reading. Controls stay disabled meanwhile. An error goes to [target].
     */
    private fun apply(target: JBLabel, settleSeconds: Int, settled: (NetworkState) -> Boolean, work: (AndroidNetwork) -> String?) {
        val b = backend ?: return
        if (device == null || state == null || busy) return
        val my = ++gen
        busy = true
        updateEnabled()
        setStatus(target, "Applying...")
        ApplicationManager.getApplication().executeOnPooledThread {
            val failure = work(b)
            var last: NetworkState? = null
            var readError: String? = null
            var attempt = 0
            while (attempt < settleSeconds || attempt == 0) {
                val read = runCatching { b.read() }
                last = read.getOrNull() ?: last
                readError = read.exceptionOrNull()?.message
                val st = read.getOrNull()
                val done = failure != null || (st != null && settled(st)) || attempt + 1 >= settleSeconds
                val shown = st
                val reconnecting = !done
                SwingUtilities.invokeLater {
                    if (my != gen) return@invokeLater
                    if (shown != null) { state = shown; showState(shown) }
                    if (reconnecting) { setStatus(target, "Reconnecting..."); setStatus(general, "Waiting for the device to settle...") }
                }
                if (done || my != gen) break
                Thread.sleep(1000)
                attempt++
            }
            SwingUtilities.invokeLater {
                if (my != gen) return@invokeLater
                busy = false
                setStatus(general, " ")
                if (failure != null) setStatus(target, failure, true)
                else if (last == null) setStatus(general, "Applied, but reading the device back failed: $readError", true)
                updateEnabled()
            }
        }
    }

    private fun change(changes: List<NetworkChange>, target: JBLabel, settleSeconds: Int, settled: (NetworkState) -> Boolean) {
        val dev = device ?: return
        val before = state ?: return
        apply(target, settleSeconds, settled) { actions.change(dev.id, it, before, changes) }
    }

    private fun resetToNormal() {
        val dev = device ?: return
        apply(general, 15, { it.airplane == false || it.online == true }) { b -> actions.reset(dev.id, b).first }
        pickedSpeed = null
        pickedDelay = null
    }

    private companion object {
        const val IOS_NOTE = "<html>iOS simulators share the Mac's network, so conditions cannot be changed per simulator here " +
            "(<code>simctl status_bar</code> only changes the icons in the status bar, it does not throttle or cut anything). " +
            "To slow down or cut the network, use the Network Link Conditioner on the Mac (Additional Tools for Xcode, then its preference pane), " +
            "or route the simulator through a proxy. Network Link Conditioner affects all traffic of the Mac, not only the simulator.</html>"
    }
}
