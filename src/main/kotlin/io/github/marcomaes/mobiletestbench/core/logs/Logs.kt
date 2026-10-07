package io.github.marcomaes.mobiletestbench.core.logs

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

enum class LogLevel(val label: String) {
    VERBOSE("Verbose"), DEBUG("Debug"), INFO("Info"), WARN("Warn"), ERROR("Error"), FATAL("Fatal");

    override fun toString() = label
}

/** One log line. [tag] is the logcat tag on Android and the process name on iOS. */
class LogEntry(
    val time: String,
    val level: LogLevel,
    val tag: String,
    val pid: String,
    var message: String,
) {
    override fun toString() = "$time ${level.name.first()} $tag[$pid]: $message"
}

object AndroidLogParser {
    // 10-04 11:03:54.483  2683  2686 I artd    : message   (logcat -v threadtime)
    private val line = Regex("""^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+(\d+)\s+\d+\s+([VDIWEFA])\s+(.*?)\s*: ?(.*)$""", RegexOption.DOT_MATCHES_ALL)

    fun parse(text: String): LogEntry? {
        val m = line.matchEntire(text) ?: return null
        val level = when (m.groupValues[3]) {
            "V" -> LogLevel.VERBOSE
            "D" -> LogLevel.DEBUG
            "I" -> LogLevel.INFO
            "W" -> LogLevel.WARN
            "E" -> LogLevel.ERROR
            else -> LogLevel.FATAL
        }
        return LogEntry(m.groupValues[1], level, m.groupValues[4], m.groupValues[2], m.groupValues[5])
    }
}

object IosLogParser {
    // 2026-10-04 11:04:04.085 Df mediaanalysisd[6729:1cb721] message   (log stream --style compact)
    private val line = Regex("""^\d{4}-(\d\d-\d\d) (\d\d:\d\d:\d\d\.\d+) (\w{1,2})\s+(.+?)\[(\d+):[0-9a-fA-F]+] ?(.*)$""", RegexOption.DOT_MATCHES_ALL)

    fun parse(text: String): LogEntry? {
        val m = line.matchEntire(text) ?: return null
        val level = when (m.groupValues[3]) {
            "Db" -> LogLevel.DEBUG
            "E" -> LogLevel.ERROR
            "F" -> LogLevel.FATAL
            else -> LogLevel.INFO // Df (default), I (info), A (activity)
        }
        return LogEntry("${m.groupValues[1]} ${m.groupValues[2]}", level, m.groupValues[4], m.groupValues[5], m.groupValues[6])
    }
}

/** What the log viewer shows. */
data class LogFilter(
    val minLevel: LogLevel = LogLevel.INFO,
    /** Case insensitive text in the message (a regular expression when [regex] is on). */
    val text: String = "",
    /** Case insensitive text in the tag (Android) or process (iOS) (a regular expression when [regex] is on). */
    val tag: String = "",
    /** The app by package or process name. Blank: every app. */
    val app: String = "",
    /** The process ids of [app] on Android (from `pidof`); null when they are not looked up, e.g. on iOS. */
    val appPids: Set<String>? = null,
    /** [text] and [tag] are regular expressions (case insensitive, found anywhere in the line). [app] stays plain text. */
    val regex: Boolean = false,
    /** iOS: a bundle id in [app] also matches the process name by its last part (`com.example.MyApp` matches `MyApp`). Off elsewhere. */
    val bundleIdLastPart: Boolean = false,
) {
    private class Compiled(val pattern: Pattern?, val error: String?)

    // Compiled once per filter, not per entry. An invalid pattern has no Pattern and is ignored until it is fixed.
    private val compiledText by lazy { compile(text) }
    private val compiledTag by lazy { compile(tag) }

    private fun compile(source: String): Compiled {
        val trimmed = source.trim()
        if (!regex || trimmed.isEmpty()) return Compiled(null, null)
        return try {
            Compiled(Pattern.compile(trimmed, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE), null)
        } catch (e: PatternSyntaxException) {
            Compiled(null, e.description + if (e.index >= 0) " at index ${e.index}" else "")
        }
    }

    /** Why the message pattern is invalid (null when it is fine, blank or regular expressions are off). */
    val textError: String? get() = compiledText.error

    /** Why the tag pattern is invalid (null when it is fine, blank or regular expressions are off). */
    val tagError: String? get() = compiledTag.error

    private fun textMatches(needle: String, compiled: Compiled, haystack: String): Boolean {
        if (needle.isBlank()) return true
        if (!regex) return haystack.contains(needle.trim(), ignoreCase = true)
        val pattern = compiled.pattern ?: return true // invalid: ignored
        return pattern.matcher(haystack.take(MAX_REGEX_INPUT)).find()
    }

    fun matches(entry: LogEntry): Boolean {
        if (entry.level < minLevel) return false
        if (!textMatches(tag, compiledTag, entry.tag)) return false
        if (!textMatches(text, compiledText, entry.message)) return false
        if (app.isNotBlank()) {
            if (appPids != null) {
                if (entry.pid !in appPids) return false
            } else if (!appMatchesName(entry.tag)) {
                return false
            }
        }
        return true
    }

    /**
     * By name (iOS): the process name contains [app]; a bundle id such as `com.example.MyApp` also matches by its last part
     * (`MyApp`), because the log has the process name, not the bundle id.
     */
    private fun appMatchesName(name: String): Boolean {
        val wanted = app.trim()
        if (name.contains(wanted, ignoreCase = true)) return true
        val last = wanted.substringAfterLast('.')
        return bundleIdLastPart && wanted.contains('.') && last.isNotEmpty() && name.contains(last, ignoreCase = true)
    }

    /** The parts of [line] that the message filter matches (to highlight); empty without a message filter or with an invalid pattern. */
    fun matchRanges(line: String): List<IntRange> {
        if (text.isBlank()) return emptyList()
        val ranges = ArrayList<IntRange>()
        if (regex) {
            val pattern = compiledText.pattern ?: return emptyList()
            val m = pattern.matcher(line.take(MAX_REGEX_INPUT))
            while (ranges.size < MAX_RANGES && m.find()) {
                if (m.end() > m.start()) ranges += m.start() until m.end()
            }
        } else {
            val needle = text.trim()
            var from = 0
            while (ranges.size < MAX_RANGES) {
                val i = line.indexOf(needle, from, ignoreCase = true)
                if (i < 0) break
                ranges += i until i + needle.length
                from = i + needle.length
            }
        }
        return ranges
    }

    companion object {
        /** Regular expressions only look at this much of a message: a cap against slow patterns on huge lines. */
        private const val MAX_REGEX_INPUT = 20_000
        private const val MAX_RANGES = 100
    }
}

/**
 * Reads a device log (a long running process such as `adb logcat`) and hands out entries. A line that is not
 * the start of an entry continues the previous one (multi line messages, stack traces).
 */
class LogStream private constructor(private val process: Process) : AutoCloseable {

    /**
     * Ends the process and everything it started: `mobilecli` is a wrapper around a native child that would otherwise keep running
     * (and keep the device side log process alive). Anything still alive after about two seconds is killed.
     */
    override fun close() {
        val children = runCatching { process.descendants().toList() }.getOrDefault(emptyList())
        children.forEach { it.destroy() }
        process.destroy()
        if (process.isAlive || children.any { it.isAlive }) {
            Thread {
                runCatching { process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }
                val deadline = System.currentTimeMillis() + 500
                while (children.any { it.isAlive } && System.currentTimeMillis() < deadline) Thread.sleep(50)
                children.forEach { if (it.isAlive) it.destroyForcibly() }
                if (process.isAlive) process.destroyForcibly()
            }.apply { isDaemon = true; name = "MobileTestBench-log-close"; start() }
        }
    }

    companion object {
        /**
         * Runs [launch] (it returns the process to read) and reads it on a background thread. [parse] turns a line into an entry or null;
         * [onEntry] and [onEnd] are called from that thread.
         */
        fun start(
            launch: () -> Process,
            parse: (String) -> LogEntry?,
            onEntry: (LogEntry) -> Unit,
            onEnd: (String?) -> Unit,
        ): LogStream {
            val process = launch()
            val stream = LogStream(process)
            val errors = StringBuilder()
            Thread {
                runCatching {
                    process.errorStream.bufferedReader().forEachLine { synchronized(errors) { if (errors.length < 2048) errors.append(it).append('\n') } }
                }
            }.apply { isDaemon = true; name = "MobileTestBench-log-errors"; start() }
            Thread {
                var pending: LogEntry? = null
                try {
                    val reader = process.inputStream.bufferedReader()
                    while (true) {
                        // Nothing more buffered: the message is complete, do not hold it back until the next line.
                        if (pending != null && !reader.ready()) {
                            onEntry(pending)
                            pending = null
                        }
                        val text = reader.readLine() ?: break
                        val entry = parse(text)
                        if (entry != null) {
                            pending?.let(onEntry)
                            pending = entry
                        } else if (pending != null && text.isNotBlank()) {
                            pending.message += "\n" + text
                        }
                    }
                    pending?.let(onEntry)
                } catch (_: Exception) {
                    // the stream was closed
                }
                onEnd(synchronized(errors) { errors.toString().trim() }.ifEmpty { null })
            }.apply { isDaemon = true; name = "MobileTestBench-log"; start() }
            return stream
        }
    }
}
