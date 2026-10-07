package io.github.marcomaes.mobiletestbench.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.github.marcomaes.mobiletestbench.DeviceServices
import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.biometrics.AndroidBiometrics
import io.github.marcomaes.mobiletestbench.core.biometrics.BiometricCommands
import io.github.marcomaes.mobiletestbench.core.biometrics.BiometricResult
import io.github.marcomaes.mobiletestbench.core.biometrics.IosBiometrics
import io.github.marcomaes.mobiletestbench.core.biometrics.IosBiometry
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * Simulates biometrics. iOS simulator: enrol / unenrol Face ID or Touch ID and send matching or non-matching notifications through
 * `simctl spawn notifyutil`. Android emulator: touch the fingerprint sensor with a finger id (`adb emu finger touch`). Each click
 * runs on a pooled thread and shows the exact result; a result of another device (or an older click) is dropped. Pure logic in `core/biometrics`.
 */
class BiometricsPanel : JPanel(), DeviceToolTab {

    override val title = "Biometrics"
    override val component: JComponent get() = this
    override val scrollable: Boolean get() = true

    override fun deviceChanged(device: Device?) {
        this.device = device
        gen++
        busy = false
        ios = null
        android = null
        biometry = IosBiometry.FACE_ID
        if (device != null) {
            ios = DeviceServices.iosBiometrics(device)
            android = DeviceServices.androidBiometrics(device)
            if (device.platform == Platform.IOS) biometry = BiometricCommands.biometryFor(device.name)
        }
        configure()
        if (device != null && visible) refreshState()
    }

    override fun shown() {
        visible = true
        if (device != null && !busy) refreshState()
    }

    override fun hidden() {
        visible = false
    }

    override fun dispose() {
        gen++
    }

    private var device: Device? = null
    private var ios: IosBiometrics? = null
    private var android: AndroidBiometrics? = null
    private var biometry = IosBiometry.FACE_ID
    private var visible = false
    private var busy = false

    /** Bumped for every click and device change: a late result of an older run is dropped. */
    @Volatile private var gen = 0

    private val header = WrappingLabel(" ")
    private val state = WrappingLabel(" ")
    private val enroll = JButton("Enroll")
    private val unenroll = JButton("Unenroll")
    private val match = JButton("Matching face")
    private val noMatch = JButton("Non-matching face")

    private val preset = JComboBox(arrayOf("Enrolled finger 1", "Finger 2", "Unknown finger 99 (not enrolled)", "Custom id"))
    private val fingerId = JTextField("1", 4)
    private val touch = JButton("Touch sensor")
    private val lift = JButton("Lift finger")
    private val refresh = JButton("Refresh")

    private val result = WrappingLabel(" ")
    private val note = WrappingLabel(" ").apply { foreground = JBColor.GRAY }
    private val iosRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
    private val androidRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(6, 8)
        iosRow.apply { add(enroll); add(unenroll); add(match); add(noMatch) }
        androidRow.apply {
            add(WrappingLabel("Finger:")); add(preset); add(WrappingLabel("id")); add(fingerId); add(touch); add(lift); add(refresh)
        }
        listOf(header, state, iosRow, androidRow, result, note).forEach {
            (it as JComponent).alignmentX = LEFT_ALIGNMENT
            add(it)
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)))
        }

        enroll.addActionListener { run { it.enroll() } }
        unenroll.addActionListener { run { it.unenroll() } }
        match.addActionListener { run { it.match(biometry) } }
        noMatch.addActionListener { run { it.noMatch(biometry) } }
        refresh.addActionListener { refreshState() }
        preset.addActionListener {
            when (preset.selectedIndex) {
                0 -> fingerId.text = "1"
                1 -> fingerId.text = "2"
                2 -> fingerId.text = "99"
            }
            fingerId.isEditable = preset.selectedIndex == 3
        }
        touch.addActionListener {
            val (id, err) = BiometricCommands.parseFingerId(fingerId.text)
            if (id == null) show(BiometricResult(err!!, true)) else runAndroid { it.touch(id) }
        }
        lift.addActionListener { runAndroid { it.lift() } }
        fingerId.isEditable = false
        configure()
    }

    private fun show(r: BiometricResult) {
        result.text = "<html>" + r.text.replace("<", "&lt;") + "</html>"
        result.foreground = if (r.error) JBColor.RED else JBColor.foreground()
    }

    private fun configure() {
        val d = device
        val isIos = d?.platform == Platform.IOS
        val isAndroid = d?.platform == Platform.ANDROID
        iosRow.isVisible = isIos
        androidRow.isVisible = isAndroid
        state.isVisible = isIos || isAndroid
        result.text = " "
        state.text = " "
        header.text = when {
            d == null -> "Not connected"
            isIos && ios != null -> "<html>${biometry.label} (${d.name}). The type is guessed from the model name; simctl does not report it.</html>"
            isIos -> "Biometrics need an iOS simulator. A physical iPhone cannot be driven from here."
            isAndroid && android != null -> "Fingerprint sensor of the emulator ${d.id}"
            else -> "Biometrics need an Android emulator: the sensor is reached through the emulator console (adb emu), which a physical Android device does not have."
        }
        note.text = when {
            ios != null -> "<html>Enroll first, then start an app that asks for ${biometry.label}: Matching / Non-matching only act while such a prompt is showing, and they always report only that the notification was posted (notifyutil exits 0 for any name). The enrolled state shown is what this tab or an earlier notifyutil call set; it can differ from Simulator.app's Features menu.</html>"
            android != null -> "<html>Enrol a fingerprint first: set a screen lock in Settings > Security, then Settings > Security > Fingerprint, and press Touch sensor here for every scan step. The plugin cannot set a screen lock or enrol for you (there is no shell command for it). The console replies OK even when nothing is enrolled, so OK is not proof of a match: the enrolled count below is the check.</html>"
            else -> " "
        }
        match.text = "Matching ${biometry.subject}"
        noMatch.text = "Non-matching ${biometry.subject}"
        updateEnabled()
    }

    private fun updateEnabled() {
        val on = !busy
        listOf(enroll, unenroll, match, noMatch).forEach { it.isEnabled = on && ios != null }
        listOf(touch, lift, refresh).forEach { it.isEnabled = on && android != null }
        preset.isEnabled = on && android != null
        fingerId.isEnabled = on && android != null
    }

    private fun refreshState() {
        val dev = device ?: return
        val my = ++gen
        val i = ios
        val a = android
        if (i == null && a == null) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = runCatching {
                if (i != null) when (i.readEnrolled()) {
                    true -> "State: enrolled (as set by this tab or notifyutil)"
                    false -> "State: not enrolled (as set by this tab or notifyutil; Face ID / Touch ID may also be enrolled through Simulator.app)"
                    null -> "State: unknown"
                } else {
                    val prints = a!!.prints()
                    if (prints.isEmpty()) "Enrolled fingerprints: unknown (dumpsys fingerprint showed no entries)"
                    else "Enrolled fingerprints: ${prints.sumOf { it.count }}  (accepted ${prints.sumOf { it.accept }}, rejected ${prints.sumOf { it.reject }} since boot)"
                }
            }.getOrElse { "State could not be read: ${it.message}" }
            SwingUtilities.invokeLater { if (my == gen && device === dev) state.text = text }
        }
    }

    private fun run(action: (IosBiometrics) -> BiometricResult) {
        val b = ios ?: return
        execute { action(b) }
    }

    private fun runAndroid(action: (AndroidBiometrics) -> BiometricResult) {
        val b = android ?: return
        execute { action(b) }
    }

    private fun execute(work: () -> BiometricResult) {
        if (busy) return
        val dev = device ?: return
        val my = ++gen
        busy = true
        updateEnabled()
        result.text = "Working..."
        result.foreground = JBColor.GRAY
        ApplicationManager.getApplication().executeOnPooledThread {
            val r = runCatching(work).getOrElse { BiometricResult(it.message ?: it.javaClass.simpleName, true) }
            SwingUtilities.invokeLater {
                if (my != gen || device !== dev) return@invokeLater
                busy = false
                show(r)
                updateEnabled()
                refreshState()
            }
        }
    }
}
