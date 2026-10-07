package io.github.marcomaes.mobiletestbench.core.recording

import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import java.io.File
import java.util.concurrent.TimeUnit

/** A running screen recording: a `mobilecli screenrecord` process writing an MP4 file until it is stopped. */
class ScreenRecording private constructor(private val process: Process, val output: File, private val errors: StringBuilder) {

    val isAlive: Boolean get() = process.isAlive

    /** Ends the recording and waits for the MP4 to be finalized. */
    fun stop(timeoutMs: Long = 10_000): Result<File> {
        process.destroy()
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(2_000, TimeUnit.MILLISECONDS)
        }
        return if (output.isFile && output.length() > 0) {
            Result.success(output)
        } else {
            Result.failure(IllegalStateException(failureText("The recording file is empty")))
        }
    }

    private fun failureText(fallback: String) = synchronized(errors) { errors.toString().trim() }.ifEmpty { fallback }

    companion object {
        /**
         * Starts [command] (see `MobilecliClient.screenRecordCommand`). Fails with mobilecli's message when the process
         * ends within [startupMs], e.g. for an unknown device.
         */
        fun start(runner: CommandRunner, command: List<String>, output: File, startupMs: Long = 1_000): ScreenRecording {
            val process = runner.start(command)
            val errors = StringBuilder()
            drain(process.inputStream, errors)
            drain(process.errorStream, errors)
            if (process.waitFor(startupMs, TimeUnit.MILLISECONDS)) {
                Thread.sleep(50) // let the drain threads collect the last output
                val text = synchronized(errors) { errors.toString().trim() }
                throw IllegalStateException(text.ifEmpty { "mobilecli stopped with exit code ${process.exitValue()}" })
            }
            return ScreenRecording(process, output, errors)
        }

        /** Reads a stream so the process never blocks on a full pipe, keeping the first few KB for error messages. */
        private fun drain(stream: java.io.InputStream, into: StringBuilder) {
            Thread {
                runCatching {
                    stream.bufferedReader().forEachLine { line ->
                        synchronized(into) { if (into.length < 4096) into.append(line).append('\n') }
                    }
                }
            }.apply {
                isDaemon = true
                name = "MobileTestBench-screenrecord-output"
                start()
            }
        }
    }
}
