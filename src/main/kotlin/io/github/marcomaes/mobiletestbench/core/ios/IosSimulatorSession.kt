package io.github.marcomaes.mobiletestbench.core.ios

import io.github.marcomaes.mobiletestbench.core.session.TransportInfo
import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.DeviceKey
import io.github.marcomaes.mobiletestbench.core.Size
import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import io.github.marcomaes.mobiletestbench.core.inspector.WdaSourceParser
import io.github.marcomaes.mobiletestbench.core.session.DeviceSession
import io.github.marcomaes.mobiletestbench.core.session.FrameListener
import io.github.marcomaes.mobiletestbench.core.session.PollingFrameSource
import io.github.marcomaes.mobiletestbench.core.session.UnsupportedOperationOnDevice
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

/**
 * iOS simulator.
 *  - Frames: WDA MJPEG stream when available, otherwise `simctl io screenshot` polling.
 *  - Input and hierarchy: WebDriverAgent (required, there is no public simulator input API).
 */
class IosSimulatorSession(
    override val device: Device,
    private val simctl: SimctlClient,
    private val wda: WdaClient?,
    private val mjpegUrl: String?,
    private val pollIntervalMs: Long = 400,
) : DeviceSession {

    @Volatile private var mode = "simctl screenshots"
    override val transport: String get() = mode
    @Volatile private var fallbackReason: String? = null
    override fun transportInfo() = TransportInfo(mode, fallbackReason)
    override val supportsInput: Boolean get() = wda != null

    private val running = AtomicBoolean(false)
    private var mjpegThread: Thread? = null
    private var polling: PollingFrameSource? = null
    @Volatile private var windowSize: Size? = null

    override fun start(listener: FrameListener) {
        running.set(true)
        windowSize = wda?.let { runCatching { it.windowSize() }.getOrNull() }
        if (wda != null && mjpegUrl != null) {
            mode = "WDA MJPEG"
            mjpegThread = Thread({ runMjpeg(listener) }, "MobileTestBench-mjpeg-${device.id}").apply {
                isDaemon = true
                start()
            }
        } else {
            fallbackReason = if (wda == null) "WebDriverAgent not configured: simctl screenshots only, no input or inspection" else "No WDA MJPEG URL configured"
            startPolling(listener)
        }
    }

    private fun runMjpeg(listener: FrameListener) {
        try {
            val connection = URI.create(mjpegUrl!!).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 10000
            connection.inputStream.use { stream ->
                MjpegReader(stream).readFrames({ !running.get() }) { jpeg ->
                    ImageIO.read(ByteArrayInputStream(jpeg))?.let(listener::onFrame)
                }
            }
        } catch (e: Exception) {
            if (!running.get()) return
            fallbackReason = "WDA MJPEG stream unavailable: ${e.message}"
            listener.onStatus("MJPEG stream unavailable (${e.message}), falling back to simctl screenshots")
        }
        if (running.get()) {
            mode = "simctl screenshots"
            if (fallbackReason == null) fallbackReason = "WDA MJPEG stream ended"
            startPolling(listener)
        }
    }

    private fun startPolling(listener: FrameListener) {
        polling = PollingFrameSource("simctl-${device.id}", pollIntervalMs) {
            ImageIO.read(ByteArrayInputStream(simctl.screenshotPng(device.id)))
        }.also { it.start(listener) }
    }

    override fun logicalSize(): Size? = windowSize

    private fun requireWda(): WdaClient =
        wda ?: throw UnsupportedOperationOnDevice("Input and inspection on iOS need WebDriverAgent. Configure the WDA URL in Settings | Tools | MobileTestBench.")

    override fun tap(x: Int, y: Int) = requireWda().tap(x, y)

    override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) =
        requireWda().swipe(fromX, fromY, toX, toY, durationMs)

    /** WDA W3C pointer actions. Not verified: WebDriverAgent was not available when this was written. */
    override fun longPress(x: Int, y: Int, durationMs: Long) = requireWda().longPress(x, y, durationMs)

    override val supportsDoubleTap: Boolean get() = wda != null

    override fun doubleTap(x: Int, y: Int) = requireWda().doubleTap(x, y)

    override val supportsHoldDrag: Boolean get() = wda != null

    override fun dragWithHold(fromX: Int, fromY: Int, toX: Int, toY: Int, holdMs: Long, durationMs: Long) =
        requireWda().dragWithHold(fromX, fromY, toX, toY, holdMs, durationMs)

    override fun press(key: DeviceKey) {
        val client = requireWda()
        when (key) {
            DeviceKey.HOME -> client.pressButton("home")
            DeviceKey.VOLUME_UP -> client.pressButton("volumeUp")
            DeviceKey.VOLUME_DOWN -> client.pressButton("volumeDown")
            DeviceKey.ENTER -> client.typeText("\n")
            DeviceKey.DELETE -> client.typeText("\b")
            DeviceKey.BACK, DeviceKey.APP_SWITCH, DeviceKey.POWER ->
                throw UnsupportedOperationOnDevice("${key.name} is not available on iOS simulators")
        }
    }

    override fun typeText(text: String) = requireWda().typeText(text)

    override fun hierarchy(): UiNode {
        val client = requireWda()
        // Refresh the window size as well, it changes on rotation.
        runCatching { client.windowSize() }.getOrNull()?.let { windowSize = it }
        return WdaSourceParser.parse(client.source())
    }

    override fun close() {
        running.set(false)
        mjpegThread?.interrupt()
        mjpegThread = null
        polling?.close()
        polling = null
        wda?.close()
    }
}
