package io.github.marcomaes.mobiletestbench.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.github.marcomaes.mobiletestbench.DeviceServices
import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.DeviceKind
import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.display.Appearance
import io.github.marcomaes.mobiletestbench.core.display.DisplayBackend
import io.github.marcomaes.mobiletestbench.core.display.DisplayActions
import io.github.marcomaes.mobiletestbench.core.display.DisplayKey
import io.github.marcomaes.mobiletestbench.core.display.DisplayState
import io.github.marcomaes.mobiletestbench.core.display.FontPreset
import io.github.marcomaes.mobiletestbench.core.display.LocaleActions
import io.github.marcomaes.mobiletestbench.core.display.LocaleBackend
import io.github.marcomaes.mobiletestbench.core.display.LocalePresets
import io.github.marcomaes.mobiletestbench.core.display.LocaleState
import io.github.marcomaes.mobiletestbench.core.display.LocaleValidation
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ItemEvent
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Dark mode, font size, animations, show touches and layout bounds of the connected device. Every control reads the device when the tab
 * is shown, applies at once and shows what the device reports afterwards. Reset restores what this tab changed.
 * Android goes through adb, iOS simulators through `simctl ui` (pure logic in `core/display`).
 */
class DisplayPanel : JPanel(GridBagLayout()), DeviceToolTab {

    override val title = "Display"
    override val component: JComponent get() = this
    override val scrollable: Boolean get() = true

    override fun deviceChanged(device: Device?) {
        this.device = device
        gen++
        busy = false
        backend = null
        state = null
        localeGen++
        localeBusy = false
        localeBackend = null
        localeState = null
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

    override fun dispose() = Unit

    private var device: Device? = null
    private var backend: DisplayBackend? = null
    private var state: DisplayState? = null
    private var visible = false
    private var busy = false
    private var updating = false

    /** Bumped for every read, write and device change: a result of an older run is dropped. */
    private var gen = 0

    private val actions = DisplayActions()

    // Language and time zone have their own read/write cycle and generation counter.
    private val localeActions = LocaleActions()
    private var localeBackend: LocaleBackend? = null
    private var localeState: LocaleState? = null
    private var localeBusy = false
    private var localeGen = 0
    private val language = JComboBox<String>().apply { isEditable = true }
    private val timeZone = JComboBox<String>().apply { isEditable = true }
    private val applyLanguage = JButton("Apply")
    private val applyTimeZone = JButton("Apply")
    private val languageStatus = statusLabel()
    private val timeZoneStatus = statusLabel()
    private val localeHint = WrappingLabel(" ").apply { foreground = JBColor.GRAY }

    private val appearance = JComboBox<Appearance>()
    private val font = JComboBox<FontPreset>()
    private val animations = JBCheckBox("Disable animations")
    private val touches = JBCheckBox("Show touches")
    private val layoutBounds = JBCheckBox("Layout bounds")
    private val pointer = JBCheckBox("Pointer location")
    private val dontKeep = JBCheckBox("Don't keep activities")
    private val reset = JButton("Reset to defaults")
    private val refreshButton = JButton("Refresh")

    private val appearanceStatus = statusLabel()
    private val fontStatus = statusLabel()
    private val animationsStatus = statusLabel()
    private val touchesStatus = statusLabel()
    private val layoutStatus = statusLabel()
    private val pointerStatus = statusLabel()
    private val dontKeepStatus = statusLabel()
    private val general = statusLabel()

    private fun statusLabel() = WrappingLabel(" ")

    init {
        border = JBUI.Borders.empty(6, 8)
        LocalePresets.LANGUAGES.forEach { language.addItem(it) }
        LocalePresets.TIME_ZONES.forEach { timeZone.addItem(it) }
        language.renderer = labelRenderer<String> { it?.let(LocalePresets::languageLabel) ?: "" }
        language.selectedItem = null
        timeZone.selectedItem = null
        applyLanguage.addActionListener { applyLanguage() }
        applyTimeZone.addActionListener { applyTimeZone() }
        appearance.renderer = labelRenderer<Appearance> { it?.label ?: "(custom)" }
        font.renderer = labelRenderer<FontPreset> { it?.label ?: "(custom)" }

        appearance.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED && !updating) {
                val choice = appearance.selectedItem as? Appearance ?: return@addItemListener
                val platform = device?.platform ?: return@addItemListener
                change(listOf(DisplayKey.APPEARANCE to choice.raw(platform)), appearanceStatus)
            }
        }
        font.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED && !updating) {
                val choice = font.selectedItem as? FontPreset ?: return@addItemListener
                val platform = device?.platform ?: return@addItemListener
                change(listOf(DisplayKey.FONT_SIZE to choice.raw(platform)), fontStatus)
            }
        }
        animations.addActionListener {
            val off = animations.isSelected
            // Unset (null) is the default scale 1.0 and also what a device that never customised it has.
            change(DisplayKey.ANIMATIONS.map { it to (if (off) "0" else null) }, animationsStatus)
        }
        touches.addActionListener { change(listOf(DisplayKey.SHOW_TOUCHES to if (touches.isSelected) "1" else "0"), touchesStatus) }
        layoutBounds.addActionListener { change(listOf(DisplayKey.LAYOUT_BOUNDS to if (layoutBounds.isSelected) "true" else null), layoutStatus) }
        pointer.addActionListener { change(listOf(DisplayKey.POINTER_LOCATION to if (pointer.isSelected) "1" else "0"), pointerStatus) }
        dontKeep.addActionListener { change(listOf(DisplayKey.DONT_KEEP_ACTIVITIES to if (dontKeep.isSelected) "1" else "0"), dontKeepStatus) }
        reset.addActionListener { resetToDefaults() }
        refreshButton.addActionListener { refresh() }

        var row = 0
        fun add(label: String?, control: JComponent, status: JComponent) {
            val c = GridBagConstraints().apply { gridy = row; anchor = GridBagConstraints.WEST; insets = JBUI.insets(3, 0, 3, 10) }
            if (label != null) add(WrappingLabel(label), c.apply { gridx = 0 })
            add(control, c.apply { gridx = 1 })
            add(status, c.apply { gridx = 2; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL })
            row++
        }
        add("Dark mode:", appearance, appearanceStatus)
        add("Font size:", font, fontStatus)
        add("Animations:", animations, animationsStatus)
        add("Developer options:", touches, touchesStatus)
        add(null, layoutBounds, layoutStatus)
        add(null, pointer, pointerStatus)
        add(null, dontKeep, dontKeepStatus)
        val buttons = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
            add(reset)
            add(javax.swing.Box.createHorizontalStrut(JBUI.scale(6)))
            add(refreshButton)
        }
        add(null, buttons, general)
        val applyLang = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply { add(language); add(javax.swing.Box.createHorizontalStrut(JBUI.scale(6))); add(applyLanguage) }
        val applyZone = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply { add(timeZone); add(javax.swing.Box.createHorizontalStrut(JBUI.scale(6))); add(applyTimeZone) }
        add("Language:", applyLang, languageStatus)
        add("Time zone:", applyZone, timeZoneStatus)
        add(localeHint, GridBagConstraints().apply { gridy = row; gridx = 0; gridwidth = 3; anchor = GridBagConstraints.WEST; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0; insets = JBUI.insets(3, 0, 3, 0) })
        row++
        val hint = WrappingLabel(
            "<html>Changes apply at once and stay on the device until you change them again or press Reset to defaults, which restores " +
                "what this tab changed. Layout bounds is redrawn by running apps; touch the screen if a static one does not update.</html>",
        ).apply { foreground = JBColor.GRAY }
        add(hint, GridBagConstraints().apply { gridy = row; gridx = 0; gridwidth = 3; anchor = GridBagConstraints.WEST; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0; insets = JBUI.insets(8, 0, 0, 0) })
        add(JPanel(), GridBagConstraints().apply { gridy = row + 1; weighty = 1.0; fill = GridBagConstraints.VERTICAL })
        configure()
    }

    private fun <T> labelRenderer(text: (T?) -> String): javax.swing.ListCellRenderer<T> {
        val base = javax.swing.DefaultListCellRenderer()
        return javax.swing.ListCellRenderer { list, value, index, sel, focus -> base.getListCellRendererComponent(list, text(value), index, sel, focus) }
    }

    private fun supported(d: Device?) = d != null && (d.platform == Platform.ANDROID || d.kind == DeviceKind.SIMULATOR)

    /** Sets what the connected device kind offers, before anything is read. */
    private fun configure() {
        val d = device
        updating = true
        appearance.removeAllItems()
        font.removeAllItems()
        FontPreset.values().forEach { font.addItem(it) }
        if (d != null) Appearance.forPlatform(d.platform).forEach { appearance.addItem(it) }
        appearance.selectedItem = null
        font.selectedItem = null
        animations.isSelected = false
        touches.isSelected = false
        layoutBounds.isSelected = false
        pointer.isSelected = false
        dontKeep.isSelected = false
        language.selectedItem = null
        timeZone.selectedItem = null
        updating = false
        setStatus(languageStatus, " ", false)
        setStatus(timeZoneStatus, " ", false)
        localeHint.text = " "
        updateLocaleEnabled()
        listOf(appearanceStatus, fontStatus, animationsStatus, touchesStatus, layoutStatus, pointerStatus, dontKeepStatus).forEach { setStatus(it, " ", false) }
        val android = d?.platform == Platform.ANDROID
        animations.toolTipText = if (d != null && !android) "Not supported on iOS simulators (no setting changes animations there)" else null
        touches.toolTipText = if (d != null && !android) "Android only" else null
        layoutBounds.toolTipText = if (d != null && !android) "Android only" else null
        pointer.toolTipText = if (d != null && !android) "Android only" else null
        dontKeep.toolTipText = if (d != null && !android) "Android only" else null
        setStatus(general, when {
            d == null -> "Not connected"
            !supported(d) -> "Not supported for physical iOS devices"
            else -> " "
        }, false)
        if (d != null && !android && supported(d)) {
            setStatus(animationsStatus, "Not supported on iOS simulators", false)
            setStatus(touchesStatus, "Android only", false)
            setStatus(layoutStatus, "Android only", false)
            setStatus(pointerStatus, "Android only", false)
            setStatus(dontKeepStatus, "Android only", false)
        }
        updateEnabled()
    }

    private fun updateEnabled() {
        val st = state
        val ok = supported(device) && !busy && st != null
        appearance.isEnabled = ok && st.has(DisplayKey.APPEARANCE)
        font.isEnabled = ok && st.has(DisplayKey.FONT_SIZE)
        animations.isEnabled = ok && st.animationsOff != null
        touches.isEnabled = ok && st.showTouches != null
        layoutBounds.isEnabled = ok && st.layoutBounds != null
        pointer.isEnabled = ok && st.pointerLocation != null
        dontKeep.isEnabled = ok && st.dontKeepActivities != null
        reset.isEnabled = supported(device) && !busy && !localeBusy && (st != null || localeState != null)
        refreshButton.isEnabled = supported(device) && !busy
    }

    private fun updateLocaleEnabled() {
        val st = localeState
        val ok = supported(device) && !localeBusy && st != null
        language.isEnabled = ok && st.languages != null
        applyLanguage.isEnabled = language.isEnabled
        val canZone = ok && localeBackend?.canSetTimeZone == true && st.timeZoneError == null
        timeZone.isEnabled = canZone
        applyTimeZone.isEnabled = canZone
    }

    /** Reads language and time zone of the device (own generation counter: a late result for another device is dropped). */
    private fun refreshLocale() {
        val dev = device ?: return
        if (!supported(dev)) return
        if (localeBusy) return // a read or write is in flight; bumping the generation would drop its result
        val my = ++localeGen
        localeBusy = true
        updateLocaleEnabled()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val b = DeviceServices.locale(dev) ?: error("Not supported for this device")
                b to b.read()
            }
            SwingUtilities.invokeLater {
                if (my != localeGen) return@invokeLater
                localeBusy = false
                result.onSuccess { (b, st) ->
                    localeBackend = b
                    localeState = st
                    showLocale(b, st)
                }.onFailure {
                    localeState = null
                    setStatus(languageStatus, it.message ?: it.javaClass.simpleName, true)
                    updateLocaleEnabled()
                }
            }
        }
    }

    private fun showLocale(b: LocaleBackend, st: LocaleState) {
        val android = b.platform == Platform.ANDROID
        st.primary?.let { language.selectedItem = it }
        st.timeZone?.let { timeZone.selectedItem = it }
        if (st.languageError != null) setStatus(languageStatus, st.languageError, true)
        else setStatus(languageStatus, "Device: ${st.languages?.joinToString(", ")?.ifEmpty { "unset" }}" +
            (st.appleLocale?.let { " (region $it)" } ?: ""), false)
        if (st.timeZoneError != null) setStatus(timeZoneStatus, st.timeZoneError, true)
        else if (st.timeZoneFollowsHost) setStatus(timeZoneStatus, "Mac: ${st.timeZone} (the iOS simulator follows the Mac; not changeable per simulator, use System Settings on the Mac)", false)
        else setStatus(timeZoneStatus, "Device: ${st.timeZone ?: "unset"}", false)
        localeHint.text = if (android) {
            "<html>Language applies at once, no restart; running apps recreate their screens. Android keeps only ONE system language this way " +
                "(the other languages are dropped; Reset can only bring back the first). Time zone applies at once; if Automatic time zone is on in Settings the network may switch it back. " +
                "To test one app only, use Apps > Launch with options... > locale.</html>"
        } else {
            "<html>Language is written to the simulator's global preferences and takes effect for apps the next time they start (relaunch the app). " +
                "The Home screen keeps the old language until the simulator or SpringBoard restarts (not done automatically). " +
                "To test one app only, use Apps > Launch with options... > locale.</html>"
        }
        updateLocaleEnabled()
    }

    private fun typed(combo: JComboBox<String>): String = (combo.editor.item?.toString() ?: combo.selectedItem?.toString() ?: "").trim()

    /** Runs [work] on a pooled thread, then reads the locale back. [work] returns the first error text or null. */
    private fun localeWrite(target: JBLabel, work: (LocaleBackend, LocaleState) -> String?) {
        val b = localeBackend ?: return
        val before = localeState ?: return
        if (device == null || localeBusy) return
        val my = ++localeGen
        localeBusy = true
        updateLocaleEnabled()
        setStatus(target, "Applying...", false)
        ApplicationManager.getApplication().executeOnPooledThread {
            val failure = work(b, before)
            val readBack = runCatching { b.read() }
            SwingUtilities.invokeLater {
                if (my != localeGen) return@invokeLater
                localeBusy = false
                val st = readBack.getOrNull()
                if (st != null) { localeState = st; showLocale(b, st) } else updateLocaleEnabled()
                if (failure != null) setStatus(target, failure, true)
            }
        }
    }

    private fun applyLanguage() {
        val dev = device ?: return
        val tag = typed(language)
        LocaleValidation.languageError(tag)?.let { setStatus(languageStatus, it, true); return }
        val before = localeState ?: return
        if (dev.platform == Platform.ANDROID) {
            val warning = localeActions.androidListLossWarning(before, tag)
            // Decided from the live list: whenever more than one language would be dropped, ask.
            if (warning != null &&
                Messages.showYesNoDialog(warning, "Change system language", "Change", "Cancel", Messages.getWarningIcon()) != Messages.YES
            ) return
        }
        localeWrite(languageStatus) { b, st -> localeActions.changeLanguage(dev.id, b, st, tag) }
    }

    private fun applyTimeZone() {
        val dev = device ?: return
        val id = typed(timeZone)
        LocaleValidation.timeZoneError(id)?.let { setStatus(timeZoneStatus, it, true); return }
        localeWrite(timeZoneStatus) { b, st -> localeActions.changeTimeZone(dev.id, b, st, id) }
    }

    private fun setStatus(label: JBLabel, text: String, error: Boolean) {
        label.text = text.ifBlank { " " }
        label.foreground = if (error) JBColor.RED else JBColor.GRAY
    }

    /** Shows what the device reported: the controls follow it, the status labels give the raw value or the read error. */
    private fun showState(st: DisplayState) {
        val d = device ?: return
        val platform = d.platform
        updating = true
        appearance.selectedItem = Appearance.fromRaw(platform, st.values[DisplayKey.APPEARANCE])
        font.selectedItem = FontPreset.fromRaw(platform, st.values[DisplayKey.FONT_SIZE])
        animations.isSelected = st.animationsOff == true
        touches.isSelected = st.showTouches == true
        layoutBounds.isSelected = st.layoutBounds == true
        pointer.isSelected = st.pointerLocation == true
        dontKeep.isSelected = st.dontKeepActivities == true
        updating = false

        fun status(label: JBLabel, keys: List<DisplayKey>, text: String) {
            val err = keys.firstNotNullOfOrNull { st.errors[it] }
            if (err != null) setStatus(label, err, true) else setStatus(label, text, false)
        }
        val raw = st.values
        status(appearanceStatus, listOf(DisplayKey.APPEARANCE), "Device reports: ${raw[DisplayKey.APPEARANCE]}")
        val fontRaw = raw[DisplayKey.FONT_SIZE] ?: if (platform == Platform.ANDROID) "unset (1.0)" else "?"
        status(fontStatus, listOf(DisplayKey.FONT_SIZE),
            if (FontPreset.fromRaw(platform, raw[DisplayKey.FONT_SIZE]) == null) "Custom value on the device: $fontRaw" else "Device reports: $fontRaw")
        if (platform == Platform.ANDROID) {
            fun scale(k: DisplayKey) = raw[k] ?: "unset"
            status(animationsStatus, DisplayKey.ANIMATIONS,
                "window ${scale(DisplayKey.WINDOW_SCALE)}, transition ${scale(DisplayKey.TRANSITION_SCALE)}, animator ${scale(DisplayKey.ANIMATOR_SCALE)}")
            status(touchesStatus, listOf(DisplayKey.SHOW_TOUCHES), "show_touches: ${raw[DisplayKey.SHOW_TOUCHES] ?: "unset"}")
            status(layoutStatus, listOf(DisplayKey.LAYOUT_BOUNDS), "debug.layout: ${raw[DisplayKey.LAYOUT_BOUNDS] ?: "empty"}")
            status(pointerStatus, listOf(DisplayKey.POINTER_LOCATION), "pointer_location: ${raw[DisplayKey.POINTER_LOCATION] ?: "unset"}")
            status(dontKeepStatus, listOf(DisplayKey.DONT_KEEP_ACTIVITIES), "always_finish_activities: ${raw[DisplayKey.DONT_KEEP_ACTIVITIES] ?: "unset"}")
        }
        updateEnabled()
    }

    private fun refresh() {
        val dev = device ?: return
        if (!supported(dev)) return
        val my = ++gen
        busy = true
        setStatus(general, "Reading...", false)
        updateEnabled()
        refreshLocale()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val b = DeviceServices.display(dev) ?: error("Not supported for this device")
                b to b.read()
            }
            SwingUtilities.invokeLater {
                if (my != gen) return@invokeLater
                busy = false
                result.onSuccess { (b, st) ->
                    backend = b
                    state = st
                    setStatus(general, " ", false)
                    showState(st)
                }.onFailure {
                    state = null
                    setStatus(general, it.message ?: it.javaClass.simpleName, true)
                    updateEnabled()
                }
            }
        }
    }

    /** Runs [work] (the writes, returning the first error or null) on a pooled thread, reads the device back and shows it; an error goes to [target]. */
    private fun apply(target: JBLabel, afterOk: () -> Unit = {}, onDone: () -> Unit = {}, work: (DisplayBackend) -> String?) {
        val b = backend ?: return
        if (device == null || state == null || busy) return
        val my = ++gen
        busy = true
        updateEnabled()
        setStatus(target, "Applying...", false)
        ApplicationManager.getApplication().executeOnPooledThread {
            val failure = work(b)
            val readBack = runCatching { b.read() }
            SwingUtilities.invokeLater {
                if (my != gen) return@invokeLater
                busy = false
                val st = readBack.getOrNull()
                if (st != null) { state = st; showState(st) } else updateEnabled()
                if (failure != null) setStatus(target, failure, true)
                else {
                    afterOk()
                    if (st == null) setStatus(general, "Applied, but reading the device back failed: ${readBack.exceptionOrNull()?.message}", true)
                }
                onDone()
            }
        }
    }

    private fun change(changes: List<Pair<DisplayKey, String?>>, target: JBLabel) {
        val dev = device ?: return
        val before = state ?: return
        apply(target) { actions.change(dev.id, it, before, changes) }
    }

    /** Restores what this tab changed. The Display part and the locale part run independently: either may be unavailable. */
    private fun resetToDefaults() {
        val dev = device ?: return
        if (busy || localeBusy) return
        val b = if (state != null) backend else null
        val lb = if (localeState != null) localeBackend else null
        if (b == null && lb == null) return
        val my = ++gen
        val myLocale = ++localeGen
        if (b != null) busy = true
        if (lb != null) localeBusy = true
        updateEnabled()
        updateLocaleEnabled()
        setStatus(general, "Applying...", false)
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = b?.let { actions.reset(dev.id, dev.platform, it) }
            val localeOutcome = lb?.let { localeActions.reset(dev.id, it) }
            val readBack = b?.let { runCatching { it.read() } }
            val localeBack = lb?.let { runCatching { it.read() } }
            SwingUtilities.invokeLater {
                if (my != gen || myLocale != localeGen) return@invokeLater
                busy = false
                localeBusy = false
                readBack?.getOrNull()?.let { state = it; showState(it) }
                if (lb != null) localeBack?.getOrNull()?.let { localeState = it; showLocale(lb, it) }
                updateEnabled()
                updateLocaleEnabled()
                val failure = outcome?.failure ?: localeOutcome?.failure
                if (failure != null) {
                    setStatus(general, failure, true)
                } else {
                    val restored = outcome?.restored == true || localeOutcome?.restored == true
                    val note = localeOutcome?.note
                    setStatus(general, (if (restored) "Restored what this tab changed" else "Reset to the platform defaults") + (note?.let { ". $it" } ?: ""), false)
                }
            }
        }
    }
}
