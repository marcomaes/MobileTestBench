package io.github.marcomaes.mobiletestbench.core.android

import io.github.marcomaes.mobiletestbench.core.session.TransportInfo
import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.DeviceKey
import io.github.marcomaes.mobiletestbench.core.Size
import io.github.marcomaes.mobiletestbench.core.inspector.MobilecliAndroidRawParser
import io.github.marcomaes.mobiletestbench.core.inspector.UiAutomatorParser
import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import io.github.marcomaes.mobiletestbench.core.ios.MobilecliClient
import io.github.marcomaes.mobiletestbench.core.ios.MobilecliMjpegStream
import io.github.marcomaes.mobiletestbench.core.session.DeviceSession
import io.github.marcomaes.mobiletestbench.core.session.FrameListener
import io.github.marcomaes.mobiletestbench.core.session.PollingFrameSource
import io.github.marcomaes.mobiletestbench.core.session.UnsupportedOperationOnDevice
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Android device or emulator. When an [EmulatorLink] is available (emulator with gRPC),
 * frames and input go through it; otherwise adb screenshots and `adb shell input` are used.
 * The hierarchy comes from `mobilecli dump ui` when it is installed, otherwise (or when that fails) from uiautomator via adb.
 */
class AndroidSession(
    override val device: Device,
    private val adb: AdbClient,
    private val emulatorLink: EmulatorLink?,
    private val pollIntervalMs: Long = 250,
    private val mobilecli: MobilecliClient? = null,
    private val displayWatchMs: Long = 2000,
) : DeviceSession {

    /** Set to null when the gRPC link fails before delivering a frame, so adb takes over. */
    @Volatile private var link: EmulatorLink? = emulatorLink

    @Volatile private var mode = if (emulatorLink != null) "emulator gRPC" else "adb screenshots"
    override val transport: String get() = mode
    @Volatile private var fallbackReason: String? = null
    override fun transportInfo() = TransportInfo(transport, fallbackReason)
    override val supportsInput: Boolean = true

    @Volatile private var displaySize: Size? = null
    @Volatile private var lastFrameSize: Size? = null
    @Volatile private var gotLinkFrame = false
    private var polling: PollingFrameSource? = null
    private var stream: MobilecliMjpegStream? = null
    private var displayWatcher: Thread? = null

    override fun start(listener: FrameListener) {
        displaySize = runCatching { adb.displaySize(device.id) }.getOrNull()
        val tracking = object : FrameListener {
            override fun onFrame(image: BufferedImage) {
                lastFrameSize = Size(image.width, image.height)
                listener.onFrame(image)
            }
            override fun onStatus(message: String) = listener.onStatus(message)
            override fun onError(message: String, cause: Throwable?) = listener.onError(message, cause)
        }
        val current = link
        if (current == null) {
            fallbackReason = "No emulator gRPC link (not an emulator, gRPC unavailable or disabled in settings)"
            startFallback(tracking)
            return
        }
        current.startStreaming(object : FrameListener {
            override fun onFrame(image: BufferedImage) {
                gotLinkFrame = true
                tracking.onFrame(image)
            }
            override fun onStatus(message: String) = tracking.onStatus(message)
            override fun onError(message: String, cause: Throwable?) {
                if (gotLinkFrame) {
                    tracking.onError(message, cause)
                    return
                }
                // e.g. the emulator requires JWT auth or was started without gRPC.
                link = null
                fallbackReason = "gRPC failed before the first frame: $message"
                runCatching { current.close() }
                tracking.onStatus("$message. Falling back to ${if (mobilecli != null) "the mobilecli stream" else "adb screenshots"}.")
                startFallback(tracking)
            }
        })
    }

    /** The mobilecli MJPEG stream when installed (fast, also on physical phones), adb screenshots when it is missing or fails. */
    private fun startFallback(listener: FrameListener) {
        val client = mobilecli ?: return startPolling(listener)
        mode = "mobilecli MJPEG"
        val previousReason = fallbackReason
        openStream(client, listener, previousReason)
        watchDisplay(client, listener, previousReason)
    }

    private fun openStream(client: MobilecliClient, listener: FrameListener, previousReason: String?) {
        stream = MobilecliMjpegStream(client, mobilecliId).also { source ->
            source.start(listener) { frames, error ->
                val why = error?.let { "unavailable: $it" } ?: if (frames == 0) "ended without frames" else "ended after $frames frames"
                fallbackReason = listOfNotNull(previousReason, "mobilecli MJPEG stream $why").joinToString("; ")
                listener.onStatus("mobilecli stream $why, falling back to adb screenshots")
                startPolling(listener)
            }
        }
    }

    /**
     * A foldable switches displays when it folds, but mobilecli's capture display keeps the size it was created with, so the
     * stream would show the old display. `wm size` follows the active display (not the rotation): restart the stream when it changes.
     */
    private fun watchDisplay(client: MobilecliClient, listener: FrameListener, previousReason: String?) {
        displayWatcher = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(displayWatchMs)
                    val size = runCatching { adb.displaySize(device.id) }.getOrNull() ?: continue
                    if (size == displaySize) continue
                    val known = displaySize != null
                    displaySize = size
                    if (!known) continue
                    synchronized(this) {
                        if (mode != "mobilecli MJPEG" || Thread.currentThread().isInterrupted) return@Thread
                        stream?.close()
                        listener.onStatus("Display changed to ${size.width}x${size.height}, restarting the stream")
                        openStream(client, listener, previousReason)
                    }
                }
            } catch (_: InterruptedException) {
            }
        }, "MobileTestBench-display-${device.id}").apply {
            isDaemon = true
            start()
        }
    }

    private fun startPolling(listener: FrameListener) {
        mode = "adb screenshots"
        polling = PollingFrameSource("adb-${device.id}", pollIntervalMs) {
            ImageIO.read(ByteArrayInputStream(adb.screencapPng(device.id)))
        }.also { it.start(listener) }
    }

    @Volatile private var cachedDensity: Int? = null

    /** `wm density`, read once (a later change needs a reconnect). A failed read is retried on the next call. */
    override fun density(): Int? {
        cachedDensity?.let { return it }
        val read = runCatching { adb.displayDensity(device.id) }.getOrNull()
        cachedDensity = read
        return read
    }

    /** Display pixels, oriented like the most recent frame (handles rotation). */
    override fun logicalSize(): Size? {
        val frame = lastFrameSize
        val display = displaySize ?: return frame
        if (frame == null) return display
        return if (frame.isLandscape != display.isLandscape) display.swapped() else display
    }

    override fun tap(x: Int, y: Int) {
        link?.tap(x, y) ?: adb.tap(device.id, x, y)
    }

    override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) {
        link?.swipe(fromX, fromY, toX, toY, durationMs) ?: adb.swipe(device.id, fromX, fromY, toX, toY, durationMs)
    }

    /** gRPC holds the pointer down; without it adb sends a swipe that starts and ends at the same point. */
    override fun longPress(x: Int, y: Int, durationMs: Long) {
        link?.longPress(x, y, durationMs) ?: adb.swipe(device.id, x, y, x, y, durationMs)
    }

    /** Only gRPC is fast enough: each adb or mobilecli call costs 150 ms or more, past the 300 ms double tap timeout, so they would be two single taps. */
    override val supportsDoubleTap: Boolean get() = link != null

    override fun doubleTap(x: Int, y: Int) {
        (link ?: throw UnsupportedOperationOnDevice("Double tap on Android needs the emulator gRPC connection: adb and mobilecli are too slow for the double tap timeout")).doubleTap(x, y)
    }

    override val supportsHoldDrag: Boolean get() = link != null

    override fun dragWithHold(fromX: Int, fromY: Int, toX: Int, toY: Int, holdMs: Long, durationMs: Long) {
        (link ?: throw UnsupportedOperationOnDevice("Drag with hold on Android needs the emulator gRPC connection")).dragWithHold(fromX, fromY, toX, toY, holdMs, durationMs)
    }

    override fun press(key: DeviceKey) {
        link?.let {
            it.press(key)
            return
        }
        val code = when (key) {
            DeviceKey.HOME -> AdbClient.KEYCODE_HOME
            DeviceKey.BACK -> AdbClient.KEYCODE_BACK
            DeviceKey.APP_SWITCH -> AdbClient.KEYCODE_APP_SWITCH
            DeviceKey.ENTER -> AdbClient.KEYCODE_ENTER
            DeviceKey.DELETE -> AdbClient.KEYCODE_DEL
            DeviceKey.VOLUME_UP -> AdbClient.KEYCODE_VOLUME_UP
            DeviceKey.VOLUME_DOWN -> AdbClient.KEYCODE_VOLUME_DOWN
            DeviceKey.POWER -> AdbClient.KEYCODE_POWER
        }
        adb.keyEvent(device.id, code)
    }

    override fun typeText(text: String) {
        link?.typeText(text) ?: adb.text(device.id, text)
    }

    /** mobilecli when installed (one call); otherwise adb for the two keys select-all and backspace need. */
    override fun pressKeys(keys: List<String>) {
        if (mobilecli != null) {
            mobilecli.keys(mobilecliId, keys)
            return
        }
        for (key in keys) when (key) {
            "ctrl+a" -> adb.keyCombination(device.id, AdbClient.KEYCODE_CTRL_LEFT, AdbClient.KEYCODE_A)
            "backspace" -> adb.keyEvent(device.id, AdbClient.KEYCODE_DEL)
            else -> throw UnsupportedOperationOnDevice("Key $key needs mobilecli on Android")
        }
    }

    /**
     * mobilecli first when installed: its dump takes about 0.1 s, while `uiautomator dump` is killed on
     * some emulator images (exit 137) and costs time on every attempt. uiautomator is the fallback.
     */
    override fun hierarchy(): UiNode {
        if (mobilecli != null) {
            try {
                return MobilecliAndroidRawParser.parse(mobilecli.dumpUiRawJson(mobilecliId))
            } catch (e: Exception) {
                // fall through to uiautomator; its error is the one to report
            }
        }
        return UiAutomatorParser.parse(adb.dumpHierarchy(device.id))
    }

    /** mobilecli names an emulator by its AVD name, not by the adb serial. */
    private val mobilecliId: String by lazy { MobilecliClient.idFor(device, adb) }

    override fun close() {
        displayWatcher?.interrupt()
        displayWatcher = null
        synchronized(this) {
            stream?.close()
            stream = null
        }
        polling?.close()
        polling = null
        link?.close()
    }
}
