package io.github.marcomaes.mobiletestbench.core.crashes

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.Platform

/** One entry of `mobilecli device crashes list`. [timestamp] is `yyyy-MM-dd HH:mm:ss[.SSS]` in local time. */
data class CrashEntry(val id: String, val process: String, val timestamp: String)

/** Pure helpers for the crash report viewer. */
object Crashes {

    val LIST_ARGS = listOf("device", "crashes", "list")

    /** The id is one argument on purpose: Android ids contain colons, iOS ids contain spaces. */
    fun getArgs(id: String) = listOf("device", "crashes", "get", id)

    /** Entries of the `data` array, newest first (the platforms deliver oldest first or alphabetical). Null or malformed data gives an empty list. */
    fun parseList(data: JsonElement?): List<CrashEntry> {
        if (data == null || !data.isJsonArray) return emptyList()
        return data.asJsonArray.mapNotNull { e ->
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            CrashEntry(id, o.str("processName") ?: "", o.str("timestamp") ?: "")
        }.sortedByDescending { it.timestamp }
    }

    /** The report text of a `crashes get` result. */
    fun parseReport(data: JsonElement?): String? =
        data?.takeIf { it.isJsonObject }?.asJsonObject?.str("content")

    /** Case-insensitive match on process, id and time; blank matches everything. */
    fun filter(entries: List<CrashEntry>, text: String): List<CrashEntry> {
        val q = text.trim().lowercase()
        if (q.isEmpty()) return entries
        return entries.filter { q in it.process.lowercase() || q in it.id.lowercase() || q in it.timestamp.lowercase() }
    }

    /** A file name for saving: the id with characters that are invalid on common file systems replaced; `.ips` for iOS reports, else `.txt`. */
    fun fileName(entry: CrashEntry, platform: Platform): String {
        val base = entry.id.replace(Regex("[:/\\\\*?\"<>|]+"), "-").removeSuffix(".ips")
        return base + if (platform == Platform.IOS) ".ips" else ".txt"
    }

    /** Shown above the list on iOS: mobilecli reads the Mac's crash folder, not the simulator's. */
    fun listNote(device: Device): String? =
        if (device.platform == Platform.IOS) "Host crash reports (the Mac's DiagnosticReports), not only this simulator" else null

    /** The one-line crash summary (exception type, signal or termination reason), or null when none can be found. */
    fun summary(platform: Platform, content: String): String? =
        runCatching { if (platform == Platform.IOS) iosSummary(content) else androidSummary(content) }.getOrNull()?.takeIf { it.isNotBlank() }

    private val logcatPrefix = Regex("""^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+\d+\s+\d+\s+[VDIWEF]\s+[^:]*:\s?""")
    private val javaException = Regex("""^[\w.$]+(Exception|Error|Throwable)(: .*)?$""")
    private val signalLine = Regex("""signal \d+ \((\w+)\), code (-?\d+) \(([\w_]+)\)(?:, fault addr (\S+))?""")

    /** The log lines without the logcat prefix (`date time pid tid L tag: `). */
    fun androidBody(content: String): List<String> = content.lines().map { it.replace(logcatPrefix, "") }

    private fun androidSummary(content: String): String? {
        val lines = androidBody(content)
        val fatal = lines.indexOfFirst { it.startsWith("FATAL EXCEPTION") }
        if (fatal >= 0) {
            lines.drop(fatal + 1).map { it.trim() }.firstOrNull { javaException.matches(it) }?.let { return it }
        }
        lines.firstNotNullOfOrNull { signalLine.find(it) }?.let { m ->
            val (sig, _, code, addr) = m.destructured
            val abort = lines.firstOrNull { it.startsWith("Abort message:") }?.removePrefix("Abort message:")?.trim()?.trim('\'')
            return buildString {
                append(sig).append(" (").append(code).append(')')
                if (addr.isNotEmpty()) append(" fault addr ").append(addr)
                if (!abort.isNullOrEmpty()) append(": ").append(abort)
            }
        }
        lines.firstOrNull { it.startsWith("Abort message:") }?.let { return it.trim() }
        return lines.firstOrNull { it.startsWith("Fatal signal") }?.trim()
    }

    /** An .ips file: line 1 is a header JSON, the rest one JSON document. Falls back to the header's app name. */
    private fun iosSummary(content: String): String? {
        val body = content.substringAfter('\n', "")
        val root = runCatching { JsonParser.parseString(body) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        if (root != null) {
            val ex = root.getAsJsonObject("exception")
            val term = root.getAsJsonObject("termination")
            val parts = mutableListOf<String>()
            val type = ex?.str("type")
            val signal = ex?.str("signal")
            if (type != null) parts += if (signal != null) "$type ($signal)" else type
            ex?.str("subtype")?.let { parts += it }
            val reason = term?.str("indicator") ?: term?.let { t -> listOfNotNull(t.str("namespace"), t.get("code")?.takeIf { !it.isJsonNull }?.asString).joinToString(" ").ifBlank { null } }
            if (reason != null) parts += reason
            val asi = root.getAsJsonObject("asi")?.entrySet()?.firstNotNullOfOrNull { (_, v) -> if (v.isJsonArray) v.asJsonArray.firstOrNull()?.takeIf { it.isJsonPrimitive }?.asString else null }
            if (asi != null) parts += asi
            if (parts.isNotEmpty()) return parts.joinToString(" - ")
        }
        return null
    }

    private fun com.google.gson.JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
