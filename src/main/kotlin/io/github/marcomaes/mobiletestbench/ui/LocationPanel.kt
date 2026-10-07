package io.github.marcomaes.mobiletestbench.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.marcomaes.mobiletestbench.DeviceServices
import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.location.LocationOverride
import io.github.marcomaes.mobiletestbench.core.location.Place
import io.github.marcomaes.mobiletestbench.settings.MobileTestBenchSettings
import java.awt.FlowLayout
import java.awt.event.ItemEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Overrides the device location with one point (mobilecli `device location set|clear`), with preset and user saved places. */
class LocationPanel : JPanel(), DeviceToolTab {

    override val title = "Location"
    override val component: JComponent get() = this
    override val scrollable: Boolean get() = true
    override fun deviceChanged(device: Device?) {
        this.device = device
        if (device == null) setStatus("Not connected", error = false)
        else if (!busy) setStatus(" ", error = false)
        updateButtons()
    }
    override fun dispose() = Unit

    private var device: Device? = null
    private var busy = false
    private var updating = false

    private val combo = JComboBox<Place>()
    private val lat = JBTextField(10)
    private val lon = JBTextField(10)
    private val set = JButton("Set location")
    private val clear = JButton("Clear override")
    private val save = JButton("Save place...")
    private val delete = JButton("Delete place")
    private val status = WrappingLabel(" ").apply { border = JBUI.Borders.empty(2, 8) }

    private val saved get() = LocationOverride.decodePlaces(MobileTestBenchSettings.getInstance().state.savedPlaces)

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        combo.renderer = javax.swing.DefaultListCellRenderer().let { base ->
            javax.swing.ListCellRenderer<Place> { list, value, index, sel, focus ->
                base.getListCellRendererComponent(list, value?.let { describe(it) } ?: "(custom)", index, sel, focus)
            }
        }
        reloadPlaces(null)
        combo.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED && !updating) (combo.selectedItem as? Place)?.let { fill(it) }
            updateButtons()
        }
        listOf(lat, lon).forEach { field ->
            field.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = changed(field)
                override fun removeUpdate(e: DocumentEvent) = changed(field)
                override fun changedUpdate(e: DocumentEvent) = Unit
            })
            field.addActionListener { setLocation() }
        }
        set.addActionListener { setLocation() }
        clear.addActionListener { clearOverride() }
        save.addActionListener { savePlace() }
        delete.addActionListener { deletePlace() }

        fun row(vararg parts: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(3))).apply { parts.forEach { add(it) } }
        add(row(WrappingLabel("Place:"), combo))
        add(row(WrappingLabel("Latitude:"), lat, WrappingLabel("Longitude:"), lon))
        add(row(set, clear, save, delete))
        add(status)
        add(row(WrappingLabel("<html>The override is held by the mobilecli background process; apps need location permission to see it. Paste \"lat, lon\" into either field.</html>").apply { foreground = JBColor.GRAY }))
        setStatus("Not connected", error = false)
        updateButtons()
    }

    private fun describe(p: Place) = "${p.name} (${LocationOverride.format(p.latitude)}, ${LocationOverride.format(p.longitude)})"

    /** Rebuilds the combo: presets, then saved places. */
    private fun reloadPlaces(select: Place?) {
        updating = true
        combo.removeAllItems()
        combo.addItem(null)
        (LocationOverride.PRESETS + saved).forEach { combo.addItem(it) }
        combo.selectedItem = select
        updating = false
    }

    private fun fill(p: Place) {
        updating = true
        lat.text = LocationOverride.format(p.latitude)
        lon.text = LocationOverride.format(p.longitude)
        updating = false
        updateButtons()
    }

    private fun changed(field: JBTextField) {
        if (updating) return
        // A pasted "lat, lon" is split over both fields (after the document event, so not inside it).
        LocationOverride.parsePair(field.text)?.let { (a, b) ->
            SwingUtilities.invokeLater { updating = true; lat.text = a; lon.text = b; updating = false; updateButtons() }
            return
        }
        updating = true
        combo.selectedItem = null
        updating = false
        updateButtons()
    }

    private fun updateButtons() {
        val latError = if (lat.text.isBlank()) null else LocationOverride.validateLatitude(lat.text)
        val lonError = if (lon.text.isBlank()) null else LocationOverride.validateLongitude(lon.text)
        lat.putClientProperty("JComponent.outline", if (latError != null) "error" else null)
        lon.putClientProperty("JComponent.outline", if (lonError != null) "error" else null)
        lat.toolTipText = latError
        lon.toolTipText = lonError
        val valid = LocationOverride.parse(lat.text, lon.text) is LocationOverride.Parsed.Ok
        set.isEnabled = device != null && !busy && valid
        // Clear is idempotent in mobilecli, so it is always offered while connected.
        clear.isEnabled = device != null && !busy
        save.isEnabled = valid
        val selected = combo.selectedItem as? Place
        delete.isEnabled = selected != null && !LocationOverride.isPreset(selected)
    }

    private fun setStatus(text: String, error: Boolean) {
        status.text = text.ifBlank { " " }
        status.foreground = if (error) JBColor.RED else JBColor.foreground()
    }

    private fun setLocation() {
        val dev = device ?: return
        if (busy) return
        val p = LocationOverride.parse(lat.text, lon.text)
        if (p !is LocationOverride.Parsed.Ok) { setStatus((p as LocationOverride.Parsed.Invalid).reason, error = true); return }
        run(dev, "Setting location...", LocationOverride.setArgs(p.latitude, p.longitude),
            "Location set to ${LocationOverride.format(p.latitude)}, ${LocationOverride.format(p.longitude)} on ${dev.name}")
    }

    private fun clearOverride() {
        val dev = device ?: return
        if (busy) return
        run(dev, "Clearing override...", LocationOverride.CLEAR_ARGS, "Override cleared on ${dev.name}")
    }

    private fun run(dev: Device, progress: String, args: List<String>, success: String) {
        busy = true
        updateButtons()
        setStatus(progress, error = false)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { DeviceServices.mobilecliCall(dev, *args.toTypedArray()) }
            SwingUtilities.invokeLater {
                busy = false
                if (dev === device) {
                    result.onSuccess { setStatus(success, error = false) }
                        .onFailure { setStatus(LocationOverride.friendlyError(it.message ?: ""), error = true) }
                } else {
                    // The selection moved on while the call ran; drop the stale progress text.
                    setStatus("", error = false)
                }
                updateButtons()
            }
        }
    }

    private fun savePlace() {
        val p = LocationOverride.parse(lat.text, lon.text) as? LocationOverride.Parsed.Ok ?: return
        val name = Messages.showInputDialog(this, "Name for ${LocationOverride.format(p.latitude)}, ${LocationOverride.format(p.longitude)}:", "Save Place", null)?.trim() ?: return
        val place = Place(name, p.latitude, p.longitude)
        val updated = LocationOverride.savePlace(saved, place)
        if (updated == null) { setStatus("Enter a name that is not one of the preset places", error = true); return }
        val state = MobileTestBenchSettings.getInstance().state
        state.savedPlaces = updated.map { LocationOverride.encodePlace(it) }.toMutableList()
        reloadPlaces(updated.last())
        setStatus("Saved place ${updated.last().name}", error = false)
        updateButtons()
    }

    private fun deletePlace() {
        val selected = combo.selectedItem as? Place ?: return
        if (LocationOverride.isPreset(selected)) return
        val state = MobileTestBenchSettings.getInstance().state
        state.savedPlaces = LocationOverride.deletePlace(saved, selected).map { LocationOverride.encodePlace(it) }.toMutableList()
        reloadPlaces(null)
        setStatus("Deleted place ${selected.name}", error = false)
        updateButtons()
    }
}
