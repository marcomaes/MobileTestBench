package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.android.AdbOutput
import io.github.marcomaes.mobiletestbench.core.android.EmulatorDiscovery
import io.github.marcomaes.mobiletestbench.core.discovery.AdbDevicesParser
import io.github.marcomaes.mobiletestbench.core.discovery.SimctlDevicesParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class ParsersTest {

    @Test
    fun `parses adb devices -l`() {
        val output = """
            List of devices attached
            emulator-5554          device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a transport_id:1
            R58M123ABC             unauthorized usb:1-1 transport_id:2

        """.trimIndent()
        val devices = AdbDevicesParser.parse(output)
        assertEquals(2, devices.size)
        assertEquals("emulator-5554", devices[0].id)
        assertEquals(DeviceKind.EMULATOR, devices[0].kind)
        assertEquals("sdk gphone64 arm64", devices[0].name)
        assertEquals(true, devices[0].isUsable)
        assertEquals(DeviceKind.PHYSICAL, devices[1].kind)
        assertEquals(false, devices[1].isUsable)
    }

    @Test
    fun `parses simctl booted json`() {
        val json = """
            {"devices":{
              "com.apple.CoreSimulator.SimRuntime.iOS-17-5":[
                {"udid":"AAAA-1111","name":"iPhone 15","state":"Booted","isAvailable":true},
                {"udid":"BBBB-2222","name":"iPhone SE","state":"Shutdown","isAvailable":true}
              ],
              "com.apple.CoreSimulator.SimRuntime.iOS-26-0":[]
            }}
        """.trimIndent()
        val devices = SimctlDevicesParser.parse(json)
        assertEquals(1, devices.size)
        assertEquals("AAAA-1111", devices[0].id)
        assertEquals("iOS 17.5", devices[0].osVersion)
        assertEquals(Platform.IOS, devices[0].platform)
    }

    @Test
    fun `wm size prefers override`() {
        assertEquals(Size(1080, 2400), AdbOutput.parseWmSize("Physical size: 1080x2400\n"))
        assertEquals(Size(720, 1600), AdbOutput.parseWmSize("Physical size: 1080x2400\nOverride size: 720x1600\n"))
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
        val warning = "[Warning] Multiple displays were found, but no display id was specified.\n".toByteArray()
        assertEquals(png.toList(), AdbOutput.pngFrom(warning + png).toList())
        assertEquals(png.toList(), AdbOutput.pngFrom(png).toList())
        assertNull(AdbOutput.parseWmSize("error: device offline"))
    }

    @Test
    fun `escapes adb input text`() {
        assertEquals("hello%sworld", AdbOutput.escapeInputText("hello world"))
        assertEquals("a\\&b\\'c", AdbOutput.escapeInputText("a&b'c"))
        assertEquals("user@example.com", AdbOutput.escapeInputText("user@example.com"))
    }

    @Test
    fun `finds emulator grpc endpoint from discovery file`() {
        val dir = Files.createTempDirectory("avd-running").toFile()
        try {
            dir.resolve("pid_1234.ini").writeText(
                """
                port.serial=5556
                port.adb=5557
                avd.name=Pixel_8_API_35
                grpc.port=8556
                grpc.token=secret-token
                """.trimIndent()
            )
            dir.resolve("pid_999.ini").writeText("port.serial=5554\ngrpc.port=8554\n")

            val endpoint = EmulatorDiscovery.findEndpoint("emulator-5556", listOf(dir))!!
            assertEquals(8556, endpoint.port)
            assertEquals("secret-token", endpoint.token)

            val noToken = EmulatorDiscovery.findEndpoint("emulator-5554", listOf(dir))!!
            assertEquals(8554, noToken.port)
            assertNull(noToken.token)

            assertNull(EmulatorDiscovery.findEndpoint("emulator-5560", listOf(dir)))
            assertNull(EmulatorDiscovery.findEndpoint("R58M123ABC", listOf(dir)))
        } finally {
            dir.deleteRecursively()
        }
    }
}
