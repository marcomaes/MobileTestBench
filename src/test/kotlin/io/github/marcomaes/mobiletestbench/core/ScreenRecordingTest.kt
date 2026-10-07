package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.android.AdbClient
import io.github.marcomaes.mobiletestbench.core.exec.CommandResult
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import io.github.marcomaes.mobiletestbench.core.ios.MobilecliClient
import io.github.marcomaes.mobiletestbench.core.recording.ScreenRecording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Duration

class ScreenRecordingTest {

    private class ShellRunner(private val script: String) : CommandRunner(emptyMap()) {
        override fun start(command: List<String>): Process = ProcessBuilder("sh", "-c", script).start()
    }

    @Test
    fun `the command records to the given file without progress output`() {
        val client = MobilecliClient("/bin/mobilecli", CommandRunner(emptyMap()))
        assertEquals(
            listOf("/bin/mobilecli", "screenrecord", "--device", "Pixel_9", "-o", "/tmp/a.mp4", "--silent"),
            client.screenRecordCommand("Pixel_9", "/tmp/a.mp4"),
        )
    }

    @Test
    fun `stopping a running recording returns the finished file`() {
        assumeTrue(File("/bin/sh").exists())
        val out = File.createTempFile("rec", ".mp4")
        try {
            // Writes the "file" at once and then keeps running, like mobilecli does.
            val recording = ScreenRecording.start(ShellRunner("echo mp4 > '${out.path}'; exec sleep 30"), listOf("x"), out, startupMs = 200)
            assertTrue(recording.isAlive)
            val result = recording.stop(timeoutMs = 5000)
            assertTrue(result.isSuccess)
            assertFalse(recording.isAlive)
        } finally {
            out.delete()
        }
    }

    @Test
    fun `a recording that never wrote a file fails when stopped`() {
        assumeTrue(File("/bin/sh").exists())
        val out = File.createTempFile("rec", ".mp4").also { it.delete() }
        val recording = ScreenRecording.start(ShellRunner("exec sleep 30"), listOf("x"), out, startupMs = 200)
        assertTrue(recording.stop(timeoutMs = 5000).isFailure)
    }

    @Test
    fun `a process that ends at once reports mobilecli's message`() {
        assumeTrue(File("/bin/sh").exists())
        val e = runCatching {
            ScreenRecording.start(ShellRunner("echo 'device not found: X' >&2; exit 1"), listOf("x"), File("/tmp/none.mp4"), startupMs = 3000)
        }.exceptionOrNull()
        assertTrue(e is IllegalStateException && e.message!!.contains("device not found: X"))
    }

    @Test
    fun `an emulator is addressed by its AVD name, other devices by their id`() {
        val runner = object : CommandRunner(emptyMap()) {
            override fun run(command: List<String>, timeout: Duration) = CommandResult(0, "Pixel_9\r\nOK\r\n".toByteArray(), "")
        }
        val adb = AdbClient("adb", runner)
        assertEquals("Pixel_9", MobilecliClient.idFor(Device("emulator-5554", "Pixel 9", Platform.ANDROID, DeviceKind.EMULATOR), adb))
        assertEquals("R5CT123", MobilecliClient.idFor(Device("R5CT123", "Galaxy", Platform.ANDROID, DeviceKind.PHYSICAL), adb))
        assertEquals("F236-UDID", MobilecliClient.idFor(Device("F236-UDID", "iPhone", Platform.IOS, DeviceKind.SIMULATOR), adb))
    }
}
