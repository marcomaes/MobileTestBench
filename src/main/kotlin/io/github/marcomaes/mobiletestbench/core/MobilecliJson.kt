package io.github.marcomaes.mobiletestbench.core

import com.google.gson.JsonElement
import com.google.gson.JsonParser

/** A mobilecli failure; the message is mobilecli's own error text. */
class MobilecliException(message: String) : RuntimeException(message)

/** Pure helpers for mobilecli calls: building the command line and reading the `{"status":..,"data":..}` envelope. */
object MobilecliJson {

    /** `mobilecli <args...> --device <id>`. */
    fun commandLine(executable: String, id: String, args: List<String>): List<String> =
        listOf(executable) + args + listOf("--device", id)

    /** The envelope as a JSON object, or null when [stdout] is not one. */
    private fun envelope(stdout: String) =
        runCatching { JsonParser.parseString(stdout.trim()) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject

    /** The text of mobilecli's error: the envelope's `error`, else stderr, else stdout, else [fallback]. */
    fun errorMessage(stdout: String, stderr: String, fallback: String = "mobilecli failed"): String {
        val fromJson = envelope(stdout)?.get("error")?.takeIf { it.isJsonPrimitive }?.asString
        return listOf(fromJson, stderr.trim(), stdout.trim(), fallback).first { !it.isNullOrBlank() }!!.trim()
    }

    /** True when stdout is an envelope with `"status":"error"`. */
    fun isError(stdout: String): Boolean = envelope(stdout)?.get("status")?.asString == "error"

    /** The `data` member of a successful envelope (null when absent or when stdout is not an envelope). Throws [MobilecliException] on an error envelope. */
    fun data(stdout: String): JsonElement? {
        if (isError(stdout)) throw MobilecliException(errorMessage(stdout, ""))
        return envelope(stdout)?.get("data")?.takeIf { !it.isJsonNull }
    }
}
