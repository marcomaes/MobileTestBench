package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.android.AdbClient
import io.github.marcomaes.mobiletestbench.core.android.AndroidSession
import io.github.marcomaes.mobiletestbench.core.exec.CommandResult
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import io.github.marcomaes.mobiletestbench.core.ios.MobilecliClient
import io.github.marcomaes.mobiletestbench.core.session.UnsupportedOperationOnDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Duration

/** The command lines behind Fill's select-all + backspace, and mobilecli's real answers (scouted fixtures). */
class DeviceKeysTransportTest {

    private class Runner(val exit: Int = 0, val out: String = "", val err: String = "") : CommandRunner(emptyMap()) {
        val commands = mutableListOf<List<String>>()
        override fun run(command: List<String>, timeout: Duration): CommandResult {
            commands += command
            return CommandResult(exit, out.toByteArray(), err)
        }
    }

    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()
    private val device = Device("emulator-5554", "Pixel 9", Platform.ANDROID, DeviceKind.EMULATOR)

    @Test
    fun `mobilecli keys passes every combo as its own argument`() {
        val runner = Runner(out = fixture("mobilecli-keys-ok.json"))
        MobilecliClient("mobilecli", runner).keys("Pixel_9", listOf("cmd+a", "backspace"))
        assertEquals(listOf("mobilecli", "io", "keys", "--device", "Pixel_9", "cmd+a", "backspace"), runner.commands.single())
    }

    @Test
    fun `a bad key surfaces mobilecli's own error text`() {
        for ((file, text) in listOf("mobilecli-keys-error-android.json" to "unsupported key", "mobilecli-keys-error-ios.json" to "Unsupported key")) {
            try {
                MobilecliClient("mobilecli", Runner(exit = 1, out = fixture(file))).keys("x", listOf("foo"))
                fail("expected an error for $file")
            } catch (e: MobilecliException) {
                assertTrue(e.message, e.message!!.contains(text))
            }
        }
    }

    @Test
    fun `adb key combination uses one keycombination call`() {
        val runner = Runner()
        AdbClient("adb", runner).keyCombination("emulator-5554", 113, 29)
        assertEquals(listOf("adb", "-s", "emulator-5554", "shell", "input", "keycombination", "113", "29"), runner.commands.single())
    }

    @Test
    fun `android without mobilecli maps ctrl+a and backspace to adb`() {
        val runner = Runner()
        AndroidSession(device, AdbClient("adb", runner), null).pressKeys(listOf("ctrl+a", "backspace"))
        assertEquals(
            listOf(
                listOf("adb", "-s", "emulator-5554", "shell", "input", "keycombination", "113", "29"),
                listOf("adb", "-s", "emulator-5554", "shell", "input", "keyevent", "67"),
            ),
            runner.commands,
        )
    }

    @Test
    fun `android without mobilecli rejects other keys`() {
        val runner = Runner()
        try {
            AndroidSession(device, AdbClient("adb", runner), null).pressKeys(listOf("cmd+a"))
            fail("expected UnsupportedOperationOnDevice")
        } catch (e: UnsupportedOperationOnDevice) {
            assertTrue(e.message!!.contains("needs mobilecli"))
        }
        assertTrue(runner.commands.isEmpty())
    }

    @Test
    fun `android with mobilecli sends the keys through the avd name`() {
        val runner = object : CommandRunner(emptyMap()) {
            val commands = mutableListOf<List<String>>()
            override fun run(command: List<String>, timeout: Duration): CommandResult {
                commands += command
                return if (command.contains("emu")) CommandResult(0, "Pixel_9\r\nOK\r\n".toByteArray(), "") else CommandResult(0, "{\"status\":\"ok\"}".toByteArray(), "")
            }
        }
        AndroidSession(device, AdbClient("adb", runner), null, mobilecli = MobilecliClient("mobilecli", runner)).pressKeys(listOf("ctrl+a", "backspace"))
        assertEquals(listOf("mobilecli", "io", "keys", "--device", "Pixel_9", "ctrl+a", "backspace"), runner.commands.last())
    }
}
