package io.github.marcomaes.mobiletestbench.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.marcomaes.mobiletestbench.DeviceServices
import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.DeviceKey
import io.github.marcomaes.mobiletestbench.core.Orientation
import io.github.marcomaes.mobiletestbench.core.coords.CoordinateText
import io.github.marcomaes.mobiletestbench.core.coords.ElementInfo
import io.github.marcomaes.mobiletestbench.core.coords.HoverPoint
import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.freeze.FreezeState
import io.github.marcomaes.mobiletestbench.core.inspector.HierarchyQueries
import io.github.marcomaes.mobiletestbench.core.pageobject.PageObjectCandidates
import io.github.marcomaes.mobiletestbench.core.power.PowerAction
import io.github.marcomaes.mobiletestbench.core.project.ForegroundText
import io.github.marcomaes.mobiletestbench.core.project.LastDevice
import io.github.marcomaes.mobiletestbench.core.project.LayoutPatch
import io.github.marcomaes.mobiletestbench.core.project.ProjectLayout
import io.github.marcomaes.mobiletestbench.core.apps.Apps
import io.github.marcomaes.mobiletestbench.core.apps.ForegroundApp
import io.github.marcomaes.mobiletestbench.settings.ProjectViewState
import io.github.marcomaes.mobiletestbench.settings.MobileTestBenchSettings
import io.github.marcomaes.mobiletestbench.core.reconnect.ReconnectEvent
import io.github.marcomaes.mobiletestbench.core.reconnect.ReconnectPhase
import io.github.marcomaes.mobiletestbench.core.reconnect.ReconnectWatcher
import io.github.marcomaes.mobiletestbench.core.power.Polling
import io.github.marcomaes.mobiletestbench.core.recording.Check
import io.github.marcomaes.mobiletestbench.core.recording.Checks
import io.github.marcomaes.mobiletestbench.core.recording.CountIs
import io.github.marcomaes.mobiletestbench.core.recording.HierarchySnapshots
import io.github.marcomaes.mobiletestbench.core.recording.ScreenRecording
import io.github.marcomaes.mobiletestbench.core.recording.ScreenRecordingLimit
import io.github.marcomaes.mobiletestbench.core.recording.ReplayOptions
import io.github.marcomaes.mobiletestbench.core.recording.ScriptPlayer
import io.github.marcomaes.mobiletestbench.core.recording.ScriptRecorder
import io.github.marcomaes.mobiletestbench.core.inspector.LocatorExtraction
import io.github.marcomaes.mobiletestbench.core.inspector.ShowLocator
import io.github.marcomaes.mobiletestbench.core.inspector.ShowLocatorResult
import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import io.github.marcomaes.mobiletestbench.core.session.DeviceSession
import io.github.marcomaes.mobiletestbench.core.session.FrameListener
import io.github.marcomaes.mobiletestbench.settings.MobileTestBenchConfigurable
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import io.github.marcomaes.mobiletestbench.core.ScreenshotAnnotator
import java.awt.BorderLayout
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.DefaultComboBoxModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Pause after the last input before the inspector re-reads the hierarchy. */
private const val AUTO_REFRESH_DELAY_MS = 700
/** Fingerprint of a sparse pixel grid; the top strip is skipped so the status bar clock does not count as a change. */
private fun screenSignature(image: BufferedImage): Int {
    var hash = 1
    val top = image.height / 20
    for (row in 0 until SIGNATURE_ROWS) {
        val y = top + (image.height - 1 - top) * row / (SIGNATURE_ROWS - 1)
        for (col in 0 until SIGNATURE_COLUMNS) {
            hash = 31 * hash + image.getRGB((image.width - 1) * col / (SIGNATURE_COLUMNS - 1), y)
        }
    }
    return hash
}
private const val SIGNATURE_COLUMNS = 24
private const val SIGNATURE_ROWS = 48
private const val RECORDING_TAB = "Recording"
/** Narrowest the splitter lets the inspector and the device screen get, in unscaled pixels. */
private const val MIN_INSPECTOR_WIDTH = 200
private const val MIN_SCREEN_WIDTH = 240
/** How long a recorded tap waits for a hierarchy taken after the previous action, before using an older one. */
private const val FRESH_WAIT_MS = 2_000L
/** Device Power: how often and how long to look for a device that came up or went away, and the pause after a reboot command. */
private const val POWER_POLL_MS = 2_000L
private const val POWER_UP_TIMEOUT_MS = 60_000L
private const val POWER_GONE_TIMEOUT_MS = 10_000L
private const val POWER_REBOOT_GRACE_MS = 3_000L
/** How long a tap waits when there is no hierarchy at all yet. */
private const val MAX_WAIT_MS = 15_000L
/** Foreground app polling: interval, call timeout (s) and the delay before a changed layout is saved. */
private const val FOREGROUND_POLL_MS = 3_000
private const val FOREGROUND_TIMEOUT_S = 6L
private const val LAYOUT_SAVE_DELAY_MS = 1_000
/** How long the result of "Show Locator on Device" stays in the status line. */
private const val LOCATOR_STATUS_HOLD_MS = 6_000L

/** An error longer than this is a how-to (such as the iPhone agent install): it wraps and stays for a minute. */
private const val LONG_STATUS_CHARS = 120
private const val LONG_STATUS_HOLD_MS = 60_000L

class DeviceViewerPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable, LocatorTarget {

    private val log = logger<DeviceViewerPanel>()

    private val deviceModel = DefaultComboBoxModel<Device>()
    private val deviceCombo = ComboBox(deviceModel).apply {
        renderer = object : ColoredListCellRenderer<Device?>() {
            override fun customizeCellRenderer(list: JList<out Device?>, value: Device?, index: Int, selected: Boolean, hasFocus: Boolean) {
                val inactive = value != null && !value.isUsable
                append(value?.displayName ?: "No devices found", if (inactive) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }
        addActionListener { onDeviceSelected(selectedItem as? Device) }
    }
    private val screen = ScreenView()
    private val inspector = InspectorPanel(
        project,
        ::onInspectorSelection,
        { nodes -> screen.matchBounds = nodes.mapNotNull { it.bounds } },
        { nodes -> screen.issueBounds = nodes.mapNotNull { it.bounds } },
        density = { session?.density() },
        currentFrame = { screen.currentFrame() },
        logicalSize = { session?.logicalSize() },
    )
    private val status = WrappingLabel("Not connected").apply { border = JBUI.Borders.empty(2, 8) }

    /** Coordinates and element under the mouse; separate from [status] so the fps line is not overwritten. */
    private val hoverLine = JBLabel(" ").apply {
        border = JBUI.Borders.empty(2, 8)
        isVisible = false
    }
    /** The foreground app, live (see the foreground region); click copies its package id. */
    private val foregroundLabel = JBLabel(" ").apply {
        border = JBUI.Borders.empty(2, 8)
        isVisible = false
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    }
    private val cancelReconnectLink = com.intellij.ui.components.ActionLink("Cancel") { cancelReconnect() }.apply {
        isVisible = false
        border = JBUI.Borders.empty(2, 8)
    }
    private val splitter = OnePixelSplitter(false, ProjectLayout.DEFAULT_MIRROR)
    private val toolsPanel = DeviceToolsPanel(
        project,
        highlight = { rect -> screen.webBounds = rect },
        nativeHierarchy = { session?.hierarchy() },
        currentFrame = { screen.currentFrame() },
        logicalSize = { screen.logicalSizeProvider() },
        diffBounds = { rects -> screen.diffBounds = rects },
        foregroundApp = { foregroundApp?.takeUnless { it.isHome }?.id },
    )
    private val toolsSplitter = OnePixelSplitter(true, ProjectLayout.DEFAULT_TOOLS)
    private var toolsVisible = false

    /** Input runs on one background thread so taps and typing arrive in order. */
    private val inputExecutor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Mobile Test Bench input", 1)

    @Volatile private var session: DeviceSession? = null
    @Volatile private var hierarchy: UiNode? = null
    private var inspectEnabled = false
    /** Recorded steps survive stopping a recording; Clear or a new device platform starts over. */
    @Volatile private var recorder: ScriptRecorder? = null
    @Volatile private var recording = false
    @Volatile private var snapshots: HierarchySnapshots? = null
    private val recorderPanel = RecorderPanel(project, { clearRecording() }, { range -> startReplay(range) }, { replaying = false }, { armed -> setAssertArmed(armed) }, { rows -> scrollUntilVisible(rows) }, { rows, ms -> setStepTimeout(rows, ms) }, { rows, dir, max -> setScrollOptions(rows, dir, max) })
    @Volatile private var replaying = false
    @Volatile private var assertArmed = false
    @Volatile private var screenRecording: ScreenRecording? = null
    @Volatile private var screenRecordingStarting = false
    /** Bumped on every start and stop so an auto stop timer of an earlier recording does nothing. */
    private val screenRecordingGeneration = AtomicInteger()
    /** Last orientation read from the device; null while unknown. Never assumed: it is re-read after every change and on connect. */
    @Volatile private var orientation: Orientation? = null
    @Volatile private var orientationBusy = false
    private val replayExecutor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Mobile Test Bench replay", 1)
    private val freeze = FreezeState()
    private var refreshInFlight = false
    private var refreshAgain = false

    /** Re-reads the hierarchy shortly after the last input, so the tree follows what happens on the device. */
    private val autoRefreshTimer = Timer(AUTO_REFRESH_DELAY_MS) { refreshHierarchy(quiet = true) }.apply { isRepeats = false }
    private val frameCounter = AtomicInteger()
    /** Cheap fingerprint of the last frame; a different one means the screen changed and the hierarchy is stale. */
    @Volatile private var lastSignature = 0
    private val fpsTimer = Timer(1000) { updateStatus() }

    init {
        screen.logicalSizeProvider = { session?.logicalSize() }
        screen.listener = ScreenInputHandler()
        screen.hoverListener = { point -> showHover(point) }
        screen.elementProvider = { x, y -> elementAt(x, y) }
        screen.gridVisible = runCatching { MobileTestBenchSettings.getInstance().state.showGrid }.getOrDefault(false)
        inspector.addTab(RECORDING_TAB, recorderPanel)
        project.getService(LocatorTargets::class.java)?.register(this)

        // The inspector's content would force a wide minimum; the screen has none and can be dragged to nothing.
        inspector.minimumSize = java.awt.Dimension(JBUI.scale(MIN_INSPECTOR_WIDTH), 0)
        screen.minimumSize = java.awt.Dimension(JBUI.scale(MIN_SCREEN_WIDTH), 0)
        splitter.firstComponent = screen
        splitter.secondComponent = null

        val actions = DefaultActionGroup().apply {
            add(RefreshDevicesAction())
            add(ConnectAction())
            add(DisconnectAction())
            add(DevicePowerAction())
            addSeparator()
            add(KeyAction("Back", AllIcons.Actions.Back, DeviceKey.BACK))
            add(KeyAction("Home", AllIcons.Nodes.HomeFolder, DeviceKey.HOME))
            add(KeyAction("Recent Apps", AllIcons.Actions.ListFiles, DeviceKey.APP_SWITCH))
            add(TypeTextAction())
            add(OrientationToggleAction())
            addSeparator()
            add(InspectToggleAction())
            add(RefreshHierarchyAction())
            add(FreezeToggleAction())
            add(GridToggleAction())
            add(RecordToggleAction())
            add(ToolsToggleAction())
            addSeparator()
            add(PageObjectAction())
            add(ScreenshotAction())
            add(ScreenRecordToggleAction())
            add(SettingsAction())
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("Mobile Test Bench", actions, true)
        toolbar.targetComponent = this

        val top = JPanel(BorderLayout()).apply {
            add(deviceCombo, BorderLayout.WEST)
            add(toolbar.component, BorderLayout.CENTER)
        }
        setToolbar(top)

        toolsSplitter.firstComponent = splitter
        toolsSplitter.secondComponent = null
        val content = JPanel(BorderLayout()).apply {
            add(toolsSplitter, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                add(JPanel(BorderLayout()).apply {
                    add(hoverLine, BorderLayout.CENTER)
                    add(foregroundLabel, BorderLayout.EAST)
                }, BorderLayout.NORTH)
                add(status, BorderLayout.CENTER)
                add(cancelReconnectLink, BorderLayout.EAST)
            }, BorderLayout.SOUTH)
        }
        setContent(content)

        fpsTimer.start()
    }

    // region devices and session

    /** [select]: the device to select after the refresh (default: keep the current one; matched by id or emulator AVD name). */
    private fun refreshDevices(select: Device? = null) {
        status.text = "Looking for devices..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = DeviceServices.discovery().discover()
            SwingUtilities.invokeLater {
                val firstRefresh = restoreDevicePending
                restoreDevicePending = false
                val previous = select ?: deviceCombo.selectedItem as? Device
                // Refilling the model selects other entries on the way: that is not the user choosing another device.
                refillingDevices = true
                try {
                    deviceModel.removeAllElements()
                    result.devices.forEach(deviceModel::addElement)
                    (Polling.reselect(result.devices, previous)
                        ?: if (firstRefresh && select == null) rememberedDevice(result.devices) else null)
                        ?.let { deviceModel.selectedItem = it }
                } finally {
                    refillingDevices = false
                }
                if (firstRefresh && select == null) autoConnectRemembered(result.devices)
                if (reconnect.phase == ReconnectPhase.WAITING) {
                    reconnect.target?.let { status.text = ReconnectWatcher.waitingText(it, reconnect.waitedMs()) }
                    return@invokeLater
                }
                status.text = when {
                    result.devices.isEmpty() && result.warnings.isNotEmpty() -> result.warnings.joinToString("  |  ")
                    result.devices.isEmpty() -> "No running emulators, devices or booted simulators found"
                    else -> "${result.devices.size} device(s) found" +
                        result.devices.count { it.bootable }.let { if (it > 0) " ($it offline)" else "" }
                }
                if (session == null && result.devices.isEmpty() && result.warnings.isNotEmpty()) screen.placeholder = result.warnings.joinToString("\n")
                result.warnings.forEach { log.info("Discovery: $it") }
            }
        }
    }

    // region device power

    /** Bumped for every power operation and on dispose: a result that arrives for an older operation is dropped. */
    private val powerGeneration = AtomicInteger()
    @Volatile private var powerBusy = false

    /** Offline devices cannot be mirrored: say what to do instead of leaving a stale placeholder. */
    private fun onDeviceSelected(device: Device?) {
        if (!refillingDevices && device != null && reconnect.phase == ReconnectPhase.WAITING && !device.isSameDevice(reconnect.target)) cancelReconnect()
        if (session == null && device?.bootable == true) screen.placeholder = "${device.name} is offline. Use Device Power > Boot."
    }

    private fun runPower(action: PowerAction, device: Device) {
        if (powerBusy || !action.isAvailableFor(device)) return
        if (action.needsConfirmation &&
            Messages.showYesNoDialog(project, PowerAction.confirmText(action, device), "${action.label} Device", Messages.getWarningIcon()) != Messages.YES
        ) return
        val generation = powerGeneration.incrementAndGet()
        // The session must not outlive the device: disconnect first when it is the one being shut down or rebooted.
        val wantBack = action != PowerAction.BOOT && session?.device?.isSameDevice(device) == true && MobileTestBenchSettings.getInstance().state.reconnectAutomatically
        if (action != PowerAction.BOOT && session?.device?.isSameDevice(device) == true) disconnect()
        powerBusy = true
        // The watcher waits for the device to come back, but stays quiet until this operation is over (see reconnectTick).
        if (wantBack) startWaiting(device)
        status.text = PowerAction.progressText(action, device)
        ApplicationManager.getApplication().executeOnPooledThread {
            var error: String? = null
            var up: Device? = null
            var timedOut = false
            try {
                DeviceServices.powerCall(device, action)
                val stale = { powerGeneration.get() != generation }
                if (action == PowerAction.SHUTDOWN) {
                    // adb can list a just stopped emulator for a moment longer.
                    Polling.until(POWER_GONE_TIMEOUT_MS, POWER_POLL_MS, cancelled = stale) {
                        true.takeIf { Polling.usableMatch(DeviceServices.discovery().discover().devices, device) == null }
                    }
                } else {
                    // After a reboot the old instance may still look usable for a moment.
                    if (action == PowerAction.REBOOT) Thread.sleep(POWER_REBOOT_GRACE_MS)
                    SwingUtilities.invokeLater { if (!stale()) status.text = "Waiting for ${device.name} to come up..." }
                    up = Polling.until(POWER_UP_TIMEOUT_MS, POWER_POLL_MS, cancelled = stale) {
                        Polling.usableMatch(DeviceServices.discovery().discover().devices, device)
                    }
                    timedOut = up == null && !stale()
                }
            } catch (e: Exception) {
                log.info("Power ${action.name} failed", e)
                error = e.message
            }
            SwingUtilities.invokeLater {
                if (powerGeneration.get() != generation) return@invokeLater
                powerBusy = false
                refreshDevices(up ?: device)
                val waiting = reconnect.phase == ReconnectPhase.WAITING && reconnect.target?.isSameDevice(device) == true
                when {
                    waiting && error != null -> notify("${action.label} of ${device.name} failed: $error. Still waiting for it to come back.", NotificationType.ERROR)
                    waiting && timedOut -> notify("${device.name} did not appear within ${POWER_UP_TIMEOUT_MS / 1000} s. Still waiting for it to come back.", NotificationType.WARNING)
                    waiting && up != null -> {} // the watcher reconnects on its next poll
                    waiting -> notify("${device.name} was shut down. The panel reconnects when it is back.", NotificationType.INFORMATION)
                    error != null -> notify("${action.label} of ${device.name} failed: $error", NotificationType.ERROR)
                    timedOut -> notify("${device.name} did not appear within ${POWER_UP_TIMEOUT_MS / 1000} s. Press Refresh Devices when it has started.", NotificationType.WARNING)
                    up != null -> offerConnect(up, if (action == PowerAction.REBOOT) "rebooted" else "booted")
                    else -> notify("${device.name} was shut down.", NotificationType.INFORMATION)
                }
            }
        }
    }

    /** A notification with a Connect button; the device is not connected on its own (a reboot's session cannot be restored reliably). */
    private fun offerConnect(device: Device, verb: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Mobile Test Bench")
            .createNotification("${device.name} $verb. Connect to it?", NotificationType.INFORMATION)
            .addAction(com.intellij.notification.NotificationAction.createSimpleExpiring("Connect") {
                val target = (0 until deviceModel.size).map { deviceModel.getElementAt(it) }.firstOrNull { it.isSameDevice(device) && it.isUsable } ?: device
                deviceModel.selectedItem = target
                connect(target)
            })
            .notify(project)
    }

    private inner class DevicePowerAction : PanelAction("Device Power", AllIcons.Actions.Lightning) {
        override fun actionPerformed(e: AnActionEvent) {
            val device = deviceCombo.selectedItem as? Device ?: return
            val group = DefaultActionGroup()
            for (action in PowerAction.values()) {
                group.add(object : DumbAwareAction(action.label) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = !powerBusy && action.isAvailableFor(device)
                    }
                    override fun actionPerformed(e: AnActionEvent) = runPower(action, device)
                })
            }
            JBPopupFactory.getInstance()
                .createActionGroupPopup("Device Power", group, e.dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
                .showUnderneathOf(e.inputEvent?.component ?: this@DeviceViewerPanel)
        }

        override fun update(e: AnActionEvent) {
            val device = deviceCombo.selectedItem as? Device
            e.presentation.isEnabled = !powerBusy && device != null && PowerAction.values().any { it.isAvailableFor(device) }
            e.presentation.description = "Boot, shut down or reboot the selected emulator or simulator"
        }
    }

    // endregion

    /** [automatic]: a reconnect by the watcher; when it fails the watcher goes back to waiting instead of giving up. */
    private fun connect(device: Device, automatic: Boolean = false) {
        disconnect()
        val watchGeneration = reconnectGeneration.get()
        screen.placeholder = "Connecting to ${device.name}..."
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val newSession = DeviceServices.createSession(device)
                session = newSession
                newSession.start(SessionListener(newSession))
                SwingUtilities.invokeLater {
                    updateStatus()
                    if (!newSession.supportsInput) {
                        notify("Input is not available for ${device.name}. For iOS, start WebDriverAgent and check the WDA URL in the settings.", NotificationType.WARNING)
                    }
                    if (inspectEnabled) refreshHierarchy()
                    if (toolsVisible) toolsPanel.deviceChanged(device)
                    if (session === newSession && reconnectGeneration.get() == watchGeneration) startWatching(device)
                    if (session === newSession) {
                        remember(LayoutPatch(lastDeviceId = device.id, lastDeviceName = device.name))
                        pollForeground()
                    }
                }
                readOrientation(newSession)
            } catch (e: Exception) {
                log.warn(e)
                SwingUtilities.invokeLater {
                    if (automatic) {
                        // Usable in discovery but not ready yet (booting): keep waiting.
                        if (reconnectGeneration.get() == watchGeneration && session == null) startWaiting(device)
                        return@invokeLater
                    }
                    screen.placeholder = "Could not connect: ${e.message}"
                    notify("Could not connect to ${device.name}: ${e.message}", NotificationType.ERROR)
                }
            }
        }
    }

    /** [keepWatching]: the reconnect watcher itself closes a lost session and keeps waiting; every other disconnect ends the watch. */
    private fun disconnect(keepWatching: Boolean = false) {
        if (!keepWatching) cancelReconnect()
        val old = session ?: return
        session = null
        showForeground(null, clear = true)
        setFrozen(false, refresh = false)
        autoRefreshTimer.stop()
        if (screenRecording != null) stopScreenRecording()
        if (recording) setRecording(false)
        if (toolsVisible) toolsPanel.deviceChanged(null)
        hierarchy = null
        orientation = null
        ApplicationManager.getApplication().executeOnPooledThread { runCatching { old.close() } }
        screen.setFrame(null)
        screen.placeholder = "Disconnected"
        screen.selectedBounds = null
        screen.webBounds = null
        screen.hoverBounds = null
        screen.matchBounds = emptyList()
        locatorHighlight = false
        inspector.setMessage("No hierarchy")
        statusHoldUntil = 0
        updateStatus()
    }

    // region reconnect

    private val reconnect: ReconnectWatcher = ReconnectWatcher({ ReconnectWatcher.conclusive(DeviceServices.discovery().discover(), reconnect.target) })
    /** Bumped whenever the watch is started, stopped or replaced: a scheduled poll or result of an older watch is dropped. */
    private val reconnectGeneration = AtomicInteger()
    @Volatile private var refillingDevices = false

    /** Watch a freshly connected device (if the setting is on). */
    private fun startWatching(device: Device) {
        if (!MobileTestBenchSettings.getInstance().state.reconnectAutomatically) return
        reconnect.watch(device)
        scheduleReconnectTick(reconnectGeneration.incrementAndGet())
    }

    /** Wait for [device] to come back without a session (lost, a deliberate shutdown or reboot, or a reconnect that failed). */
    private fun startWaiting(device: Device) {
        reconnect.waitFor(device)
        screen.placeholder = ReconnectWatcher.waitingText(device, 0)
        status.text = ReconnectWatcher.waitingText(device, 0)
        cancelReconnectLink.isVisible = true
        scheduleReconnectTick(reconnectGeneration.incrementAndGet())
    }

    /** Ends watching and waiting (Disconnect, Cancel, another device, a new connect, dispose). Safe to call at any time. */
    private fun cancelReconnect() {
        val wasWaiting = reconnect.phase == ReconnectPhase.WAITING
        reconnect.stop()
        reconnectGeneration.incrementAndGet()
        cancelReconnectLink.isVisible = false
        if (wasWaiting && session == null) {
            screen.placeholder = "Disconnected"
            status.text = "Not connected"
        }
    }

    private fun scheduleReconnectTick(generation: Int) {
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (reconnectGeneration.get() != generation || reconnect.phase == ReconnectPhase.IDLE) return@schedule
            // Device Power polls discovery itself and reports its own result: stay out of its way.
            val event = if (powerBusy) ReconnectEvent.None else reconnect.tick()
            SwingUtilities.invokeLater {
                if (reconnectGeneration.get() != generation) return@invokeLater
                when (event) {
                    is ReconnectEvent.Lost -> {
                        log.info("Lost ${event.device.name}, waiting for it to come back")
                        disconnect(keepWatching = true)
                        screen.placeholder = ReconnectWatcher.waitingText(event.device, 0)
                        status.text = ReconnectWatcher.waitingText(event.device, 0)
                        cancelReconnectLink.isVisible = true
                    }
                    is ReconnectEvent.StillWaiting -> if (!powerBusy) status.text = ReconnectWatcher.waitingText(event.device, event.waitedMs)
                    is ReconnectEvent.Reconnected -> {
                        cancelReconnectLink.isVisible = false
                        refreshDevices(event.device)
                        connect(event.device, automatic = true)
                        return@invokeLater
                    }
                    ReconnectEvent.None -> {}
                }
                if (reconnect.phase != ReconnectPhase.IDLE) scheduleReconnectTick(generation)
            }
        }, reconnect.pollDelayMs(), java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    // endregion

    // region orientation

    /** Reads the device orientation (off the EDT) so the toggle shows the real state, also after external rotations. */
    private fun readOrientation(current: DeviceSession): Orientation? {
        val value = runCatching { Orientation.parse(DeviceServices.mobilecliCall(current.device, *Orientation.getArgs().toTypedArray())) }.getOrNull()
        // A failed read keeps the last known value instead of resetting it to unknown.
        if (value != null && session === current) orientation = value
        return value
    }

    /** Rotates the device, re-reads the real orientation and size, then refreshes mirror geometry and the inspector. */
    private fun rotate() {
        val current = session ?: return
        if (orientationBusy) return
        val target = (orientation ?: Orientation.PORTRAIT).other
        orientationBusy = true
        status.text = "Rotating to ${target.value}..."
        ApplicationManager.getApplication().executeOnPooledThread {
            var error: String? = null
            try {
                DeviceServices.mobilecliCall(current.device, *Orientation.setArgs(target).toTypedArray(), timeout = java.time.Duration.ofSeconds(30))
                // If the confirming read fails, trust the set that just succeeded.
                if (readOrientation(current) == null && session === current) orientation = target
                current.refreshSize()
            } catch (e: Exception) {
                log.info("Rotate failed", e)
                error = e.message
            }
            SwingUtilities.invokeLater {
                orientationBusy = false
                if (session !== current) return@invokeLater
                if (error != null) notify("Could not rotate ${current.device.name}: $error", NotificationType.WARNING)
                screen.repaint()
                updateStatus()
                if (inspectEnabled) refreshHierarchy()
            }
        }
    }

    private inner class OrientationToggleAction : ToggleAction("Landscape", "Rotate the device", AllIcons.Actions.Rollback), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = orientation == Orientation.LANDSCAPE
        override fun setSelected(e: AnActionEvent, state: Boolean) = rotate()
        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isEnabled = session != null && !orientationBusy
            e.presentation.description = when (orientation) {
                Orientation.LANDSCAPE -> "Rotate to portrait (current: landscape)"
                Orientation.PORTRAIT -> "Rotate to landscape (current: portrait)"
                null -> "Rotate the device (needs mobilecli)"
            }
        }
    }

    // endregion

    private inner class SessionListener(private val owner: DeviceSession) : FrameListener {
        override fun onFrame(image: BufferedImage) {
            if (session !== owner) return
            frameCounter.incrementAndGet()
            screen.setFrame(image)
            val signature = screenSignature(image)
            if (signature != lastSignature) {
                lastSignature = signature
                SwingUtilities.invokeLater { if (inspectEnabled && !recording && !freeze.frozen) autoRefreshTimer.restart() }
            }
        }

        override fun onStatus(message: String) {
            SwingUtilities.invokeLater { status.text = message }
        }

        override fun onError(message: String, cause: Throwable?) {
            if (session !== owner) return
            reconnect.sessionFailed()
            log.warn(message, cause)
            SwingUtilities.invokeLater {
                status.text = if (message.length > LONG_STATUS_CHARS) "<html>${StringUtil.escapeXmlEntities(message)}</html>" else message
                // The fps line would replace a long hint within a second.
                if (message.length > LONG_STATUS_CHARS) statusHoldUntil = System.currentTimeMillis() + LONG_STATUS_HOLD_MS
                notify(message, NotificationType.WARNING)
            }
        }
    }

    /**
     * The deepest element at a logical point from the last known hierarchy: the inspector's or the frozen one, else
     * the newest recorder snapshot. Never starts a capture; null when none is loaded.
     */
    private fun lastKnownHierarchy(): UiNode? = hierarchy ?: snapshots?.latest()

    private fun elementAt(x: Int, y: Int): ElementInfo? =
        lastKnownHierarchy()?.let { ElementInfo.of(HierarchyQueries.deepestAt(it, x, y)) }

    private fun showHover(point: HoverPoint?) {
        val root = lastKnownHierarchy()
        val element = if (point != null && root != null) ElementInfo.of(HierarchyQueries.deepestAt(root, point.x, point.y)) else null
        hoverLine.text = CoordinateText.infoLine(point, session?.device?.platform, element, root != null)
    }

    private fun updateStatus() {
        val current = session
        hoverLine.isVisible = current != null
        val fps = frameCounter.getAndSet(0)
        // While a power operation runs the label shows its progress; do not overwrite it every second.
        if (current == null || powerBusy) return
        if (System.currentTimeMillis() < statusHoldUntil) return
        status.text = "${current.device.name}  |  ${current.transport}  |  " + (if (freeze.frozen) "frozen" else "$fps fps") +
            (current.logicalSize()?.let { "  |  ${it.width}x${it.height}" } ?: "")
        status.toolTipText = current.transportInfo()?.tooltip()
    }

    // endregion

    // region inspector

    private fun setInspect(enabled: Boolean) {
        inspectEnabled = enabled
        remember(LayoutPatch(inspectorOpen = enabled))
        updateSidePanel()
        if (enabled) refreshHierarchy() else {
            autoRefreshTimer.stop()
            screen.selectedBounds = null
        }
        revalidate()
        repaint()
    }

    /** The side panel shows while inspecting or recording. While recording, clicks act on the device. */
    private fun updateSidePanel() {
        screen.inspectMode = (inspectEnabled && (!recording || freeze.frozen)) || assertArmed
        splitter.secondComponent = if (inspectEnabled || recording) inspector else null
    }

    /** [quiet]: keep the current tree visible while loading (used by the automatic refresh). */
    private fun refreshHierarchy(quiet: Boolean = false) {
        val current = session ?: run {
            inspector.setMessage("Connect to a device first")
            return
        }
        // The inspector shows the hierarchy captured when the screen was frozen; it must not be replaced.
        if (freeze.frozen) return
        if (refreshInFlight) {
            refreshAgain = true
            return
        }
        refreshInFlight = true
        if (!quiet) inspector.setMessage("Loading hierarchy...")
        // The frame of the moment the capture starts goes with the hierarchy, for the contrast rule.
        val frameAtCapture = screen.currentFrame()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val root = current.hierarchy()
                SwingUtilities.invokeLater {
                    if (session === current && !freeze.frozen) {
                        hierarchy = root
                        inspector.setHierarchy(root, frameAtCapture)
                        screen.selectedBounds = null
                    }
                    finishRefresh()
                }
            } catch (e: Exception) {
                log.warn(e)
                SwingUtilities.invokeLater {
                    if (session === current && !freeze.frozen) inspector.setError("Hierarchy failed: ${e.message}")
                    finishRefresh()
                }
            }
        }
    }

    // region freeze

    /**
     * Freezing keeps the last frame, drops new ones and blocks input. The hierarchy of that moment is
     * captured right away, because a transient element (toast, snackbar) may be gone from the device soon.
     */
    private fun setFrozen(on: Boolean, refresh: Boolean = true) {
        if (on == freeze.frozen) return
        if (on) {
            val current = session ?: return
            if (screen.currentFrame() == null) return
            val token = freeze.freeze()
            screen.frozen = true
            // The tree from before the freeze does not match the frozen frame: nothing may be picked from it.
            hierarchy = null
            screen.selectedBounds = null
            screen.hoverBounds = null
            autoRefreshTimer.stop()
            if (!inspectEnabled) setInspect(true)
            updateSidePanel()
            inspector.setMessage("Loading the hierarchy of the frozen screen...")
            status.text = "Frozen: the screen is not updated and input is blocked"
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val root = current.hierarchy()
                    SwingUtilities.invokeLater {
                        if (session === current && freeze.isCurrent(token)) {
                            hierarchy = root
                            inspector.setHierarchy(root)
                            screen.selectedBounds = null
                        }
                    }
                } catch (e: Exception) {
                    log.warn(e)
                    SwingUtilities.invokeLater {
                        if (session === current && freeze.isCurrent(token)) inspector.setError("Hierarchy failed: ${e.message}")
                    }
                }
            }
        } else {
            freeze.unfreeze()
            screen.frozen = false
            screen.hoverBounds = null
            updateSidePanel()
            updateStatus()
            if (refresh && inspectEnabled && session != null) refreshHierarchy()
        }
        revalidate()
        repaint()
    }

    private inner class FreezeToggleAction : ToggleAction("Freeze", "Freeze the screen to inspect a transient state; input is blocked while frozen", AllIcons.Actions.Pause), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = freeze.frozen
        override fun setSelected(e: AnActionEvent, state: Boolean) = setFrozen(state)
        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isEnabled = freeze.frozen || (session != null && screen.currentFrame() != null)
        }
    }

    // endregion

    private fun finishRefresh() {
        refreshInFlight = false
        if (refreshAgain) {
            refreshAgain = false
            if (session != null && inspectEnabled) refreshHierarchy(quiet = true)
        }
    }

    // endregion

    // region recording

    private fun setRecording(enabled: Boolean) {
        val current = session
        if (enabled && (current == null || !current.supportsInput || replaying)) return
        recording = enabled
        if (!enabled) setAssertArmed(false)
        snapshots?.close()
        snapshots = null
        if (enabled) {
            val platform = current!!.device.platform
            if (recorder == null || recorder?.platform != platform) recorder = ScriptRecorder(platform)
            snapshots = HierarchySnapshots({
                val started = System.currentTimeMillis()
                try {
                    current.hierarchy().also { log.info("Recording: hierarchy captured in ${System.currentTimeMillis() - started} ms") }
                } catch (e: Exception) {
                    log.info("Recording: hierarchy capture failed after ${System.currentTimeMillis() - started} ms: ${e.message?.lineSequence()?.joinToString(" | ")}")
                    throw e
                }
            }).also { it.start() }
            inspector.showTab(RECORDING_TAB)
        } else {
            autoRefreshTimer.stop()
        }
        updateSidePanel()
        recorderPanel.setSteps(recorder?.steps?.toList().orEmpty(), recording, recorder?.platform)
        revalidate()
        repaint()
    }

    private fun setAssertArmed(armed: Boolean) {
        assertArmed = armed && recording
        recorderPanel.setAssertArmed(assertArmed)
        if (!assertArmed) screen.hoverBounds = null
        updateSidePanel()
    }

    /** The user clicked an element while "Assert" is on: find it and offer the assertions that fit. */
    private fun pickAssertion(x: Int, y: Int) {
        val source = snapshots ?: return
        val rec = recorder ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val picked = try {
                rec.pick(source.awaitFresh(FRESH_WAIT_MS, MAX_WAIT_MS).root, x, y)
            } catch (e: Exception) {
                SwingUtilities.invokeLater { recorderPanel.showMessage("No hierarchy yet: ${e.message?.lineSequence()?.firstOrNull()}") }
                return@executeOnPooledThread
            }
            SwingUtilities.invokeLater {
                if (picked == null) {
                    recorderPanel.showMessage("No element with a locator there; click another one")
                } else {
                    showAssertionMenu(picked)
                }
            }
        }
    }

    private fun showAssertionMenu(picked: ScriptRecorder.Picked) {
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(Checks.optionsFor(picked.node, picked.matchCount))
            .setTitle("Assert on ${picked.target.code()}")
            .setRenderer(object : ColoredListCellRenderer<Check?>() {
                override fun customizeCellRenderer(list: JList<out Check?>, value: Check?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    append(value?.label ?: "")
                }
            })
            .setItemChosenCallback { chosen ->
                val check = if (chosen is CountIs) askCount(chosen) else chosen
                // Recorded on the input thread like every other step, so the order is kept.
                if (check != null) inputExecutor.execute { record { add(picked.target, check) } }
                setAssertArmed(false)
            }
            .createPopup()
            .showInCenterOf(screen)
    }

    /** The count is prefilled with what the locator matches now; the user may change it. Null when cancelled. */
    private fun askCount(proposed: CountIs): CountIs? {
        val answer = Messages.showInputDialog(
            this, "Expected number of matching elements:", "Count Assertion", null, proposed.count.toString(),
            object : InputValidator {
                override fun checkInput(inputString: String?) = inputString?.trim()?.toIntOrNull()?.let { it >= 0 } == true
                override fun canClose(inputString: String?) = checkInput(inputString)
            },
        )
        return answer?.trim()?.toIntOrNull()?.let { CountIs(it) }
    }

    // region project state and live foreground app

    private val viewState: ProjectViewState? = project.getService(ProjectViewState::class.java)
    private var layout = ProjectLayout()
    /** False while the saved layout is applied, so applying it does not count as the user changing it. */
    private var layoutTracking = false
    private var restoreDevicePending = true
    private val saveTimer = Timer(LAYOUT_SAVE_DELAY_MS) { saveLayoutNow() }.apply { isRepeats = false }

    private fun restoreLayout() {
        layout = runCatching { viewState?.layout() }.getOrNull() ?: ProjectLayout()
        splitter.proportion = layout.mirrorProportion
        toolsSplitter.proportion = layout.toolsProportion
        if (layout.inspectorOpen) setInspect(true)
        layout.tabIn(toolsPanel.tabTitles)?.let { toolsPanel.selectTab(it) }
        if (layout.toolsOpen) setLogsVisible(true)
        layoutTracking = true
    }

    private fun installLayoutListeners() {
        toolsPanel.onTabSelected = { remember(LayoutPatch(toolsTab = toolsPanel.selectedTitle)) }
        splitter.addPropertyChangeListener(com.intellij.openapi.ui.Splitter.PROP_PROPORTION) {
            remember(LayoutPatch(mirrorProportion = splitter.proportion))
        }
        toolsSplitter.addPropertyChangeListener(com.intellij.openapi.ui.Splitter.PROP_PROPORTION) {
            remember(LayoutPatch(toolsProportion = toolsSplitter.proportion))
        }
    }

    /** Records a change and saves it shortly after (debounced). */
    private fun remember(patch: LayoutPatch) {
        if (!layoutTracking) return
        val next = layout.merge(patch)
        if (next == layout) return
        layout = next
        saveTimer.restart()
    }

    private fun saveLayoutNow() {
        if (!layoutTracking) return
        runCatching { viewState?.save(layout) }.onFailure { log.info("Could not save the project state", it) }
    }

    private fun rememberedDevice(devices: List<Device>): Device? = LastDevice.find(devices, layout.lastDeviceId, layout.lastDeviceName)

    /** Only with the setting on, only when the remembered device is running and nothing is connected yet. */
    private fun autoConnectRemembered(devices: List<Device>) {
        if (session != null || !runCatching { MobileTestBenchSettings.getInstance().state.reconnectToLastDevice }.getOrDefault(false)) return
        val device = rememberedDevice(devices)?.takeIf { it.isUsable } ?: return
        connect(device)
    }

    private val foregroundGeneration = AtomicInteger()
    @Volatile private var foregroundInFlight = false
    private var foregroundApp: ForegroundApp? = null
    private val foregroundTimer = Timer(FOREGROUND_POLL_MS) { pollForeground() }

    private fun installForegroundPolling() {
        foregroundLabel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val id = ForegroundText.copyText(foregroundApp) ?: return
                CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(id))
                status.text = "Copied $id"
                statusHoldUntil = System.currentTimeMillis() + 2_000L
            }
        })
        // A hidden tool window stops the polling; showing again polls at once.
        addHierarchyListener { e ->
            if (e.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                if (isShowing) pollForeground() else foregroundGeneration.incrementAndGet()
            }
        }
        foregroundTimer.start()
    }

    private fun showForeground(app: ForegroundApp?, clear: Boolean = false) {
        foregroundApp = app
        if (clear) {
            foregroundGeneration.incrementAndGet()
            foregroundLabel.isVisible = false
            foregroundLabel.text = " "
            return
        }
        foregroundLabel.isVisible = true
        foregroundLabel.text = ForegroundText.label(app)
        foregroundLabel.toolTipText = ForegroundText.tooltip(app)
    }

    /** One `apps foreground` call at a time, on a pooled thread; the result is dropped when the generation or the device changed meanwhile. */
    private fun pollForeground() {
        val current = session ?: return
        if (!isShowing || foregroundInFlight || powerBusy) return
        val generation = foregroundGeneration.get()
        foregroundInFlight = true
        ApplicationManager.getApplication().executeOnPooledThread {
            val app = try {
                Apps.parseForeground(DeviceServices.mobilecliData(current.device, "apps", "foreground", timeout = java.time.Duration.ofSeconds(FOREGROUND_TIMEOUT_S)))
            } catch (e: Exception) {
                null
            }
            SwingUtilities.invokeLater {
                foregroundInFlight = false
                if (foregroundGeneration.get() == generation && session === current) showForeground(app)
            }
        }
    }

    // endregion

    // Runs here, after the fields above exist (property initializers run in declaration order).
    init {
        restoreLayout()
        installLayoutListeners()
        installForegroundPolling()
        refreshDevices()
    }

    // region device tools

    private fun setLogsVisible(visible: Boolean) {
        toolsVisible = visible
        remember(LayoutPatch(toolsOpen = visible))
        toolsSplitter.secondComponent = if (visible) toolsPanel else null
        if (visible) toolsPanel.opened(session?.device) else toolsPanel.closed()
        revalidate()
        repaint()
    }

    // endregion

    // region page object

    /** Finds the elements of the current hierarchy (in the background: it works out a locator for each) and opens the dialog. */
    private fun generatePageObject() {
        val root = hierarchy ?: return
        status.text = "Preparing the page object..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val mobilewright = PageObjectCandidates.find(root, appium = false)
            val appium = PageObjectCandidates.find(root, appium = true)
            SwingUtilities.invokeLater {
                updateStatus()
                PageObjectDialog(project, mobilewright, appium).show()
            }
        }
    }

    // endregion

    // region screen recording

    private fun startScreenRecording() {
        val device = session?.device ?: return
        val name = device.name.replace(Regex("[^A-Za-z0-9._-]+"), "-") + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".mp4"
        val descriptor = FileSaverDescriptor("Save Screen Recording", "Where to save the video of the device screen", "mp4")
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(null as com.intellij.openapi.vfs.VirtualFile?, name) ?: return
        screenRecordingStarting = true
        status.text = "Starting screen recording..."
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val recording = DeviceServices.startScreenRecording(device, target.file)
                screenRecording = recording
                val generation = screenRecordingGeneration.incrementAndGet()
                val limitMs = ScreenRecordingLimit.limitMs(MobileTestBenchSettings.getInstance().state.screenRecordingLimitSeconds)
                SwingUtilities.invokeLater {
                    status.text = "Recording the screen to ${target.file.path}: press the button again to stop" +
                        (limitMs?.let { " (stops by itself after ${it / 1000} s)" } ?: "")
                    if (limitMs != null) {
                        Timer(limitMs.toInt().coerceAtLeast(1), null).apply {
                            isRepeats = false
                            addActionListener {
                                if (screenRecordingGeneration.get() == generation && screenRecording === recording) stopScreenRecording()
                            }
                            start()
                        }
                    }
                }
            } catch (e: Exception) {
                log.warn(e)
                SwingUtilities.invokeLater {
                    status.text = "Could not record the screen: ${e.message?.lineSequence()?.firstOrNull()}"
                    notify("Could not record the screen: ${e.message}", NotificationType.WARNING)
                }
            } finally {
                screenRecordingStarting = false
            }
        }
    }

    private fun stopScreenRecording() {
        val recording = screenRecording ?: return
        screenRecording = null
        screenRecordingGeneration.incrementAndGet()
        status.text = "Finishing the video..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = recording.stop()
            SwingUtilities.invokeLater {
                result.onSuccess { status.text = "Screen recording saved to ${it.path} (${it.length() / 1024} KB)" }
                result.onFailure { status.text = "Screen recording failed: ${it.message?.lineSequence()?.firstOrNull()}" }
            }
        }
    }

    // endregion

    // region screenshot

    /** Copy or save the current frame; with an element selected in the inspector it can be outlined first. */
    private fun screenshotMenu() {
        val withHierarchy = MobileTestBenchSettings.getInstance().state.saveHierarchyWithScreenshot
        val selected = screen.selectedBounds
        val options = buildList {
            add("Copy to Clipboard")
            add("Save as PNG...")
            if (selected != null) {
                add("Copy to Clipboard, selected element outlined")
                add("Save as PNG, selected element outlined...")
            }
            add(if (withHierarchy) "[x] Also save the hierarchy (XML) next to the PNG" else "[ ] Also save the hierarchy (XML) next to the PNG")
        }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(options)
            .setTitle("Screenshot")
            .setItemChosenCallback { choice ->
                if (choice.contains("Also save the hierarchy")) {
                    MobileTestBenchSettings.getInstance().state.saveHierarchyWithScreenshot = !withHierarchy
                    screenshotMenu()
                    return@setItemChosenCallback
                }
                val outlined = choice.contains("outlined")
                val image = screenshot(outlined) ?: return@setItemChosenCallback
                if (choice.startsWith("Copy")) copyImage(image) else saveImage(image)
            }
            .createPopup()
            .showInCenterOf(screen)
    }

    private fun screenshot(outlined: Boolean): BufferedImage? {
        val frame = screen.currentFrame() ?: return null
        val bounds = screen.selectedBounds
        val logical = session?.logicalSize()
        return if (outlined && bounds != null && logical != null) ScreenshotAnnotator.outline(frame, logical, bounds) else frame
    }

    private fun copyImage(image: BufferedImage) {
        CopyPasteManager.getInstance().setContents(object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any = if (isDataFlavorSupported(flavor)) image else throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
        })
        status.text = "Screenshot copied to the clipboard"
    }

    private fun saveImage(image: BufferedImage) {
        val device = session?.device?.name?.replace(Regex("[^A-Za-z0-9._-]+"), "-") ?: "device"
        val name = "$device-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.png"
        val descriptor = FileSaverDescriptor("Save Screenshot", "Save the current device screen as a PNG file", "png")
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(null as com.intellij.openapi.vfs.VirtualFile?, name) ?: return
        val withHierarchy = MobileTestBenchSettings.getInstance().state.saveHierarchyWithScreenshot
        ApplicationManager.getApplication().executeOnPooledThread {
            val message = try {
                ImageIO.write(image, "png", target.file)
                if (withHierarchy) {
                    val root = lastKnownHierarchy() ?: session?.hierarchy()
                    if (root != null) {
                        val xml = io.github.marcomaes.mobiletestbench.core.HierarchyDump.siblingFile(target.file)
                        xml.writeText(io.github.marcomaes.mobiletestbench.core.HierarchyDump.toXml(root))
                        "Screenshot and hierarchy saved to ${target.file.path} and ${xml.name}"
                    } else "Screenshot saved to ${target.file.path} (no hierarchy available)"
                } else "Screenshot saved to ${target.file.path}"
            } catch (e: Exception) {
                log.warn(e)
                "Could not save the screenshot: ${e.message}"
            }
            SwingUtilities.invokeLater { status.text = message }
        }
    }

    // endregion

    /** [range] is the steps to run (indices in the recording); null runs all of them. */
    private fun startReplay(range: IntRange? = null) {
        val current = session ?: return
        val steps = recorder?.steps?.toList().orEmpty()
        if (recording || replaying || steps.isEmpty() || !current.supportsInput) return
        if (recorder?.platform != current.device.platform) {
            notify("This recording is for ${recorder?.platform}; connect a ${recorder?.platform} device to replay it", NotificationType.WARNING)
            return
        }
        replaying = true
        recorderPanel.setReplaying(true)
        replayExecutor.execute {
            var passed = false
            try {
                val settings = MobileTestBenchSettings.getInstance().state
                val player = ScriptPlayer(
                    current,
                    elementTimeoutMs = ReplayOptions.timeoutMs(settings.replayTimeoutSeconds),
                    stepDelayMs = ReplayOptions.delayMs(settings.replayDelayMs),
                )
                passed = player.run(steps, { index, state, message ->
                    SwingUtilities.invokeLater { recorderPanel.setStepState(index, state, message) }
                }, cancelled = { !replaying }, range = range)
            } finally {
                val stopped = !replaying
                replaying = false
                SwingUtilities.invokeLater {
                    recorderPanel.setReplaying(false)
                    recorderPanel.replayFinished(passed, stopped)
                }
            }
        }
    }

    /** Sets (or with null removes) the own timeout of the wait and expect steps at [rows]. */
    private fun setStepTimeout(rows: List<Int>, timeoutMs: Long?) {
        if (replaying) return
        inputExecutor.execute {
            val r = recorder ?: return@execute
            val changed = rows.count { r.setTimeout(it, timeoutMs) }
            val steps = r.steps.toList()
            SwingUtilities.invokeLater {
                recorderPanel.setSteps(steps, recording)
                recorderPanel.showMessage(
                    if (changed == 0) "Only wait and expect steps have a timeout"
                    else if (timeoutMs == null) "Timeout removed: the replay timeout setting applies" else "Timeout set to ${ReplayOptions.format(timeoutMs)}",
                )
            }
        }
    }

    /** Replaces the selected swipe steps and the element step after them (see [ScriptRecorder.scrollUntilVisible]). */
    private fun scrollUntilVisible(rows: List<Int>) {
        if (replaying) return
        inputExecutor.execute {
            val r = recorder ?: return@execute
            val problem = r.scrollUntilVisible(rows)
            val steps = r.steps.toList()
            SwingUtilities.invokeLater {
                recorderPanel.setSteps(steps, recording)
                recorderPanel.showMessage(problem ?: "Swipes replaced by scroll until visible")
            }
        }
    }

    /** Sets direction and swipe limit of the scroll-until-visible steps at [rows]. */
    private fun setScrollOptions(rows: List<Int>, direction: String, maxSwipes: Int) {
        if (replaying) return
        inputExecutor.execute {
            val r = recorder ?: return@execute
            val changed = rows.count { r.setScrollOptions(it, direction, maxSwipes) }
            val steps = r.steps.toList()
            SwingUtilities.invokeLater {
                recorderPanel.setSteps(steps, recording)
                recorderPanel.showMessage(if (changed == 0) "Only scroll until visible steps have a direction and limit" else "Scroll set to $direction, at most $maxSwipes swipes")
            }
        }
    }

    private fun clearRecording() {
        inputExecutor.execute {
            recorder?.clear()
            SwingUtilities.invokeLater { recorderPanel.setSteps(emptyList(), recording) }
        }
    }

    /** Runs on the input thread, before the input is sent. */
    private fun record(step: ScriptRecorder.() -> Unit) {
        val r = recorder?.takeIf { recording } ?: return
        r.step()
        val steps = r.steps.toList()
        SwingUtilities.invokeLater { recorderPanel.setSteps(steps, recording, r.platform) }
    }

    /**
     * A tap is tied to the element under the finger using the hierarchy of the screen the user sees. That
     * hierarchy is captured in the background after each action, so this normally does not wait.
     */
    private fun recordTap(x: Int, y: Int) = recordPoint(x, y, "tap") { root, fresh -> tap(root, x, y, verified = fresh) }

    /** Like [recordTap] for other gestures at a point: [step] records them against the captured hierarchy. */
    private fun recordPoint(x: Int, y: Int, what: String, step: ScriptRecorder.(UiNode, Boolean) -> Unit) {
        val source = snapshots?.takeIf { recording } ?: return
        val started = System.currentTimeMillis()
        val captured = try {
            source.awaitFresh(FRESH_WAIT_MS, MAX_WAIT_MS)
        } catch (e: Exception) {
            log.info("Recording: no hierarchy for $what after ${System.currentTimeMillis() - started} ms: ${e.message}")
            val reason = e.message?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(" | ")
            record { tapWithoutHierarchy(x, y, reason, what) }
            return
        }
        log.info("Recording: $what waited ${System.currentTimeMillis() - started} ms for the hierarchy (${if (captured.fresh) "fresh" else "stale"})")
        record { step(captured.root, captured.fresh) }
    }

    // endregion

    // region show locator from the editor

    /** The locator result stays in the status line for a few seconds before the fps line takes over. */
    @Volatile private var statusHoldUntil = 0L
    private val locatorGeneration = AtomicInteger()
    /** True while the green match highlight comes from "Show Locator on Device"; the next input clears it. */
    private var locatorHighlight = false

    /** Called on the EDT by the editor action with the selection or caret line. Blocking work runs on a pooled thread. */
    override fun showLocator(text: String) {
        val current = session
        if (current == null) return reportLocator(ShowLocatorResult.NoDevice)
        if (LocatorExtraction.extract(text) == null) return reportLocator(ShowLocatorResult.NoLocator)
        val generation = locatorGeneration.incrementAndGet()
        if (freeze.frozen) {
            // The screen the user sees is the frozen one: try the locator on the hierarchy of that moment.
            val frozenRoot = hierarchy ?: return reportLocator(ShowLocatorResult.Failed("the frozen screen has no hierarchy yet"))
            return applyLocatorResult(frozenRoot, ShowLocator.evaluate(text, frozenRoot))
        }
        status.text = "Trying the locator on ${current.device.name}..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome: Pair<UiNode?, ShowLocatorResult> = try {
                val root = current.hierarchy()
                root to ShowLocator.evaluate(text, root)
            } catch (e: Exception) {
                log.info("Show locator: hierarchy failed", e)
                null to ShowLocatorResult.Failed(e.message?.lineSequence()?.firstOrNull() ?: e.javaClass.simpleName)
            }
            SwingUtilities.invokeLater {
                if (session !== current || locatorGeneration.get() != generation || freeze.frozen) return@invokeLater
                val root = outcome.first
                if (root == null) reportLocator(outcome.second) else applyLocatorResult(root, outcome.second)
            }
        }
    }

    private fun applyLocatorResult(root: UiNode, result: ShowLocatorResult) {
        hierarchy = root
        if (inspectEnabled && !freeze.frozen) inspector.setHierarchy(root)
        val nodes = (result as? ShowLocatorResult.Matches)?.nodes.orEmpty()
        screen.matchBounds = nodes.mapNotNull { it.bounds }
        locatorHighlight = screen.matchBounds.isNotEmpty()
        if (inspectEnabled) nodes.firstOrNull()?.let(inspector::select)
        // the old selection came from the previous tree: no match means no selection
        screen.selectedBounds = nodes.firstOrNull()?.bounds
        reportLocator(result)
    }

    private fun reportLocator(result: ShowLocatorResult) {
        statusHoldUntil = System.currentTimeMillis() + LOCATOR_STATUS_HOLD_MS
        status.text = "Locator: ${result.message()}"
        notify(result.message().replaceFirstChar { it.uppercase() }, if (result.isSuccess) NotificationType.INFORMATION else NotificationType.WARNING)
    }

    // endregion

    // region inspector selection

    private fun onInspectorSelection(node: UiNode?) {
        screen.selectedBounds = node?.bounds
    }

    // endregion

    private inner class ScreenInputHandler : ScreenView.Listener {
        override fun onTap(x: Int, y: Int) = input({ recordTap(x, y) }) { it.tap(x, y) }

        override fun onSwipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) =
            input({ record { swipe(fromX, fromY, toX, toY) } }) { it.swipe(fromX, fromY, toX, toY, durationMs) }

        override fun onLongPress(x: Int, y: Int, durationMs: Long) =
            input({ recordPoint(x, y, "long press") { root, fresh -> longPress(root, x, y, durationMs, verified = fresh) } }) { it.longPress(x, y, durationMs) }

        /** Where a double tap cannot be sent in time (see [DeviceSession.supportsDoubleTap]) the click stays the plain tap it was before. */
        override fun onDoubleTap(x: Int, y: Int) {
            if (session?.supportsDoubleTap != true) return onTap(x, y)
            input({ recordPoint(x, y, "double tap") { root, fresh -> doubleTap(root, x, y, verified = fresh) } }) { it.doubleTap(x, y) }
        }

        override fun onHoldDrag(fromX: Int, fromY: Int, toX: Int, toY: Int, holdMs: Long, durationMs: Long) {
            if (session?.supportsHoldDrag != true) return onSwipe(fromX, fromY, toX, toY, durationMs)
            input({ record { dragWithHold(fromX, fromY, toX, toY, holdMs) } }) { it.dragWithHold(fromX, fromY, toX, toY, holdMs, durationMs) }
        }

        override fun onType(text: String) = input({ record { type(text) } }, changesScreen = false) { it.typeText(text) }

        override fun onSpecialKey(keyCode: Int) {
            when (keyCode) {
                KeyEvent.VK_ENTER -> input({ record { key(DeviceKey.ENTER) } }) { it.press(DeviceKey.ENTER) }
                KeyEvent.VK_BACK_SPACE -> input({ record { delete() } }, changesScreen = false) { it.press(DeviceKey.DELETE) }
                KeyEvent.VK_ESCAPE -> if (session?.device?.platform == Platform.ANDROID) {
                    input({ record { key(DeviceKey.BACK) } }) { it.press(DeviceKey.BACK) }
                }
            }
        }

        override fun onFrozenInput() {
            status.text = "The screen is frozen: turn Freeze off to interact with the device"
        }

        override fun onInspectHover(x: Int, y: Int) {
            val root = (if (assertArmed) snapshots?.latest() else hierarchy) ?: return
            screen.hoverBounds = HierarchyQueries.deepestAt(root, x, y)?.bounds
        }

        override fun onInspectClick(x: Int, y: Int) {
            if (assertArmed) {
                pickAssertion(x, y)
                return
            }
            val root = hierarchy ?: return
            HierarchyQueries.deepestAt(root, x, y)?.let(inspector::select)
        }
    }

    /** [changesScreen]: false for typing, which keeps the layout, so the recorder's hierarchy stays valid. [beforeInput] runs first on the input thread; it is where recording captures what is about to happen. */
    private fun input(beforeInput: (DeviceSession) -> Unit = {}, changesScreen: Boolean = true, block: (DeviceSession) -> Unit) {
        val current = session ?: return
        if (freeze.frozen) {
            status.text = "The screen is frozen: turn Freeze off to interact with the device"
            return
        }
        if (locatorHighlight) {
            locatorHighlight = false
            screen.matchBounds = emptyList()
        }
        if (!current.supportsInput) {
            status.text = "Input is not available for this device"
            return
        }
        if (replaying) {
            status.text = "A replay is running: press Stop first"
            return
        }
        inputExecutor.execute {
            try {
                beforeInput(current)
                block(current)
                SwingUtilities.invokeLater { if (inspectEnabled && !recording) autoRefreshTimer.restart() }
            } catch (e: Exception) {
                log.info("Input failed", e)
                SwingUtilities.invokeLater { status.text = "Input failed: ${e.message}" }
            } finally {
                if (changesScreen) snapshots?.inputDone()
            }
        }
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Mobile Test Bench")
            .createNotification(message, type)
            .notify(project)
    }

    override fun dispose() {
        saveTimer.stop()
        saveLayoutNow()
        foregroundGeneration.incrementAndGet()
        foregroundTimer.stop()
        project.getService(LocatorTargets::class.java)?.unregister(this)
        locatorGeneration.incrementAndGet()
        reconnect.stop()
        reconnectGeneration.incrementAndGet()
        powerGeneration.incrementAndGet()
        fpsTimer.stop()
        autoRefreshTimer.stop()
        replaying = false
        snapshots?.close()
        toolsPanel.dispose()
        screenRecording?.let { runCatching { it.stop() } }
        session?.let { old -> ApplicationManager.getApplication().executeOnPooledThread { runCatching { old.close() } } }
        session = null
        inputExecutor.shutdownNow()
    }

    // region actions

    private abstract inner class PanelAction(text: String, icon: javax.swing.Icon) : DumbAwareAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class RefreshDevicesAction : PanelAction("Refresh Devices", AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) = refreshDevices()
    }

    private inner class ConnectAction : PanelAction("Connect", AllIcons.Actions.Execute) {
        override fun actionPerformed(e: AnActionEvent) {
            (deviceCombo.selectedItem as? Device)?.let(::connect)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = (deviceCombo.selectedItem as? Device)?.isUsable == true
        }
    }

    private inner class DisconnectAction : PanelAction("Disconnect", AllIcons.Actions.Suspend) {
        override fun actionPerformed(e: AnActionEvent) = disconnect()
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = session != null || reconnect.phase == ReconnectPhase.WAITING
        }
    }

    private inner class KeyAction(text: String, icon: javax.swing.Icon, private val key: DeviceKey) : PanelAction(text, icon) {
        override fun actionPerformed(e: AnActionEvent) = input({ record { key(key) } }) { it.press(key) }
        override fun update(e: AnActionEvent) {
            val current = session
            e.presentation.isEnabled = current != null && current.supportsInput
            e.presentation.isVisible = !(current?.device?.platform == Platform.IOS && key != DeviceKey.HOME)
        }
    }

    private inner class TypeTextAction : PanelAction("Type Text...", AllIcons.Actions.Edit) {
        override fun actionPerformed(e: AnActionEvent) {
            val text = Messages.showInputDialog(project, "Text to type on the device:", "Type Text", null) ?: return
            input({ record { type(text) } }, changesScreen = false) { it.typeText(text) }
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = session?.supportsInput == true
        }
    }

    private inner class InspectToggleAction : ToggleAction("Inspect Elements", "Show the element tree and locators", AllIcons.General.Locate), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = inspectEnabled
        override fun setSelected(e: AnActionEvent, state: Boolean) = setInspect(state)
    }

    private inner class GridToggleAction : ToggleAction("Grid", "Show a coordinate grid and a crosshair with the cursor position over the screen", AllIcons.Graph.Grid), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = screen.gridVisible
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            screen.gridVisible = state
            runCatching { MobileTestBenchSettings.getInstance().state.showGrid = state }
        }
    }

    private inner class RecordToggleAction : ToggleAction("Record", "Record taps, swipes and typing as Mobilewright code", AllIcons.Debugger.Db_set_breakpoint), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = recording
        override fun setSelected(e: AnActionEvent, state: Boolean) = setRecording(state)
        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isEnabled = recording || session?.supportsInput == true
        }
    }

    private inner class RefreshHierarchyAction : PanelAction("Refresh Hierarchy", AllIcons.Actions.ForceRefresh) {
        override fun actionPerformed(e: AnActionEvent) = refreshHierarchy()
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = inspectEnabled && session != null
        }
    }

    private inner class ScreenRecordToggleAction : ToggleAction("Record Screen to Video", "Record the device screen to an MP4 file (needs mobilecli)", AllIcons.Actions.Dump), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = screenRecording != null || screenRecordingStarting
        override fun setSelected(e: AnActionEvent, state: Boolean) = if (state) startScreenRecording() else stopScreenRecording()
        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isEnabled = screenRecording != null || (session != null && !screenRecordingStarting)
        }
    }

    private inner class ToolsToggleAction : ToggleAction("Device Tools", "Device tools: log, apps, open URL, crashes, location, display, input, network, biometrics", AllIcons.Debugger.Console), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = toolsVisible
        override fun setSelected(e: AnActionEvent, state: Boolean) = setLogsVisible(state)
    }

    private inner class PageObjectAction : PanelAction("Generate Page Object...", AllIcons.Nodes.Class) {
        override fun actionPerformed(e: AnActionEvent) = generatePageObject()
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = hierarchy != null
        }
    }

    private inner class ScreenshotAction : PanelAction("Screenshot", AllIcons.Actions.Preview) {
        override fun actionPerformed(e: AnActionEvent) = screenshotMenu()
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = session != null && screen.currentFrame() != null
        }
    }

    private inner class SettingsAction : PanelAction("Settings", AllIcons.General.Settings) {
        override fun actionPerformed(e: AnActionEvent) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, MobileTestBenchConfigurable::class.java)
        }
    }

    // endregion
}
