package io.github.marcomaes.mobiletestbench.core.ios

import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import java.io.File
import java.time.Duration

class SimctlClient(private val xcrun: String, private val runner: CommandRunner) {

    /** Command line of the simulator log (long running; end it by ending the process). */
    fun logStreamCommand(udid: String): List<String> =
        listOf(xcrun, "simctl", "spawn", udid, "log", "stream", "--style", "compact", "--level", "debug")

    fun listBootedJson(): String =
        runner.runChecked(listOf(xcrun, "simctl", "list", "devices", "booted", "-j")).text

    fun screenshotPng(udid: String): ByteArray {
        val file = File.createTempFile("mobiletestbench-sim-", ".png")
        try {
            runner.runChecked(
                listOf(xcrun, "simctl", "io", udid, "screenshot", "--type=png", file.absolutePath),
                Duration.ofSeconds(10),
            )
            return file.readBytes()
        } finally {
            file.delete()
        }
    }
}
