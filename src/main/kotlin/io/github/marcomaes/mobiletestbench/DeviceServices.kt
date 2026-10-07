package io.github.marcomaes.mobiletestbench

import com.intellij.openapi.diagnostic.logger
import com.intellij.util.EnvironmentUtil
import io.github.marcomaes.mobiletestbench.android.GrpcEmulatorLink
import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.DeviceKind
import io.github.marcomaes.mobiletestbench.core.MobilecliJson
import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.android.AdbClient
import io.github.marcomaes.mobiletestbench.core.android.AndroidSession
import io.github.marcomaes.mobiletestbench.core.android.EmulatorDiscovery
import io.github.marcomaes.mobiletestbench.core.discovery.DeviceDiscovery
import io.github.marcomaes.mobiletestbench.core.display.AndroidDisplay
import io.github.marcomaes.mobiletestbench.core.display.DisplayBackend
import io.github.marcomaes.mobiletestbench.core.display.IosDisplay
import io.github.marcomaes.mobiletestbench.core.display.AndroidLocale
import io.github.marcomaes.mobiletestbench.core.display.IosLocale
import io.github.marcomaes.mobiletestbench.core.display.LocaleBackend
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import io.github.marcomaes.mobiletestbench.core.files.AndroidFiles
import io.github.marcomaes.mobiletestbench.core.files.FileBackend
import io.github.marcomaes.mobiletestbench.core.files.FilesException
import io.github.marcomaes.mobiletestbench.core.files.IosFiles
import io.github.marcomaes.mobiletestbench.core.exec.ToolLocator
import io.github.marcomaes.mobiletestbench.core.input.InputActions
import io.github.marcomaes.mobiletestbench.core.ios.IosMobilecliSession
import io.github.marcomaes.mobiletestbench.core.ios.MobilecliClient
import io.github.marcomaes.mobiletestbench.core.logs.AndroidLogParser
import io.github.marcomaes.mobiletestbench.core.logs.IosLogParser
import io.github.marcomaes.mobiletestbench.core.logs.LogEntry
import io.github.marcomaes.mobiletestbench.core.logs.LogStream
import io.github.marcomaes.mobiletestbench.core.logs.MobilecliLogParser
import io.github.marcomaes.mobiletestbench.core.battery.AndroidBattery
import io.github.marcomaes.mobiletestbench.core.memory.AndroidMemory
import io.github.marcomaes.mobiletestbench.core.network.AndroidNetwork
import io.github.marcomaes.mobiletestbench.core.apps.AndroidPackages
import io.github.marcomaes.mobiletestbench.core.permissions.AndroidPermissions
import io.github.marcomaes.mobiletestbench.core.permissions.IosPermissions
import io.github.marcomaes.mobiletestbench.core.power.PowerAction
import io.github.marcomaes.mobiletestbench.core.recording.ScreenRecording
import io.github.marcomaes.mobiletestbench.core.ios.IosSimulatorSession
import io.github.marcomaes.mobiletestbench.core.ios.SimctlClient
import io.github.marcomaes.mobiletestbench.core.ios.WdaClient
import io.github.marcomaes.mobiletestbench.core.session.DeviceSession
import io.github.marcomaes.mobiletestbench.settings.MobileTestBenchSettings

/** Wires the IDE environment and settings into the IDE independent core. Call off the EDT. */
object DeviceServices {
    private val log = logger<DeviceServices>()

    private val environment: Map<String, String> by lazy { EnvironmentUtil.getEnvironmentMap() }
    private val runner: CommandRunner by lazy { CommandRunner(environment) }
    private val defaultTools: ToolLocator by lazy { ToolLocator(environment) }

    /** Tests set this to simulate a machine without some tools; null uses the IDE environment. */
    @Volatile internal var toolsOverride: ToolLocator? = null
    private val tools: ToolLocator get() = toolsOverride ?: defaultTools

    private val settings get() = MobileTestBenchSettings.getInstance().state

    fun adbPath(): String = settings.adbPath?.takeIf { it.isNotBlank() } ?: tools.adb()

    fun adb(): AdbClient = AdbClient(adbPath(), runner)

    fun simctl(): SimctlClient? = if (tools.isMac) SimctlClient(tools.xcrun(), runner) else null

    /** Null when mobilecli is not installed: physical iOS devices are then not listed and simulators fall back to simctl + WDA. */
    fun mobilecli(): MobilecliClient? = tools.mobilecli()?.let { MobilecliClient(it, runner) }

    /**
     * Runs `mobilecli <args> --device <id>` for [device] (the id is mapped, e.g. emulator-5554 to the AVD name) and returns stdout,
     * normally `{"status":"ok","data":...}`. Throws with mobilecli's own error text on failure. Blocks; call off the EDT.
     */
    fun mobilecliCall(device: Device, vararg args: String, timeout: java.time.Duration = java.time.Duration.ofSeconds(20)): String {
        val mobilecli = mobilecli() ?: error("mobilecli is required for this feature (npm install -g mobilecli)")
        return mobilecli.call(MobilecliClient.idFor(device, adb()), args.toList(), timeout)
    }

    /** Like [mobilecliCall] but returns the envelope's `data` (null when there is none). */
    fun mobilecliData(device: Device, vararg args: String, timeout: java.time.Duration = java.time.Duration.ofSeconds(20)): com.google.gson.JsonElement? =
        MobilecliJson.data(mobilecliCall(device, *args, timeout = timeout))

    /** Starts recording the device screen to [output] (MP4). Needs mobilecli. Blocks about a second; call off the EDT. */
    fun startScreenRecording(device: Device, output: java.io.File): ScreenRecording {
        val mobilecli = mobilecli() ?: error("mobilecli is required for screen recording (npm install -g mobilecli)")
        return ScreenRecording.start(runner, mobilecli.screenRecordCommand(MobilecliClient.idFor(device, adb()), output.path), output)
    }

    /**
     * Starts the live device log: `adb logcat` on Android, `log stream` on a simulator. Null for devices without a log source
     * (physical iPhones without mobilecli); a physical iPhone uses `mobilecli device logs`. Call off the EDT.
     */
    fun startLog(device: Device, onEntry: (LogEntry) -> Unit, onEnd: (String?) -> Unit): LogStream? = when {
        device.platform == Platform.ANDROID ->
            LogStream.start({ runner.start(adb().logcatCommand(device.id)) }, AndroidLogParser::parse, onEntry, onEnd)
        device.platform == Platform.IOS && device.kind == DeviceKind.SIMULATOR ->
            simctl()?.let { simctl -> LogStream.start({ runner.start(simctl.logStreamCommand(device.id)) }, IosLogParser::parse, onEntry, onEnd) }
        // A physical iPhone has no adb/simctl log: mobilecli is the only source (experimental, see logSourceNote).
        device.platform == Platform.IOS && device.kind != DeviceKind.SIMULATOR ->
            mobilecli()?.let { cli -> LogStream.start({ runner.start(cli.logsCommand(device.id)) }, MobilecliLogParser::parse, onEntry, onEnd) }
        else -> null
    }

    /** A caveat to show next to the log of [device] (null when the source is the normal one). */
    fun logSourceNote(device: Device): String? =
        if (device.platform == Platform.IOS && device.kind != DeviceKind.SIMULATOR) "via mobilecli (experimental, not verified on a physical iPhone)" else null

    /** The process ids of an Android app (to show only its log lines). Empty when it is not running. */
    fun androidPids(device: Device, packageName: String): Set<String> = adb().pidsOf(device.id, packageName)

    /** Runs a Device Power command (`device boot|shutdown|reboot`) on [device] with the action's own timeout. Blocks; call off the EDT. */
    fun powerCall(device: Device, action: PowerAction) {
        mobilecliCall(device, *action.args.toTypedArray(), timeout = action.timeout)
    }

    /** The Display tab's backend for [device]: adb for Android, `simctl ui` for iOS simulators. Null for physical iPhones (nothing to call). */
    fun display(device: Device): DisplayBackend? = when {
        device.platform == Platform.ANDROID -> AndroidDisplay(adbPath(), device.id, runner)
        device.kind == DeviceKind.SIMULATOR && tools.isMac -> IosDisplay(tools.xcrun(), device.id, runner)
        else -> null
    }

    /** The language and time zone backend of the Display tab: adb for Android, `simctl spawn defaults` for iOS simulators. */
    fun locale(device: Device): LocaleBackend? = when {
        device.platform == Platform.ANDROID -> AndroidLocale(adbPath(), device.id, runner)
        device.kind == DeviceKind.SIMULATOR && tools.isMac -> IosLocale(tools.xcrun(), device.id, runner)
        else -> null
    }

    /** The Input tab's actions for [device]. Maps the mobilecli id (a blocking adb call for emulators), so call off the EDT. */
    fun input(device: Device): InputActions {
        val mobilecli = mobilecli()
        return InputActions(
            device.platform, device.id, runner,
            adb = if (device.platform == Platform.ANDROID) adbPath() else null,
            xcrun = if (device.platform == Platform.IOS && device.kind == DeviceKind.SIMULATOR && tools.isMac) tools.xcrun() else null,
            mobilecli = mobilecli,
            mobilecliId = mobilecli?.let { MobilecliClient.idFor(device, adb()) },
        )
    }

    /** The Battery tab's backend: Android only (adb `dumpsys battery`). */
    fun battery(device: Device): AndroidBattery? =
        if (device.platform == Platform.ANDROID) AndroidBattery(adbPath(), device.id, runner) else null

    /** The memory warning backend of the Battery tab: Android only (`am send-trim-memory`). */
    fun memory(device: Device): AndroidMemory? =
        if (device.platform == Platform.ANDROID) AndroidMemory(adbPath(), device.id, runner) else null

    /** The Network tab's backend: Android only (adb); the speed and latency part is enabled for emulators (serial emulator-NNNN). */
    fun network(device: Device): AndroidNetwork? =
        if (device.platform == Platform.ANDROID) AndroidNetwork(adbPath(), device.id, runner, EmulatorDiscovery.consolePort(device.id) != null) else null

    /** The Notifications tab's iOS backend (simulators on macOS: `simctl push`). */
    fun iosNotifications(device: Device): io.github.marcomaes.mobiletestbench.core.notifications.IosNotifications? =
        if (tools.isMac && device.platform == Platform.IOS && device.kind == DeviceKind.SIMULATOR)
            io.github.marcomaes.mobiletestbench.core.notifications.IosNotifications(tools.xcrun(), device.id, runner) else null

    /** The Notifications tab's Android backend (adb shell, one remote quoted command string per call). */
    fun androidNotifications(device: Device): io.github.marcomaes.mobiletestbench.core.notifications.AndroidNotifications? =
        if (device.platform == Platform.ANDROID) io.github.marcomaes.mobiletestbench.core.notifications.AndroidNotifications(adbPath(), device.id, runner) else null

    /** The Biometrics tab's Android backend (emulators only: `adb emu`). */
    fun androidBiometrics(device: Device): io.github.marcomaes.mobiletestbench.core.biometrics.AndroidBiometrics? =
        if (device.platform == Platform.ANDROID && EmulatorDiscovery.consolePort(device.id) != null)
            io.github.marcomaes.mobiletestbench.core.biometrics.AndroidBiometrics(adbPath(), device.id, runner) else null

    /** The Biometrics tab's iOS backend (simulators on macOS). */
    fun iosBiometrics(device: Device): io.github.marcomaes.mobiletestbench.core.biometrics.IosBiometrics? =
        if (tools.isMac && device.platform == Platform.IOS && device.kind == DeviceKind.SIMULATOR)
            io.github.marcomaes.mobiletestbench.core.biometrics.IosBiometrics(tools.xcrun(), device.id, runner) else null

    /**
     * The Files tab's backend for [device]: device storage ([appId] null) or an app's data. Android uses adb (`run-as` for app data, debuggable apps
     * only); iOS simulators use `mobilecli fs` with host paths. Blocks (resolves the iOS root), so call off the EDT; throws [FilesException] when
     * the device has no file access (physical iPhones are not supported) or the app has no data container.
     */
    fun files(device: Device, appId: String?): FileBackend = when {
        device.platform == Platform.ANDROID -> AndroidFiles(adbPath(), device.id, runner, appId)
        device.kind == DeviceKind.SIMULATOR && tools.isMac && mobilecli() != null -> {
            val call = { args: List<String> -> mobilecliCall(device, *args.toTypedArray(), timeout = java.time.Duration.ofMinutes(5)) }
            val root = if (appId == null) IosFiles.deviceRoot(call) else IosFiles.appContainer(runner, tools.xcrun(), device.id, appId)
            IosFiles(call, root, appId)
        }
        device.platform == Platform.IOS && device.kind == DeviceKind.SIMULATOR -> throw FilesException("Files on an iOS simulator need mobilecli (npm install -g mobilecli)")
        else -> throw FilesException("Files supports Android devices and iOS simulators, not physical iPhones")
    }

    /**
     * The Performance tab's backend: Android through adb, iOS simulators through the Mac side (launchctl, footprint, ps, the simulator log).
     * Null for physical iPhones. The home button of a warm iOS start goes through mobilecli, so call [PerfBackend.measure] only with it installed.
     */
    fun perf(device: Device): io.github.marcomaes.mobiletestbench.core.perf.PerfBackend? = when {
        device.platform == Platform.ANDROID -> io.github.marcomaes.mobiletestbench.core.perf.AndroidPerf(adbPath(), device.id, runner)
        device.kind == DeviceKind.SIMULATOR && tools.isMac ->
            io.github.marcomaes.mobiletestbench.core.perf.IosPerf(tools.xcrun(), device.id, runner, goHome = { mobilecliCall(device, "io", "button", "HOME") })
        else -> null
    }

    /** User installed packages of an Android [device] (adb), to tell system apps apart. */
    fun androidPackages(device: Device): AndroidPackages = AndroidPackages(adbPath(), device.id, runner)

    /** Android runtime permissions of apps on [device] (adb). */
    fun androidPermissions(device: Device): AndroidPermissions = AndroidPermissions(adbPath(), device.id, runner)

    /** Privacy services of apps on an iOS simulator; null when not on macOS or [device] is not a simulator. */
    fun iosPermissions(device: Device): IosPermissions? =
        if (tools.isMac && device.platform == Platform.IOS && device.kind == DeviceKind.SIMULATOR) IosPermissions(tools.xcrun(), device.id, runner) else null

    fun discovery(): DeviceDiscovery = DeviceDiscovery(adb(), simctl(), mobilecli())

    fun createSession(device: Device): DeviceSession = when (device.platform) {
        Platform.ANDROID -> {
            val link = if (settings.useEmulatorGrpc && device.kind == DeviceKind.EMULATOR) {
                val dirs = EmulatorDiscovery.defaultDirectories(environment, System.getProperty("os.name"), System.getProperty("user.home"))
                EmulatorDiscovery.findEndpoint(device.id, dirs)
                    ?.also { log.info("Using emulator gRPC on port ${it.port} for ${device.id}") }
                    ?.let { runCatching { GrpcEmulatorLink(it) }.onFailure { e -> log.warn(e) }.getOrNull() }
            } else {
                null
            }
            AndroidSession(device, adb(), link, settings.screenshotIntervalMs.toLong(), mobilecli())
        }
        Platform.IOS -> {
            val mobilecli = mobilecli()
            if (mobilecli != null) {
                IosMobilecliSession(device, mobilecli)
            } else {
                check(device.kind != DeviceKind.PHYSICAL) { "mobilecli is required for physical iOS devices (npm install -g mobilecli)" }
                val simctl = simctl() ?: error("iOS simulators are only available on macOS")
                val wda = settings.wdaUrl?.takeIf { it.isNotBlank() }?.let(::WdaClient)?.takeIf { it.isReachable() }
                val mjpeg = settings.mjpegUrl?.takeIf { it.isNotBlank() && wda != null }
                IosSimulatorSession(device, simctl, wda, mjpeg)
            }
        }
    }
}
