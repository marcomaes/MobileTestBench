package io.github.marcomaes.mobiletestbench.core.ios

import io.github.marcomaes.mobiletestbench.core.session.TransportInfo
import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.FrameOrientation
import io.github.marcomaes.mobiletestbench.core.DeviceKey
import io.github.marcomaes.mobiletestbench.core.Size
import io.github.marcomaes.mobiletestbench.core.discovery.MobilecliDeviceInfoParser
import io.github.marcomaes.mobiletestbench.core.inspector.MobilecliDumpParser
import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import io.github.marcomaes.mobiletestbench.core.session.DeviceSession
import io.github.marcomaes.mobiletestbench.core.session.FrameListener
import io.github.marcomaes.mobiletestbench.core.session.PollingFrameSource
import io.github.marcomaes.mobiletestbench.core.session.UnsupportedOperationOnDevice
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * iOS device (physical or simulator) through the mobilecli CLI.
 *  - Frames: `mobilecli screencapture -f mjpeg`, falling back to `mobilecli screenshot` polling.
 *  - Input and hierarchy: mobilecli (its on-device agent), coordinates in points.
 */
class IosMobilecliSession(
    override val device: Device,
    private val mobilecli: MobilecliClient,
    private val pollIntervalMs: Long = 500,
) : DeviceSession {

    @Volatile private var mode = "mobilecli MJPEG"
    override val transport: String get() = mode
    @Volatile private var fallbackReason: String? = null
    override fun transportInfo() = TransportInfo(mode, fallbackReason)
    override val supportsInput: Boolean get() = true

    private var stream: MobilecliMjpegStream? = null
    private var polling: PollingFrameSource? = null
    @Volatile private var screenSize: Size? = null

    /** The simulator keeps a portrait framebuffer in landscape; turn frames upright so they match [logicalSize]. */
    private fun uprightListener(listener: FrameListener) = object : FrameListener {
        override fun onFrame(image: java.awt.image.BufferedImage) = listener.onFrame(FrameOrientation.upright(image, screenSize))
        override fun onStatus(message: String) = listener.onStatus(message)
        override fun onError(message: String, cause: Throwable?) = listener.onError(AgentInstallHint.rewrite(message, device.id), cause)
    }

    override fun start(listener: FrameListener) {
        Thread({ refreshScreenSize() }, "MobileTestBench-info-${device.id}").apply {
            isDaemon = true
            start()
        }
        val upright = uprightListener(listener)
        stream = MobilecliMjpegStream(mobilecli, device.id).also { source ->
            source.start(upright) { frames, error ->
                fallbackReason = when {
                    error != null -> "mobilecli MJPEG stream unavailable: $error"
                    frames == 0 -> "mobilecli MJPEG stream ended without frames"
                    else -> "mobilecli MJPEG stream ended after $frames frames"
                }
                if (frames == 0) upright.onStatus("mobilecli stream ${error?.let { "unavailable ($it)" } ?: "ended without frames"}, falling back to screenshots")
                mode = "mobilecli screenshots"
                polling = PollingFrameSource("mobilecli-${device.id}", pollIntervalMs) {
                    ImageIO.read(ByteArrayInputStream(mobilecli.screenshotPng(device.id)))
                }.also { it.start(upright) }
            }
        }
    }

    private fun refreshScreenSize() {
        runCatching { MobilecliDeviceInfoParser.parseScreenSize(mobilecli.deviceInfoJson(device.id)) }
            .getOrNull()?.let { screenSize = it }
    }

    override fun logicalSize(): Size? = screenSize

    override fun refreshSize() = refreshScreenSize()

    override fun tap(x: Int, y: Int) = mobilecli.tap(device.id, x, y)

    override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) =
        mobilecli.swipe(device.id, fromX, fromY, toX, toY, durationMs)

    override fun longPress(x: Int, y: Int, durationMs: Long) = mobilecli.longPress(device.id, x, y, durationMs)

    override fun press(key: DeviceKey) {
        when (key) {
            DeviceKey.HOME -> mobilecli.button(device.id, "HOME")
            DeviceKey.VOLUME_UP -> mobilecli.button(device.id, "VOLUME_UP")
            DeviceKey.VOLUME_DOWN -> mobilecli.button(device.id, "VOLUME_DOWN")
            DeviceKey.ENTER -> mobilecli.key(device.id, "enter")
            DeviceKey.DELETE -> mobilecli.key(device.id, "backspace")
            DeviceKey.BACK, DeviceKey.APP_SWITCH, DeviceKey.POWER ->
                throw UnsupportedOperationOnDevice("${key.name} is not available through mobilecli on iOS")
        }
    }

    override fun typeText(text: String) = mobilecli.text(device.id, text)

    override fun pressKeys(keys: List<String>) = mobilecli.keys(device.id, keys)

    override fun hierarchy(): UiNode {
        refreshScreenSize() // changes on rotation
        return MobilecliDumpParser.parse(mobilecli.dumpUiJson(device.id))
    }

    override fun close() {
        stream?.close()
        stream = null
        polling?.close()
        polling = null
    }
}
