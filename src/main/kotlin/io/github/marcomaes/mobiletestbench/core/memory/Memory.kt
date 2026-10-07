package io.github.marcomaes.mobiletestbench.core.memory

import io.github.marcomaes.mobiletestbench.core.exec.CommandFailedException
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import java.time.Duration

/** Levels accepted by `am send-trim-memory` (ComponentCallbacks2.TRIM_MEMORY_*), mildest first. */
enum class TrimLevel(val arg: String, val label: String, val hint: String) {
    HIDDEN("HIDDEN", "Hidden", "UI is hidden (TRIM_MEMORY_UI_HIDDEN)"),
    RUNNING_MODERATE("RUNNING_MODERATE", "Running, moderate", "Running, device is getting low on memory"),
    BACKGROUND("BACKGROUND", "Background", "Process moved to the LRU list"),
    RUNNING_LOW("RUNNING_LOW", "Running, low", "Running, device is low on memory"),
    MODERATE("MODERATE", "Moderate", "Process is in the middle of the LRU list"),
    RUNNING_CRITICAL("RUNNING_CRITICAL", "Running, critical", "Running, device is very low; background processes are being killed"),
    COMPLETE("COMPLETE", "Complete", "Process is first in line to be killed");
}

object MemoryCommands {
    /** Package names and process names (`pkg:service`) only: the value is part of a remote shell command line. */
    private val NAME = Regex("[A-Za-z0-9_.:]+")

    fun validProcess(name: String) = name.isNotEmpty() && name.length <= 256 && NAME.matches(name)

    fun trim(adb: String, serial: String, process: String, level: TrimLevel): List<String> =
        listOf(adb, "-s", serial, "shell", "am", "send-trim-memory", process, level.arg)
}

object MemoryParsers {
    /** The error in the output of `am send-trim-memory` (it exits 0 for an unknown process), or null. */
    fun error(output: String): String? =
        output.lineSequence().map { it.trim() }.firstOrNull {
            it.startsWith("Error", true) || it.startsWith("Unknown", true) || it.startsWith("Unable", true) ||
                it.contains("Exception") || it.contains("not found", true) || it.contains("No process", true) ||
                it.startsWith("Permission", true) || it.contains("Bad", true)
        }?.take(200)

    fun errorLine(text: String, stderr: String, exitCode: Int): String =
        (stderr.lineSequence() + text.lineSequence()).map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "Exit code $exitCode"
}

/** Sends memory trim callbacks (onTrimMemory) to a running app of one Android device. Blocking; call off the EDT. */
class AndroidMemory(private val adb: String, private val serial: String, private val runner: CommandRunner) {

    fun trim(process: String, level: TrimLevel) {
        if (!MemoryCommands.validProcess(process)) throw CommandFailedException("Not a valid package or process name: $process")
        val r = runner.run(MemoryCommands.trim(adb, serial, process, level), TIMEOUT)
        if (!r.ok) throw CommandFailedException(MemoryParsers.errorLine(r.text, r.stderr, r.exitCode))
        MemoryParsers.error(r.text + "\n" + r.stderr)?.let { throw CommandFailedException("$it (is the app running?)") }
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
