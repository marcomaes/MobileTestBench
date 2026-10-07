package io.github.marcomaes.mobiletestbench.core.session

import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.DeviceKey
import io.github.marcomaes.mobiletestbench.core.Size
import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import java.awt.image.BufferedImage

interface FrameListener {
    fun onFrame(image: BufferedImage)
    fun onStatus(message: String) {}
    fun onError(message: String, cause: Throwable? = null)
}

/**
 * A live connection to one device. Coordinates for input and hierarchy bounds use
 * the same "logical" coordinate space: pixels on Android, points on iOS.
 * All methods except [start] and [close] may block and must be called off the UI thread.
 */
interface DeviceSession : AutoCloseable {
    val device: Device

    /** Short human readable description of the transport, e.g. "emulator gRPC". */
    val transport: String

    /** The transport and, after a fallback, its reason. Null when a session does not report it. Never blocks. */
    fun transportInfo(): TransportInfo? = null

    val supportsInput: Boolean

    fun start(listener: FrameListener)

    /** Size of the coordinate space used by input and hierarchy bounds, null until known. Never blocks. */
    fun logicalSize(): Size?

    /** Re-reads the logical size after a rotation. Blocks. Sessions whose size follows the frames need nothing. */
    fun refreshSize() {}

    /**
     * Screen density in dpi, to convert Android pixel bounds to dp; null when unknown or when bounds are already points (iOS).
     * May block on the first call (then cached), so call it off the UI thread.
     */
    fun density(): Int? = null

    fun tap(x: Int, y: Int)

    fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long = 300)

    /** Holds a finger on (x, y) for [durationMs]. Blocks for the hold. */
    fun longPress(x: Int, y: Int, durationMs: Long = 700) {
        throw UnsupportedOperationOnDevice("Long press is not available on this connection")
    }

    /** True when [doubleTap] works here: it needs two touches within the platform's double tap timeout (about 300 ms), which only some transports can send. */
    val supportsDoubleTap: Boolean get() = false

    /** Two quick taps at (x, y), fast enough to count as one double tap. */
    fun doubleTap(x: Int, y: Int) {
        throw UnsupportedOperationOnDevice("Double tap is not available on this connection")
    }

    /** True when [dragWithHold] works here. */
    val supportsHoldDrag: Boolean get() = false

    /** Presses at the start, holds [holdMs] without moving, then moves to the end over [durationMs] and releases. */
    fun dragWithHold(fromX: Int, fromY: Int, toX: Int, toY: Int, holdMs: Long = 700, durationMs: Long = 300) {
        throw UnsupportedOperationOnDevice("Drag with hold is not available on this connection")
    }

    fun press(key: DeviceKey)

    fun typeText(text: String)

    /** Presses key combinations one after the other in one call, in mobilecli's spelling: `ctrl+a`, `cmd+a`, `backspace`. */
    fun pressKeys(keys: List<String>) {
        throw UnsupportedOperationOnDevice("Key combinations are not available on this connection")
    }

    fun hierarchy(): UiNode

    override fun close()
}

class UnsupportedOperationOnDevice(message: String) : RuntimeException(message)
