package io.github.marcomaes.mobiletestbench.core.exec

import java.io.IOException
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CommandResult(val exitCode: Int, val stdout: ByteArray, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
    val text: String get() = String(stdout, Charsets.UTF_8)
}

class CommandFailedException(message: String) : IOException(message)

/**
 * Small process runner. The environment is injected so the IDE can pass its
 * login shell environment (apps started from the macOS Dock have a minimal PATH).
 */
open class CommandRunner(private val environment: Map<String, String> = System.getenv()) {

    private val pumps = Executors.newCachedThreadPool { r ->
        Thread(r, "MobileTestBench-process-io").apply { isDaemon = true }
    }

    open fun run(command: List<String>, timeout: Duration = Duration.ofSeconds(20)): CommandResult {
        val process = start(command)
        val out = pumps.submit<ByteArray> { process.inputStream.readBytes() }
        val err = pumps.submit<ByteArray> { process.errorStream.readBytes() }
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw CommandFailedException("Timed out after ${timeout.seconds}s: ${command.joinToString(" ")}")
        }
        return CommandResult(
            process.exitValue(),
            out.get(5, TimeUnit.SECONDS),
            String(err.get(5, TimeUnit.SECONDS), Charsets.UTF_8),
        )
    }

    /** Runs the command and throws when it exits with a non zero code. */
    fun runChecked(command: List<String>, timeout: Duration = Duration.ofSeconds(20)): CommandResult {
        val result = run(command, timeout)
        if (!result.ok) {
            throw CommandFailedException(
                "Exit code ${result.exitCode}: ${command.joinToString(" ")}\n${result.stderr.trim()}"
            )
        }
        return result
    }

    open fun start(command: List<String>): Process {
        val builder = ProcessBuilder(command)
        builder.environment().putAll(environment)
        return builder.start()
    }
}
