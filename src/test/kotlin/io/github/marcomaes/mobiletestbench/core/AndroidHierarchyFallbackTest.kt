package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.android.AdbClient
import io.github.marcomaes.mobiletestbench.core.android.AndroidSession
import io.github.marcomaes.mobiletestbench.core.exec.CommandResult
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import io.github.marcomaes.mobiletestbench.core.inspector.MobilecliAndroidRawParser
import io.github.marcomaes.mobiletestbench.core.ios.MobilecliClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class AndroidHierarchyFallbackTest {

    private val fixture = javaClass.getResource("/fixtures/mobilecli_android_raw_dump.json")!!.readText()
    private val device = Device("emulator-5554", "Pixel 9", Platform.ANDROID, DeviceKind.EMULATOR)

    /** uiautomator is killed (exit 137) like on the Android 17 emulator; mobilecli returns the fixture for the AVD name (or fails). */
    private class Runner(private val dump: String, private val mobilecliWorks: Boolean = true) : CommandRunner(emptyMap()) {
        val commands = mutableListOf<List<String>>()

        override fun run(command: List<String>, timeout: Duration): CommandResult = record(command).let { when {
            command.contains("uiautomator") -> CommandResult(137, "Killed".toByteArray(), "")
            command.contains("emu") -> CommandResult(0, "Pixel_9\r\nOK\r\n".toByteArray(), "")
            // mobilecli only knows the emulator by its AVD name.
            command.contains("mobilecli") && command.contains("raw") ->
                if (mobilecliWorks && command.contains("Pixel_9")) CommandResult(0, dump.toByteArray(), "") else CommandResult(1, ByteArray(0), "device not found")
            else -> CommandResult(0, ByteArray(0), "")
        } }

        private fun record(command: List<String>) { synchronized(commands) { commands += command } }
    }

    @Test
    fun `parses the mobilecli android raw dump`() {
        val root = MobilecliAndroidRawParser.parse(fixture)
        val cart = root.walk().first { it.resourceId == "cart-button" }
        assertEquals("Button", cart.type)
        assertEquals("Cart", cart.label)
        assertNotNull(cart.bounds)
        assertTrue(root.walk().any { it.text == "Products" })
    }

    @Test
    fun `uses mobilecli first, without touching uiautomator`() {
        val runner = Runner(fixture)
        val session = AndroidSession(device, AdbClient("adb", runner), null, mobilecli = MobilecliClient("mobilecli", runner))
        assertTrue(session.hierarchy().walk().any { it.resourceId == "cart-button" })
        assertTrue(runner.commands.none { it.contains("uiautomator") })
    }

    @Test
    fun `falls back to uiautomator when mobilecli fails`() {
        val runner = Runner(fixture, mobilecliWorks = false)
        val session = AndroidSession(device, AdbClient("adb", runner), null, mobilecli = MobilecliClient("mobilecli", runner))
        val e = runCatching { session.hierarchy() }.exceptionOrNull()
        assertTrue(e is IllegalStateException && e.message!!.contains("uiautomator dump failed"))
    }

    @Test(expected = IllegalStateException::class)
    fun `without mobilecli the uiautomator failure surfaces`() {
        AndroidSession(device, AdbClient("adb", Runner(fixture)), null).hierarchy()
    }
}
