package io.github.marcomaes.mobiletestbench.android

import com.android.emulator.control.EmulatorControllerGrpc
import com.android.emulator.control.Image
import com.android.emulator.control.ImageFormat
import com.android.emulator.control.KeyboardEvent
import com.android.emulator.control.MouseEvent
import io.github.marcomaes.mobiletestbench.core.DeviceKey
import io.github.marcomaes.mobiletestbench.core.android.EmulatorGrpcEndpoint
import io.github.marcomaes.mobiletestbench.core.android.EmulatorLink
import io.github.marcomaes.mobiletestbench.core.session.FrameListener
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.ClientCallStreamObserver
import io.grpc.stub.ClientResponseObserver
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.concurrent.TimeUnit

/**
 * Talks to the emulator's EmulatorController gRPC service (the same API Android Studio's
 * Running Devices window uses): streamScreenshot for frames, sendMouse/sendKey for input.
 */
class GrpcEmulatorLink(endpoint: EmulatorGrpcEndpoint) : EmulatorLink {

    private val channel: ManagedChannel = OkHttpChannelBuilder.forAddress(endpoint.host, endpoint.port)
        .usePlaintext()
        .maxInboundMessageSize(128 * 1024 * 1024)
        .apply { endpoint.token?.let { intercept(BearerTokenInterceptor(it)) } }
        .build()

    private val asyncStub = EmulatorControllerGrpc.newStub(channel)
    private val blockingStub = EmulatorControllerGrpc.newBlockingStub(channel)

    @Volatile private var streamCall: ClientCallStreamObserver<ImageFormat>? = null
    @Volatile private var closed = false

    override fun startStreaming(listener: FrameListener) {
        // Width/height 0 means native display resolution, already rotated correctly.
        val format = ImageFormat.newBuilder().setFormat(ImageFormat.ImgFormat.RGB888).build()
        asyncStub.streamScreenshot(format, object : ClientResponseObserver<ImageFormat, Image> {
            override fun beforeStart(requestStream: ClientCallStreamObserver<ImageFormat>) {
                streamCall = requestStream
            }

            override fun onNext(value: Image) {
                toBufferedImage(value)?.let(listener::onFrame)
            }

            override fun onError(t: Throwable) {
                if (closed) return
                val status = Status.fromThrowable(t)
                listener.onError("Emulator gRPC stream failed (${status.code}${status.description?.let { ": $it" } ?: ""})", t)
            }

            override fun onCompleted() {
                if (!closed) listener.onStatus("Emulator stream ended")
            }
        })
    }

    override fun tap(x: Int, y: Int) {
        mouse(x, y, 1)
        Thread.sleep(40)
        mouse(x, y, 0)
    }

    override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) {
        mouse(fromX, fromY, 1)
        moveHeld(fromX, fromY, toX, toY, durationMs)
        mouse(toX, toY, 0)
    }

    override fun longPress(x: Int, y: Int, durationMs: Long) {
        mouse(x, y, 1)
        Thread.sleep(durationMs)
        mouse(x, y, 0)
    }

    override fun doubleTap(x: Int, y: Int) {
        tap(x, y)
        Thread.sleep(DOUBLE_TAP_GAP_MS)
        tap(x, y)
    }

    override fun dragWithHold(fromX: Int, fromY: Int, toX: Int, toY: Int, holdMs: Long, durationMs: Long) {
        mouse(fromX, fromY, 1)
        Thread.sleep(holdMs)
        moveHeld(fromX, fromY, toX, toY, durationMs)
        mouse(toX, toY, 0)
    }

    /** Moves the held pointer from one point to the other in small steps over [durationMs]. */
    private fun moveHeld(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) {
        val steps = (durationMs / 16).toInt().coerceIn(5, 120)
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            mouse((fromX + (toX - fromX) * t).toInt(), (fromY + (toY - fromY) * t).toInt(), 1)
            Thread.sleep(durationMs / steps)
        }
    }

    override fun press(key: DeviceKey) {
        // W3C KeyboardEvent.key values, understood by the emulator.
        val name = when (key) {
            DeviceKey.HOME -> "GoHome"
            DeviceKey.BACK -> "GoBack"
            DeviceKey.APP_SWITCH -> "AppSwitch"
            DeviceKey.ENTER -> "Enter"
            DeviceKey.DELETE -> "Backspace"
            DeviceKey.VOLUME_UP -> "AudioVolumeUp"
            DeviceKey.VOLUME_DOWN -> "AudioVolumeDown"
            DeviceKey.POWER -> "Power"
        }
        blocking().sendKey(
            KeyboardEvent.newBuilder()
                .setEventType(KeyboardEvent.KeyEventType.keypress)
                .setKey(name)
                .build()
        )
    }

    override fun typeText(text: String) {
        if (text.isEmpty()) return
        blocking().sendKey(KeyboardEvent.newBuilder().setText(text).build())
    }

    override fun close() {
        closed = true
        streamCall?.cancel("viewer closed", null)
        streamCall = null
        channel.shutdownNow()
    }

    private fun mouse(x: Int, y: Int, buttons: Int) {
        blocking().sendMouse(MouseEvent.newBuilder().setX(x).setY(y).setButtons(buttons).build())
    }

    private fun blocking() = blockingStub.withDeadlineAfter(3, TimeUnit.SECONDS)

    private fun toBufferedImage(value: Image): BufferedImage? {
        val width = value.format.width
        val height = value.format.height
        val bytes = value.image
        if (width <= 0 || height <= 0 || bytes.size() < width * height * 3) return null
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val pixels = (image.raster.dataBuffer as DataBufferInt).data
        val raw = bytes.toByteArray()
        var j = 0
        for (i in 0 until width * height) {
            pixels[i] = (raw[j].toInt() and 0xFF shl 16) or (raw[j + 1].toInt() and 0xFF shl 8) or (raw[j + 2].toInt() and 0xFF)
            j += 3
        }
        return image
    }

    private companion object {
        /** Between the two taps of a double tap; Android's timeout is 300 ms from the first up to the second down. */
        const val DOUBLE_TAP_GAP_MS = 80L
    }

    private class BearerTokenInterceptor(private val token: String) : ClientInterceptor {
        override fun <ReqT, RespT> interceptCall(
            method: MethodDescriptor<ReqT, RespT>,
            callOptions: CallOptions,
            next: Channel,
        ): ClientCall<ReqT, RespT> =
            object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
                override fun start(responseListener: ClientCall.Listener<RespT>, headers: Metadata) {
                    headers.put(AUTHORIZATION, "Bearer $token")
                    super.start(responseListener, headers)
                }
            }

        companion object {
            val AUTHORIZATION: Metadata.Key<String> = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
        }
    }
}
