package io.github.marcomaes.mobiletestbench.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.github.marcomaes.mobiletestbench.DeviceServices
import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.links.UrlLauncher
import io.github.marcomaes.mobiletestbench.settings.MobileTestBenchSettings
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Opens a URL or deep link on the connected device, with a history that is remembered across sessions. */
class UrlLauncherPanel : JPanel(BorderLayout()), DeviceToolTab {

    override val title = "Open URL"
    override val component: JComponent get() = this
    override fun deviceChanged(device: Device?) {
        this.device = device
        if (device == null) setStatus("Not connected", error = false)
        else if (!busy) setStatus(" ", error = false)
        updateButtons()
    }
    override fun dispose() = Unit

    private var device: Device? = null
    private var busy = false

    private val combo = JComboBox<String>().apply { isEditable = true; prototypeDisplayValue = "https://www.example.com/some/long/deep/link/path".repeat(2) }
    private val open = JButton("Open")
    private val clear = JButton("Clear history")
    private val status = JBLabel(" ").apply { border = JBUI.Borders.empty(2, 8) }

    private val editor get() = combo.editor.editorComponent as javax.swing.text.JTextComponent

    init {
        reloadHistory()
        combo.selectedItem = ""
        open.addActionListener { openUrl() }
        clear.addActionListener {
            MobileTestBenchSettings.getInstance().state.urlHistory = mutableListOf()
            reloadHistory()
            setStatus("History cleared", error = false)
        }
        // Enter in the field opens (selecting a history entry only fills the field).
        combo.editor.addActionListener { openUrl() }
        val top = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
            add(JBLabel("URL or deep link:"))
            add(combo)
            add(open)
            add(clear)
        }
        add(top, BorderLayout.NORTH)
        add(status, BorderLayout.CENTER)
        setStatus("Not connected", error = false)
        updateButtons()
    }

    private fun reloadHistory() {
        val text = (combo.editor.item as? String) ?: ""
        combo.removeAllItems()
        MobileTestBenchSettings.getInstance().state.urlHistory.forEach { combo.addItem(it) }
        combo.selectedItem = text
        clear.isEnabled = combo.itemCount > 0
    }

    private fun updateButtons() { open.isEnabled = device != null && !busy }

    private fun setStatus(text: String, error: Boolean) {
        status.text = text.ifBlank { " " }
        status.foreground = if (error) JBColor.RED else JBColor.foreground()
    }

    private fun openUrl() {
        val dev = device ?: return
        if (busy) return
        val url = when (val v = UrlLauncher.validate(editor.text)) {
            is UrlLauncher.Validation.Invalid -> { setStatus(v.reason, error = true); return }
            is UrlLauncher.Validation.Ok -> v.url
        }
        busy = true
        updateButtons()
        setStatus("Opening $url...", error = false)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { DeviceServices.mobilecliCall(dev, *UrlLauncher.args(url).toTypedArray()) }
            SwingUtilities.invokeLater {
                busy = false
                result.onSuccess {
                    val settings = MobileTestBenchSettings.getInstance().state
                    settings.urlHistory = UrlLauncher.addToHistory(settings.urlHistory, url).toMutableList()
                    reloadHistory()
                    combo.selectedItem = url
                    if (dev === device) setStatus("Opened $url on ${dev.name}", error = false)
                }.onFailure {
                    if (dev === device) setStatus(UrlLauncher.friendlyError(it.message ?: ""), error = true)
                }
                updateButtons()
            }
        }
    }
}
