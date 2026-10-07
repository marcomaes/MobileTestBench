package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.exec.CommandResult
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import io.github.marcomaes.mobiletestbench.core.ios.MobilecliClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class MobilecliJsonTest {

    private class FakeRunner(val exit: Int, val out: String, val err: String = "") : CommandRunner(emptyMap()) {
        var last: List<String>? = null
        override fun run(command: List<String>, timeout: Duration): CommandResult {
            last = command
            return CommandResult(exit, out.toByteArray(), err)
        }
    }

    @Test
    fun `command line puts the device last`() {
        assertEquals(listOf("mobilecli", "apps", "list", "--device", "Pixel_9"), MobilecliJson.commandLine("mobilecli", "Pixel_9", listOf("apps", "list")))
    }

    @Test
    fun `data of an ok envelope`() {
        val data = MobilecliJson.data("""{"status":"ok","data":{"a":[1,2]}}""")!!
        assertEquals(2, data.asJsonObject.getAsJsonArray("a").size())
        assertNull(MobilecliJson.data("""{"status":"ok"}"""))
        assertNull(MobilecliJson.data("not json"))
    }

    @Test
    fun `error envelope gives mobilecli's text`() {
        assertEquals("device not found", MobilecliJson.errorMessage("""{"status":"error","error":"device not found"}""", ""))
        assertEquals("boom", MobilecliJson.errorMessage("", "boom\n"))
        assertEquals("mobilecli failed", MobilecliJson.errorMessage("", ""))
        val e = runCatching { MobilecliJson.data("""{"status":"error","error":"nope"}""") }.exceptionOrNull()
        assertTrue(e is MobilecliException)
        assertEquals("nope", e!!.message)
    }

    @Test
    fun `call returns stdout and builds the command`() {
        val runner = FakeRunner(0, """{"status":"ok","data":[]}""")
        val out = MobilecliClient("/bin/mobilecli", runner).call("X", listOf("apps", "list"))
        assertEquals("""{"status":"ok","data":[]}""", out)
        assertEquals(listOf("/bin/mobilecli", "apps", "list", "--device", "X"), runner.last)
    }

    @Test
    fun `call fails with the json error, stderr, or error envelope on exit 0`() {
        fun msg(r: FakeRunner) = runCatching { MobilecliClient("m", r).call("X", listOf("a")) }.exceptionOrNull()!!.message
        assertEquals("bad", msg(FakeRunner(1, """{"status":"error","error":"bad"}""")))
        assertEquals("stderr text", msg(FakeRunner(1, "", "stderr text")))
        assertEquals("soft", msg(FakeRunner(0, """{"status":"error","error":"soft"}""")))
    }
}
