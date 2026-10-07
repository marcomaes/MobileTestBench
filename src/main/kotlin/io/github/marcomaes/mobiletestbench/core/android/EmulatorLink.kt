package io.github.marcomaes.mobiletestbench.core.android

import io.github.marcomaes.mobiletestbench.core.DeviceKey
import io.github.marcomaes.mobiletestbench.core.session.FrameListener

/**
 * High bandwidth channel to an Android emulator (implemented with the emulator gRPC API).
 * Kept as an interface so the core does not depend on gRPC.
 */
interface EmulatorLink : AutoCloseable {
    fun startStreaming(listener: FrameListener)
    fun tap(x: Int, y: Int)
    fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long)
    fun longPress(x: Int, y: Int, durationMs: Long)

    /** Down, up, short gap, down, up: the gap stays well under Android's 300 ms double tap timeout. */
    fun doubleTap(x: Int, y: Int)

    /** Press, hold without moving, then move to the end point and release. */
    fun dragWithHold(fromX: Int, fromY: Int, toX: Int, toY: Int, holdMs: Long, durationMs: Long)
    fun press(key: DeviceKey)
    fun typeText(text: String)
    override fun close()
}
