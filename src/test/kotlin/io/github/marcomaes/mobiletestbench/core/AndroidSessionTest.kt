package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.android.AdbClient
import io.github.marcomaes.mobiletestbench.core.android.AndroidSession
import io.github.marcomaes.mobiletestbench.core.android.EmulatorLink
import io.github.marcomaes.mobiletestbench.core.exec.CommandResult
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import io.github.marcomaes.mobiletestbench.core.ios.MobilecliClient
import io.github.marcomaes.mobiletestbench.core.session.FrameListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class AndroidSessionTest {

    /** Delivers [data], then blocks like a stream that stays open. */
    private class OpenStream(data: ByteArray) : InputStream() {
        private val head = ByteArrayInputStream(data)
        override fun read(): Int = head.read().takeIf { it >= 0 } ?: run { Thread.sleep(Long.MAX_VALUE); -1 }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = head.read(b, off, len)
            if (n >= 0) return n
            Thread.sleep(Long.MAX_VALUE)
            return -1
        }
    }

    private class FakeProcess(private val data: ByteArray, private val open: Boolean = false) : Process() {
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = if (open) OpenStream(data) else ByteArrayInputStream(data)
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() {}
    }

    private class FakeRunner(private val stream: ByteArray? = null, private val open: Boolean = false) : CommandRunner(emptyMap()) {
        @Volatile var wmSize = "Physical size: 1080x2400\n"
        val commands = mutableListOf<List<String>>()
        private val png = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(1080, 2400, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()

        override fun run(command: List<String>, timeout: Duration): CommandResult {
            synchronized(commands) { commands += command }
            return when {
                command.contains("screencap") -> CommandResult(0, png, "")
                command.contains("wm") -> CommandResult(0, wmSize.toByteArray(), "")
                else -> CommandResult(0, ByteArray(0), "")
            }
        }

        override fun start(command: List<String>): Process {
            synchronized(commands) { commands += command }
            return FakeProcess(stream ?: throw IOException("no stream"), open)
        }
    }

    private class FailingLink : EmulatorLink {
        var closed = false
        override fun startStreaming(listener: FrameListener) = listener.onError("UNAUTHENTICATED", null)
        override fun tap(x: Int, y: Int) = error("should not be used")
        override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) = error("should not be used")
        override fun longPress(x: Int, y: Int, durationMs: Long) = error("should not be used")
        override fun doubleTap(x: Int, y: Int) = error("should not be used")
        override fun dragWithHold(fromX: Int, fromY: Int, toX: Int, toY: Int, holdMs: Long, durationMs: Long) = error("should not be used")
        override fun press(key: DeviceKey) = error("should not be used")
        override fun typeText(text: String) = error("should not be used")
        override fun close() { closed = true }
    }

    @Test
    fun `falls back to adb when the grpc link fails before the first frame`() {
        val runner = FakeRunner()
        val link = FailingLink()
        val device = Device("emulator-5554", "Pixel", Platform.ANDROID, DeviceKind.EMULATOR)
        val session = AndroidSession(device, AdbClient("adb", runner), link, pollIntervalMs = 50)
        val frame = CountDownLatch(1)
        session.start(object : FrameListener {
            override fun onFrame(image: BufferedImage) = frame.countDown()
            override fun onError(message: String, cause: Throwable?) {}
        })
        assertTrue("expected an adb frame", frame.await(5, TimeUnit.SECONDS))
        assertEquals("adb screenshots", session.transport)
        assertTrue(link.closed)
        assertEquals(Size(1080, 2400), session.logicalSize())

        session.tap(10, 20)
        // The screenshot polling thread is still adding commands: read the list under the lock it is written under.
        assertTrue(synchronized(runner.commands) { runner.commands.any { it.takeLast(4) == listOf("input", "tap", "10", "20") } })
        session.close()
    }

    private val phone = Device("R3GL701H6WK", "SM-F976B", Platform.ANDROID, DeviceKind.PHYSICAL)

    private fun awaitFrame(session: AndroidSession): Boolean {
        val frame = CountDownLatch(1)
        session.start(object : FrameListener {
            override fun onFrame(image: BufferedImage) = frame.countDown()
            override fun onError(message: String, cause: Throwable?) {}
        })
        return frame.await(5, TimeUnit.SECONDS)
    }

    @Test
    fun `a phone without grpc streams through mobilecli mjpeg`() {
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(540, 1260, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()
        val runner = FakeRunner("Starting video stream--b\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray() + jpeg + "\r\n".toByteArray() + jpeg)
        val session = AndroidSession(phone, AdbClient("adb", runner), null, pollIntervalMs = 50, mobilecli = MobilecliClient("mobilecli", runner))
        assertTrue(awaitFrame(session))
        assertTrue(synchronized(runner.commands) { runner.commands.any { it == listOf("mobilecli", "screencapture", "--device", phone.id, "-f", "mjpeg") } })
        session.close()
    }

    @Test
    fun `a failing mobilecli stream falls back to adb screenshots`() {
        val runner = FakeRunner()
        val session = AndroidSession(phone, AdbClient("adb", runner), null, pollIntervalMs = 50, mobilecli = MobilecliClient("mobilecli", runner))
        assertTrue(awaitFrame(session))
        assertEquals("adb screenshots", session.transport)
        assertTrue(session.transportInfo().fallbackReason!!.contains("mobilecli MJPEG stream unavailable"))
        session.close()
    }

    @Test
    fun `folding the phone restarts the mobilecli stream`() {
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(540, 1260, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()
        val data = "Starting video stream--b\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray() + jpeg
        val runner = FakeRunner(data, open = true)
        val session = AndroidSession(phone, AdbClient("adb", runner), null, pollIntervalMs = 50, mobilecli = MobilecliClient("mobilecli", runner), displayWatchMs = 50)
        val statuses = mutableListOf<String>()
        val frame = CountDownLatch(1)
        session.start(object : FrameListener {
            override fun onFrame(image: BufferedImage) = frame.countDown()
            override fun onStatus(message: String) { synchronized(statuses) { statuses += message } }
            override fun onError(message: String, cause: Throwable?) {}
        })
        assertTrue(frame.await(5, TimeUnit.SECONDS))
        runner.wmSize = "Physical size: 2256x2504\n"
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && synchronized(statuses) { statuses.none { it.startsWith("Display changed to 2256x2504") } }) Thread.sleep(20)
        assertTrue(synchronized(statuses) { statuses.any { it.startsWith("Display changed to 2256x2504") } })
        assertEquals(2, synchronized(runner.commands) { runner.commands.count { it.contains("screencapture") } })
        session.close()
    }
}
