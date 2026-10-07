package io.github.marcomaes.mobiletestbench.core.logs

import com.google.gson.JsonParser

/**
 * Parses one line of `mobilecli device logs` (one JSON object per line). Used only where adb and simctl cannot serve a log
 * (a physical iPhone). Its own `--filter` is exact equality and is never used; all filtering stays in [LogFilter].
 * Android lines carry `tag`; iOS lines carry `process` (and optional `subsystem`/`category`). A multi line message is one entry.
 */
object MobilecliLogParser {
    private val time = Regex("""^\d{4}-(\d\d-\d\d \d\d:\d\d:\d\d(?:\.\d{1,3})?)\d*.*$""")

    fun parse(line: String): LogEntry? {
        if (!line.startsWith("{")) return null
        val o = try {
            JsonParser.parseString(line).takeIf { it.isJsonObject }?.asJsonObject ?: return null
        } catch (_: Exception) {
            return null
        }
        fun str(key: String) = o.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (!o.has("message")) return null
        val stamp = str("timestamp")
        val t = time.matchEntire(stamp)?.groupValues?.get(1) ?: stamp
        val tag = str("tag").ifEmpty { str("process") }
        return LogEntry(t, level(str("level")), tag, str("pid"), str("message"))
    }

    // Android: Verbose Debug Info Warning Error (Fatal/Assert not seen); iOS: Default Info Error Fault and "" (no metadata).
    private fun level(word: String) = when (word.lowercase()) {
        "verbose" -> LogLevel.VERBOSE
        "debug" -> LogLevel.DEBUG
        "warning", "warn" -> LogLevel.WARN
        "error" -> LogLevel.ERROR
        "fatal", "fault", "assert" -> LogLevel.FATAL
        else -> LogLevel.INFO // Info, Default, ""
    }
}
