package io.github.marcomaes.mobiletestbench.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.tabs.JBTabs
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.Rect
import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** One tab of the Device Tools panel. All methods run on the EDT; do blocking work on a pooled thread. */
interface DeviceToolTab {
    val title: String
    val component: JComponent

    /** A form that can be taller than the panel: it is shown in a scroll pane that follows the panel width. */
    val scrollable: Boolean get() = false

    /** The connected device changed (null: disconnected). Only called while the panel is open, plus once with the current device when it opens and with null when it closes (so streams stop). */
    fun deviceChanged(device: Device?)

    /** The tab became visible (selected while the panel is open, or the panel opened on it). Load data lazily here. */
    fun shown() = Unit

    /** The tab is no longer visible (another tab selected or the panel closed). */
    fun hidden() = Unit

    /** The panel is disposed: stop everything. */
    fun dispose()
}

/** The bottom "Device Tools" panel: a tabbed container of [DeviceToolTab]s. */
class DeviceToolsPanel(
    project: Project,
    /** Draws a rect (device coordinates) on the mirror, null clears it. */
    highlight: (Rect?) -> Unit = {},
    /** The native hierarchy of the connected device; blocks, so only called on pooled threads. Null when not available. */
    nativeHierarchy: () -> UiNode? = { null },
    /** The frame the mirror shows (the frozen one while Freeze is on), null when there is none. */
    currentFrame: () -> java.awt.image.BufferedImage? = { null },
    /** The logical size of the device; null when unknown. */
    logicalSize: () -> io.github.marcomaes.mobiletestbench.core.Size? = { null },
    /** Draws changed regions (logical coordinates) on the mirror; an empty list clears them. */
    diffBounds: (List<Rect>) -> Unit = {},
    /** The id of the foreground app (null when unknown or the home screen); used to name baselines. */
    foregroundApp: () -> String? = { null },
) : JPanel(BorderLayout()) {

    /** The single place where tabs are registered: a new feature adds one line. */
    private val tabs: List<DeviceToolTab> = listOf(
        LogPanel(project),
        AppsPanel(project) { id -> logPanel.setAppFilter(id); selectTab("Log") },
        UrlLauncherPanel(),
        CrashesPanel(project),
        LocationPanel(),
        DisplayPanel(),
        InputPanel(),
        NetworkPanel(),
        BatteryPanel(),
        BiometricsPanel(),
        WebViewPanel(highlight, nativeHierarchy),
        FilesPanel(project),
        PerformancePanel(),
        VisualPanel(project, currentFrame, logicalSize, diffBounds, foregroundApp),
        NotificationsPanel(),
    )

    // 14 tabs do not fit in one row of a narrow panel. The platform's own tab component (the one tool windows use)
    // scrolls, has an overflow menu and follows the IDE theme; a plain Swing JTabbedPane drew blank tab headers.
    private val disposable = Disposer.newDisposable("MobileTestBenchDeviceTools")
    private val jbTabs: JBTabs = JBTabsFactory.createTabs(project, disposable)
    private val infos: List<TabInfo> = tabs.map { tab ->
        val content: JComponent = if (tab.scrollable) {
            com.intellij.ui.ScrollPaneFactory.createScrollPane(WidthTrackingPanel(tab.component), true).apply {
                horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            }
        } else {
            tab.component
        }
        TabInfo(content).setText(tab.title).setObject(tab)
    }
    private var device: Device? = null
    private var open = false
    private var current: DeviceToolTab? = null

    /** Called when the user (or [selectTab]) selects another tab; used to remember the choice. */
    var onTabSelected: () -> Unit = {}

    val tabTitles: List<String> get() = tabs.map { it.title }
    val selectedTitle: String get() = selectedTab()?.title.orEmpty()

    private fun selectedTab(): DeviceToolTab? = jbTabs.selectedInfo?.`object` as? DeviceToolTab

    init {
        infos.forEach { jbTabs.addTab(it) }
        infos.firstOrNull()?.let { jbTabs.select(it, false) }
        jbTabs.addListener(object : TabsListener {
            override fun selectionChanged(oldSelection: TabInfo?, newSelection: TabInfo?) {
                switchTo(newSelection?.`object` as? DeviceToolTab)
                onTabSelected()
            }
        })
        add(jbTabs.component, BorderLayout.CENTER)
    }

    /** What the panel shows for a tab (the scroll pane around a form). */
    fun contentOf(title: String): JComponent? = infos.firstOrNull { it.text == title }?.component

    fun <T : DeviceToolTab> tab(type: Class<T>): T? = tabs.filterIsInstance(type).firstOrNull()

    /** The Log tab, for features that want to set its filters. */
    val logPanel: LogPanel get() = tab(LogPanel::class.java)!!

    /** Selects the tab with this title; false when there is none. */
    fun selectTab(title: String): Boolean {
        val info = infos.firstOrNull { it.text == title } ?: return false
        jbTabs.select(info, true)
        return true
    }

    /** The connected device changed while the panel is open: every tab is told. */
    fun deviceChanged(newDevice: Device?) {
        device = newDevice
        tabs.forEach { it.deviceChanged(newDevice) }
    }

    /** The panel was opened with [newDevice] connected (or null). */
    fun opened(newDevice: Device?) {
        open = true
        deviceChanged(newDevice)
        switchTo(selectedTab())
    }

    fun closed() {
        open = false
        switchTo(null)
        // Streams (the log) are stopped while closed; opened() restarts them.
        tabs.forEach { it.deviceChanged(null) }
    }

    fun dispose() {
        switchTo(null)
        tabs.forEach { it.dispose() }
        Disposer.dispose(disposable)
    }

    private fun switchTo(next: DeviceToolTab?) {
        val target = if (open) next else null
        if (target === current) return
        current?.hidden()
        current = target
        target?.shown()
    }
}
