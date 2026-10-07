package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.discovery.MobilecliDeviceInfoParser
import io.github.marcomaes.mobiletestbench.core.discovery.MobilecliDevicesParser
import io.github.marcomaes.mobiletestbench.core.exec.CommandResult
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import io.github.marcomaes.mobiletestbench.core.inspector.HierarchyQueries
import io.github.marcomaes.mobiletestbench.core.inspector.MobilecliDumpParser
import io.github.marcomaes.mobiletestbench.core.ios.IosMobilecliSession
import io.github.marcomaes.mobiletestbench.core.ios.MobilecliClient
import io.github.marcomaes.mobiletestbench.core.session.FrameListener
import io.github.marcomaes.mobiletestbench.core.session.UnsupportedOperationOnDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

class IosMobilecliSessionTest {

    private fun fixture(name: String) =
        javaClass.getResourceAsStream("/fixtures/$name")!!.readBytes().toString(Charsets.UTF_8)

    private fun image(): BufferedImage = BufferedImage(40, 80, BufferedImage.TYPE_INT_RGB)

    private fun png(): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image(), "png", it) }.toByteArray()

    private fun jpeg(): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image(), "jpg", it) }.toByteArray()

    private class FakeProcess(private val data: ByteArray) : Process() {
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(data)
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() {}
    }

    private inner class FakeRunner(private val stream: ByteArray?) : CommandRunner(emptyMap()) {
        val commands = mutableListOf<List<String>>()
        override fun run(command: List<String>, timeout: Duration): CommandResult {
            synchronized(commands) { commands += command }
            return when {
                command.contains("screenshot") -> CommandResult(0, png(), "")
                command.contains("info") -> CommandResult(0, """{"status":"ok","data":{"device":{"screenSize":{"width":402,"height":874,"scale":3}}}}""".toByteArray(), "")
                command.contains("dump") -> CommandResult(0, fixture("mobilecli_ios_dump.json").toByteArray(), "")
                else -> CommandResult(0, ByteArray(0), "")
            }
        }

        override fun start(command: List<String>): Process {
            synchronized(commands) { commands += command }
            return FakeProcess(stream ?: throw IOException("no stream"))
        }
    }

    private val device = Device("00008110-001A", "iPhone", Platform.IOS, DeviceKind.PHYSICAL)

    private fun session(runner: FakeRunner) = IosMobilecliSession(device, MobilecliClient("mobilecli", runner), pollIntervalMs = 50)

    private fun awaitFrame(session: IosMobilecliSession): Boolean {
        val frame = CountDownLatch(1)
        session.start(object : FrameListener {
            override fun onFrame(image: BufferedImage) = frame.countDown()
            override fun onError(message: String, cause: Throwable?) {}
        })
        return frame.await(5, TimeUnit.SECONDS)
    }

    @Test
    fun `uses the mobilecli mjpeg stream when it works`() {
        val j = jpeg()
        val runner = FakeRunner("Starting video stream--b\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray() + j + "\r\n".toByteArray() + j)
        val session = session(runner)
        assertTrue(awaitFrame(session))
        assertTrue(runner.commands.any { it == listOf("mobilecli", "screencapture", "--device", device.id, "-f", "mjpeg") })
        session.close()
    }

    @Test
    fun `falls back to screenshots when the stream cannot start`() {
        val runner = FakeRunner(null)
        val session = session(runner)
        assertTrue(awaitFrame(session))
        assertEquals("mobilecli screenshots", session.transport)
        assertTrue(runner.commands.any { it.contains("screenshot") && it.takeLast(2) == listOf("-o", "-") })
        session.close()
    }

    @Test
    fun `forwards input and reads screen size and hierarchy through mobilecli`() {
        val runner = FakeRunner(null)
        val session = session(runner)
        session.tap(10, 20)
        session.swipe(1, 2, 3, 4, 250)
        session.press(DeviceKey.HOME)
        session.press(DeviceKey.DELETE)
        session.typeText("-hi")
        val root = session.hierarchy()
        assertEquals(Size(402, 874), session.logicalSize())
        assertTrue(root.walk().any { it.label == "Fitness" })
        val c = runner.commands
        assertTrue(c.contains(listOf("mobilecli", "io", "tap", "--device", device.id, "10,20")))
        assertTrue(c.contains(listOf("mobilecli", "io", "swipe", "--device", device.id, "--duration", "250", "1,2,3,4")))
        assertTrue(c.contains(listOf("mobilecli", "io", "button", "--device", device.id, "HOME")))
        assertTrue(c.contains(listOf("mobilecli", "io", "keys", "--device", device.id, "backspace")))
        assertTrue(c.contains(listOf("mobilecli", "io", "text", "--device", device.id, "--", "-hi")))
        try {
            session.press(DeviceKey.BACK)
            throw AssertionError("expected UnsupportedOperationOnDevice")
        } catch (_: UnsupportedOperationOnDevice) {
        }
        session.close()
    }

    @Test
    fun `parses physical ios devices and skips simulators and android`() {
        val json = """
            {"status":"ok","data":{"devices":[
              {"id":"Pixel_9","name":"Pixel 9","platform":"android","type":"emulator","version":"17","state":"online"},
              {"id":"SIM-1","name":"iPhone 17 Pro","platform":"ios","type":"simulator","version":"26.5","state":"online"},
              {"id":"00008110-001A","name":"Marco's iPhone","platform":"ios","type":"real","version":"18.2","state":"online"}
            ]}}
        """.trimIndent()
        val devices = MobilecliDevicesParser.parse(json)
        assertEquals(1, devices.size)
        assertEquals("00008110-001A", devices[0].id)
        assertEquals(DeviceKind.PHYSICAL, devices[0].kind)
        assertEquals(Platform.IOS, devices[0].platform)
        assertEquals("iOS 18.2", devices[0].osVersion)
        assertTrue(devices[0].isUsable)
    }

    @Test
    fun `parses device info and the ui dump fixture`() {
        val info = """{"status":"ok","data":{"device":{"id":"x","screenSize":{"width":402,"height":874,"scale":3}}}}"""
        assertEquals(Size(402, 874), MobilecliDeviceInfoParser.parseScreenSize(info))
        val root = MobilecliDumpParser.parse(fixture("mobilecli_ios_dump.json"))
        assertEquals("XCUIElementTypeApplication", root.rawType)
        val fitness = root.walk().first { it.label == "Fitness" }
        assertEquals("Icon", fitness.type)
        assertEquals("XCUIElementTypeIcon", fitness.rawType)
        assertEquals(Rect(28, 88, 68, 90), fitness.bounds)
        assertNotNull(HierarchyQueries.deepestAt(root, 40, 100))
    }
}
