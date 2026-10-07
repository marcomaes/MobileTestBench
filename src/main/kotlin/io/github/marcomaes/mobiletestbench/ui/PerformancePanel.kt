package io.github.marcomaes.mobiletestbench.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.marcomaes.mobiletestbench.DeviceServices
import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.DeviceKind
import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.apps.Apps
import io.github.marcomaes.mobiletestbench.core.perf.PerfBackend
import io.github.marcomaes.mobiletestbench.core.perf.PerfCsv
import io.github.marcomaes.mobiletestbench.core.perf.PerfPoint
import io.github.marcomaes.mobiletestbench.core.perf.PerfSampler
import io.github.marcomaes.mobiletestbench.core.perf.RingSeries
import io.github.marcomaes.mobiletestbench.core.perf.StartEntry
import io.github.marcomaes.mobiletestbench.core.perf.StartHistory
import io.github.marcomaes.mobiletestbench.core.perf.StartKind
import io.github.marcomaes.mobiletestbench.core.perf.StartRun
import io.github.marcomaes.mobiletestbench.core.perf.StartRunner
import io.github.marcomaes.mobiletestbench.core.perf.StartSummary
import io.github.marcomaes.mobiletestbench.core.perf.Stats
import io.github.marcomaes.mobiletestbench.settings.MobileTestBenchSettings
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JSplitPane
import javax.swing.ListCellRenderer
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel

/**
 * Live memory, CPU and (Android) frame charts of one app, sampled once per second by one daemon thread that never overlaps its own calls, plus
 * cold and warm start time measurements. Pure logic in `core/perf`; this class is wiring and Swing. A late result of an older sampling run or
 * measurement is dropped through [sampleGen] / [measureGen].
 */
class PerformancePanel : JPanel(BorderLayout()), DeviceToolTab {

    override val title = "Performance"
    override val component: JComponent get() = this

    override fun deviceChanged(device: Device?) {
        stopSampling(null)
        measureGen++
        cancelMeasure.set(true)
        this.device = device
        points.clear()
        resetStartResults()
        appListGen++
        configure()
        refreshCharts()
        if (device != null && visible) loadApps()
    }

    override fun shown() {
        visible = true
        if (device != null) loadApps()
    }

    override fun hidden() {
        visible = false
        // Sampling costs a device call per second: it only runs while the tab is visible.
        if (sampling) stopSampling("Stopped: the tab was hidden (the samples are kept)")
    }

    override fun dispose() {
        stopSampling(null)
        measureGen++
        cancelMeasure.set(true)
    }

    private var device: Device? = null
    private var visible = false

    /** Bumped on every start, stop and device change: a sampling thread of an older run ends and its results are dropped. */
    @Volatile private var sampleGen = 0
    @Volatile private var measureGen = 0
    private var appListGen = 0
    private val cancelMeasure = AtomicBoolean(false)
    private var measuring = false
    private var sampling = false

    private val points = RingSeries<PerfPoint>(600)
    private val history = StartHistory(20)
    private val runs = mutableListOf<StartRun>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss")

    private val appModel = DefaultComboBoxModel<String>().apply { addElement(FOREGROUND) }
    private val appCombo = JComboBox(appModel).apply {
        isEditable = true
        selectedItem = FOREGROUND
        toolTipText = "Package name or bundle id; \"$FOREGROUND\" follows the app in front"
    }
    private val start = JButton("Start")
    private val stop = JButton("Stop")
    private val clear = JButton("Clear")
    private val save = JButton("Save CSV...")
    private val windowCombo = JComboBox(WINDOWS)
    private val status = JBLabel(" ").apply { border = JBUI.Borders.empty(2, 8); foreground = JBColor.GRAY }

    private val memory = PerfChart("Memory", " MB", listOf(
        ChartSeries("PSS", JBColor(0x3574F0, 0x6C9BFF)) { it.memoryMb },
        ChartSeries("RSS", JBColor(0xB08800, 0xD9B84A)) { it.rssMb },
    ))
    private val cpu = PerfChart("CPU, % of one core", " %", listOf(ChartSeries("CPU", JBColor(0xC7222D, 0xE55765)) { it.cpuPercent }))
    private val frames = PerfChart("Janky frames", " %", listOf(ChartSeries("Janky", JBColor(0x208A3C, 0x5FB865)) { it.jankPercent }), fixedMax = 100.0)
    private val frameHolder = JPanel(CardLayout()).apply {
        add(frames, "chart")
        add(JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(8); add(JBLabel("Frame stats are not available on iOS simulators (they need Instruments)."), BorderLayout.NORTH) }, "ios")
    }

    private val kindCombo = JComboBox(StartKind.values())
    private val runsSpinner = JSpinner(SpinnerNumberModel(5, 1, 10, 1))
    private val measure = JButton("Measure")
    private val cancel = JButton("Cancel")
    private val progress = JBLabel(" ").apply { foreground = JBColor.GRAY }
    private val summary = JBLabel(" ")
    private val caption = JBLabel(" ").apply { foreground = JBColor.GRAY }

    private val runModel = object : AbstractTableModel() {
        override fun getRowCount() = runs.size
        override fun getColumnCount() = 4
        override fun getColumnName(c: Int) = listOf("Run", "Kind", "Milliseconds", "Note")[c]
        override fun getValueAt(r: Int, c: Int): Any = runs[r].let {
            when (c) { 0 -> it.index; 1 -> it.kind.label; 2 -> it.millis?.toString() ?: "failed"; else -> it.note }
        }
    }
    private val runTable = JBTable(runModel)
    private val historyModel = DefaultListModel<String>()

    init {
        val settings = MobileTestBenchSettings.getInstance().state
        windowCombo.selectedItem = WINDOWS.firstOrNull { it == settings.perfWindowSeconds } ?: 60
        runsSpinner.value = settings.perfStartRuns.coerceIn(1, 10)
        kindCombo.selectedItem = StartKind.values().firstOrNull { it.name == settings.perfStartKind } ?: StartKind.COLD
        val base = DefaultListCellRenderer()
        windowCombo.renderer = ListCellRenderer<Int> { list, v, i, sel, focus -> base.getListCellRendererComponent(list, "${v}s window", i, sel, focus) }

        val toolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
                add(JBLabel("App")); add(appCombo); add(start); add(stop); add(clear); add(save); add(windowCombo)
            })
            add(status)
        }
        appCombo.preferredSize = java.awt.Dimension(JBUI.scale(300), appCombo.preferredSize.height)

        val charts = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(memory); add(cpu); add(frameHolder)
        }
        val startPanel = JPanel(BorderLayout(0, 4)).apply {
            border = JBUI.Borders.empty(2, 6)
            val top = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JBLabel("<html><b>Measure app start</b> (a cold start stops the app chosen above)</html>"))
                add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { add(JBLabel("Kind")); add(kindCombo); add(JBLabel("Runs")); add(runsSpinner); add(measure); add(cancel) })
                add(progress)
            }
            val middle = JPanel(BorderLayout()).apply {
                add(ScrollPaneFactory.createScrollPane(runTable), BorderLayout.CENTER)
                add(summary, BorderLayout.SOUTH)
            }
            val bottom = JPanel(BorderLayout()).apply {
                add(JBLabel("Last 20 measurements (this session)"), BorderLayout.NORTH)
                add(ScrollPaneFactory.createScrollPane(JList(historyModel)), BorderLayout.CENTER)
                add(caption, BorderLayout.SOUTH)
            }
            add(top, BorderLayout.NORTH)
            add(JSplitPane(JSplitPane.VERTICAL_SPLIT, middle, bottom).apply { resizeWeight = 0.5; border = null }, BorderLayout.CENTER)
        }
        add(toolbar, BorderLayout.NORTH)
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, ScrollPaneFactory.createScrollPane(charts), startPanel).apply { resizeWeight = 0.62; border = null }, BorderLayout.CENTER)

        start.addActionListener { startSampling() }
        stop.addActionListener { stopSampling("Stopped") }
        clear.addActionListener { points.clear(); refreshCharts() }
        save.addActionListener { saveCsv() }
        windowCombo.addActionListener {
            (windowCombo.selectedItem as? Int)?.let { MobileTestBenchSettings.getInstance().state.perfWindowSeconds = it }
            refreshCharts()
        }
        measure.addActionListener { startMeasure() }
        cancel.addActionListener { cancelMeasure.set(true); progress.text = "Cancelling after the current run..." }
        configure()
        refreshCharts()
    }

    // ---- state of the controls

    private fun supported(d: Device?) = d != null && (d.platform == Platform.ANDROID || d.kind == DeviceKind.SIMULATOR)

    /** The explicit app typed or picked, or null for the foreground app. */
    private fun chosenApp(): String? = (appCombo.editor.item as? String)?.trim()?.takeIf { it.isNotEmpty() && it != FOREGROUND }

    private fun configure() {
        val d = device
        if (!sampling) setStatus(
            when {
                d == null -> "Not connected"
                !supported(d) -> "Performance needs an Android device or an iOS simulator (not a physical iPhone)"
                else -> "Press Start to sample once per second"
            }, false,
        )
        (frameHolder.layout as CardLayout).show(frameHolder, if (d?.platform == Platform.IOS) "ios" else "chart")
        caption.text = when (d?.platform) {
            Platform.ANDROID -> "<html>Method: am start -W TotalTime with the launcher intent (includes system work). Emulator timings depend on host load. Cold: force-stop, 1.5 s, launch. Warm: Home, 2 s, launch.</html>"
            Platform.IOS -> "<html>Method: simulator log, launch request to UIApplication active (includes about 0.4 s launch animation). Fallback: the duration of the launch call, which only times the spawn. Cold: terminate, launch. Warm: Home, launch.</html>"
            null -> " "
        }
        updateEnabled()
    }

    private fun updateEnabled() {
        val ok = supported(device)
        start.isEnabled = ok && !sampling
        stop.isEnabled = sampling
        save.isEnabled = points.size > 0
        measure.isEnabled = ok && !measuring
        cancel.isEnabled = measuring
        kindCombo.isEnabled = !measuring
        runsSpinner.isEnabled = !measuring
    }

    private fun setStatus(text: String, error: Boolean) {
        status.text = text.ifBlank { " " }
        status.foreground = if (error) JBColor.RED else JBColor.GRAY
    }

    private fun refreshCharts() {
        val window = windowCombo.selectedItem as? Int ?: 60
        val list = points.toList()
        val android = device?.platform == Platform.ANDROID
        memory.setData(list, window, if (device?.platform == Platform.IOS) "footprint" else null)
        cpu.setData(list, window)
        frames.setData(list, window, if (android) "fps now ${Stats.format(list.lastOrNull()?.fps, "")}; an idle app renders no frames" else null)
        updateEnabled()
    }

    // ---- sampling

    private fun startSampling() {
        val dev = device ?: return
        stopSampling(null)
        points.clear()
        val my = ++sampleGen
        sampling = true
        val choice = chosenApp()
        setStatus("Starting...", false)
        updateEnabled()
        Thread({ sampleLoop(dev, my, choice) }, "MobileTestBench-perf").apply { isDaemon = true }.start()
    }

    private fun stopSampling(message: String?) {
        sampleGen++
        sampling = false
        if (message != null) setStatus(message, false)
        updateEnabled()
    }

    /** The sampling thread: one reading per second, never two at once (a slow call just delays the next one). Ends when [sampleGen] moves on. */
    private fun sampleLoop(dev: Device, my: Int, choice: String?) {
        val backend = DeviceServices.perf(dev)
        if (backend == null) {
            SwingUtilities.invokeLater { if (my == sampleGen) stopSampling("No performance data for this device") }
            return
        }
        val sampler = PerfSampler()
        val pssExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "MobileTestBench-perf-pss").apply { isDaemon = true } }
        val pssRunning = AtomicBoolean(false)
        // The cached PSS with the app and pid it belongs to; a result for anything else is dropped.
        val pss = java.util.concurrent.atomic.AtomicReference<Triple<String, Int, Double>?>(null)
        var pssApp: String? = null
        var lastPid: Int? = null
        var lastPssAt = Long.MIN_VALUE
        var app = choice
        var nextForeground = 0L
        val t0 = System.nanoTime()
        var next = t0
        try {
            while (my == sampleGen) {
                var text: String
                var error = false
                var point: PerfPoint
                val elapsed = (System.nanoTime() - t0) / 1e9
                try {
                    if (choice == null && System.nanoTime() >= nextForeground) {
                        nextForeground = System.nanoTime() + 5_000_000_000L
                        app = Apps.parseForeground(DeviceServices.mobilecliData(dev, "apps", "foreground"))?.id
                    }
                    val current = app
                    if (current == null) {
                        point = PerfPoint(elapsed)
                        text = "No foreground app found"
                    } else {
                        if (current != pssApp) { pss.set(null); pssApp = current; lastPssAt = Long.MIN_VALUE; lastPid = null }
                        val reading = backend.sample(current)
                        if (reading.pid == null) {
                            pss.set(null)
                            lastPssAt = Long.MIN_VALUE
                            text = "$current is not running (still polling)"
                        } else {
                            if (lastPid != null && lastPid != reading.pid) { pss.set(null); lastPssAt = Long.MIN_VALUE }
                            val pid = reading.pid
                            // The slow PSS call runs on its own thread every 5 s and is skipped while the previous one is still running.
                            val due = System.nanoTime() - lastPssAt >= 5_000_000_000L
                            if (backend.hasFrames && due && pssRunning.compareAndSet(false, true)) {
                                lastPssAt = System.nanoTime()
                                pssExecutor.execute {
                                    try { backend.pssMb(current)?.let { pss.set(Triple(current, pid, it)) } } catch (_: Exception) { } finally { pssRunning.set(false) }
                                }
                            }
                            text = "Sampling $current (pid ${reading.pid})" + if (lastPid != null && lastPid != reading.pid) " - restarted" else ""
                        }
                        point = sampler.point(reading, pss.get()?.takeIf { it.first == current && it.second == reading.pid }?.third, elapsed)
                        if (reading.pid != null && point.cpuPercent == null) text += ", waiting for the first CPU sample"
                        lastPid = reading.pid
                    }
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    point = PerfPoint(elapsed)
                    text = "Sampling failed: ${e.message?.lineSequence()?.firstOrNull() ?: e.javaClass.simpleName}"
                    error = true
                }
                val shown = text
                val shownError = error
                val shownPoint = point
                SwingUtilities.invokeLater {
                    if (my != sampleGen) return@invokeLater
                    points.add(shownPoint)
                    setStatus(shown, shownError)
                    refreshCharts()
                }
                next += 1_000_000_000L
                val wait = (next - System.nanoTime()) / 1_000_000
                if (wait < 0) next = System.nanoTime() else Thread.sleep(wait)
            }
        } catch (_: InterruptedException) {
        } finally {
            pssExecutor.shutdownNow()
        }
    }

    private fun saveCsv() {
        val list = points.toList()
        if (list.isEmpty()) return
        val descriptor = FileSaverDescriptor("Save Performance Samples", "Save the samples as CSV", "csv")
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, null).save(null as VirtualFile?, "performance.csv") ?: return
        val csv = PerfCsv.toCsv(list)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { target.file.writeText(csv) }
            SwingUtilities.invokeLater {
                result.onFailure { setStatus("Saving failed: ${it.message}", true) }.onSuccess { setStatus("Saved ${list.size} samples to ${target.file.name}", false) }
            }
        }
    }

    // ---- app list

    private fun loadApps() {
        val dev = device ?: return
        val my = ++appListGen
        ApplicationManager.getApplication().executeOnPooledThread {
            val ids = runCatching { Apps.parseList(DeviceServices.mobilecliData(dev, "apps", "list")).map { it.id } }.getOrDefault(emptyList())
            SwingUtilities.invokeLater {
                if (my != appListGen) return@invokeLater
                val typed = chosenApp()
                appModel.removeAllElements()
                appModel.addElement(FOREGROUND)
                ids.forEach { appModel.addElement(it) }
                appCombo.selectedItem = typed ?: FOREGROUND
            }
        }
    }

    // ---- start time

    private fun resetStartResults() {
        runs.clear()
        runModel.fireTableDataChanged()
        summary.text = " "
        progress.text = " "
        measuring = false
    }

    private fun startMeasure() {
        val dev = device ?: return
        val app = chosenApp()
        if (app == null) {
            progress.text = "Pick or type an app first: a start is measured for an explicit app, not the foreground one."
            return
        }
        val kind = kindCombo.selectedItem as StartKind
        val count = (runsSpinner.value as Int).coerceIn(1, 10)
        val message = if (kind == StartKind.COLD) "A cold start stops $app (force-stop / terminate) and starts it $count time(s). Continue?"
        else "A warm start sends $app to the background with the Home button and launches it again, $count time(s). Continue?"
        if (Messages.showOkCancelDialog(message, "Measure App Start", "Measure", "Cancel", Messages.getQuestionIcon()) != Messages.OK) return
        val settings = MobileTestBenchSettings.getInstance().state
        settings.perfStartRuns = count
        settings.perfStartKind = kind.name
        val my = ++measureGen
        cancelMeasure.set(false)
        resetStartResults()
        measuring = true
        progress.text = "Starting..."
        updateEnabled()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val backend: PerfBackend = DeviceServices.perf(dev) ?: error("No performance backend for this device")
                if (dev.platform == Platform.IOS && kind == StartKind.WARM && DeviceServices.mobilecli() == null) error("A warm start on an iOS simulator needs mobilecli for the Home button")
                StartRunner.run(
                    backend, app, kind, count, { cancelMeasure.get() || my != measureGen },
                    progress = { done, total -> SwingUtilities.invokeLater { if (my == measureGen) progress.text = "Run ${done + 1} of $total ..." } },
                    onRun = { run ->
                        SwingUtilities.invokeLater {
                            if (my != measureGen) return@invokeLater
                            runs += run
                            runModel.fireTableDataChanged()
                            run.millis?.let { history.add(StartEntry(app, dev.name, run.kind, it, System.currentTimeMillis())) }
                            summary.text = StartSummary.of(runs)?.text() ?: " "
                            refreshHistory()
                        }
                    },
                )
            }
            SwingUtilities.invokeLater {
                if (my != measureGen) return@invokeLater
                measuring = false
                val done = result.getOrNull()
                progress.text = when {
                    result.isFailure -> "Failed: ${result.exceptionOrNull()?.message}"
                    cancelMeasure.get() -> "Cancelled after ${done?.size ?: 0} run(s)"
                    done?.lastOrNull()?.millis == null -> "Stopped at the first failed run: ${done?.lastOrNull()?.note}"
                    else -> "Done: ${done.size} run(s)"
                }
                updateEnabled()
            }
        }
    }

    private fun refreshHistory() {
        historyModel.clear()
        history.entries().forEach {
            historyModel.addElement("${timeFormat.format(Date(it.atMillis))}  ${it.kind.label}  ${it.millis} ms  ${it.app}  (${it.device})")
        }
    }

    private companion object {
        const val FOREGROUND = "Foreground app"
        val WINDOWS = arrayOf(60, 300, 600)
    }
}
