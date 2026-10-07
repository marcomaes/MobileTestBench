package io.github.marcomaes.mobiletestbench.core.notifications

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import java.io.File
import java.time.Duration

/** The fields of the iOS form. Blank strings are left out of the payload. */
data class ApnsForm(
    val title: String = "",
    val subtitle: String = "",
    val body: String = "",
    val badge: String = "",
    val sound: String = "",
    val category: String = "",
    /** An optional JSON object whose keys are added next to `aps`. */
    val customJson: String = "",
)

/**
 * A payload ready to show: [pretty] for the preview, [compact] is what is sent (exactly these UTF-8 bytes), [size] its byte count.
 * [problem] is set when the payload cannot be sent at all; [oversize] when it is above the 4096 byte APNs limit (simctl then refuses it).
 */
data class ApnsPayload(val pretty: String, val compact: String, val size: Int, val problem: String?) {
    val oversize: Boolean get() = size > ApnsPayloads.LIMIT
    val sendable: Boolean get() = problem == null
}

object ApnsPayloads {
    /** The inclusive limit simctl enforces on the raw bytes it is given (verified: 4096 bytes sent, 4097 refused). */
    const val LIMIT = 4096

    private val pretty = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val compact = GsonBuilder().disableHtmlEscaping().create()

    fun byteSize(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    /** Builds `{"aps":{"alert":{...},"badge":..,"sound":..,"category":..}, ...custom}` from the form. */
    fun build(form: ApnsForm): ApnsPayload {
        val root = JsonObject()
        val aps = JsonObject()
        val alert = JsonObject()
        if (form.title.isNotBlank()) alert.addProperty("title", form.title)
        if (form.subtitle.isNotBlank()) alert.addProperty("subtitle", form.subtitle)
        if (form.body.isNotBlank()) alert.addProperty("body", form.body)
        if (alert.size() > 0) aps.add("alert", alert)
        var problem: String? = null
        val badge = form.badge.trim()
        if (badge.isNotEmpty()) {
            val n = badge.toIntOrNull()
            if (n == null || n < 0) problem = "Badge must be a whole number of 0 or more" else aps.addProperty("badge", n)
        }
        if (form.sound.isNotBlank()) aps.addProperty("sound", form.sound.trim())
        if (form.category.isNotBlank()) aps.addProperty("category", form.category.trim())
        root.add("aps", aps)
        if (form.customJson.isNotBlank()) {
            try {
                val custom = JsonParser.parseString(form.customJson)
                if (!custom.isJsonObject) problem = problem ?: "Custom data must be a JSON object"
                else for ((k, v) in custom.asJsonObject.entrySet()) {
                    if (k == "aps") problem = problem ?: "Custom data must not contain \"aps\" (use the fields above)"
                    else root.add(k, v)
                }
            } catch (e: Exception) {
                problem = problem ?: "Custom data is not valid JSON: ${firstLine(e.message)}"
            }
        }
        if (problem == null && aps.size() == 0) problem = "Fill in at least one of title, subtitle, body, badge, sound or category (an empty aps is refused by the simulator)"
        return render(root, problem)
    }

    /** Validates a hand written payload and renders it. Invalid JSON gives the text back unchanged with a [ApnsPayload.problem]. */
    fun inspect(raw: String): ApnsPayload {
        val element: JsonElement = try {
            JsonParser.parseString(raw)
        } catch (e: Exception) {
            return ApnsPayload(raw, raw, byteSize(raw), "Not valid JSON: ${firstLine(e.message)}")
        }
        if (raw.isBlank() || !element.isJsonObject) return ApnsPayload(raw, raw, byteSize(raw), "The payload must be a JSON object")
        val root = element.asJsonObject
        val aps = root.get("aps")
        val problem = when {
            aps == null -> "The payload has no \"aps\" key"
            !aps.isJsonObject -> "\"aps\" must be an object"
            aps.asJsonObject.size() == 0 -> "\"aps\" is empty (the simulator refuses it)"
            aps.asJsonObject.get("badge")?.let { !it.isJsonPrimitive || !it.asJsonPrimitive.isNumber } == true -> "\"badge\" must be a number"
            else -> null
        }
        return render(root, problem)
    }

    private fun render(root: JsonObject, problem: String?): ApnsPayload {
        val c = compact.toJson(root)
        return ApnsPayload(pretty.toJson(root), c, byteSize(c), problem)
    }

    private fun firstLine(message: String?) = (message ?: "parse error").lineSequence().first()
}

/** What a send did: the exact text of the tool, [error] when it failed. */
data class NotificationResult(val text: String, val error: Boolean)

/** The Android notification styles of `cmd notification post -S`. */
enum class PostStyle(val label: String, val arg: String?) {
    PLAIN("Plain", null), BIGTEXT("Big text", "bigtext"), INBOX("Inbox", "inbox"), MESSAGING("Messaging", "messaging"),
}

data class AndroidPost(
    val title: String = "",
    val text: String = "",
    val style: PostStyle = PostStyle.PLAIN,
    val tag: String = "mobiletestbench",
    /** Inbox style: one line per entry. */
    val lines: List<String> = emptyList(),
    /** Messaging style: the conversation title and `who:text` messages. */
    val conversation: String = "",
    val messages: List<String> = emptyList(),
)

data class IntentSpec(
    val action: String = "",
    val pkg: String = "",
    val component: String = "",
    /** One `[type:]key=value` per line, see [NotificationCommands.parseExtras]. */
    val extras: String = "",
)

/** A parsed extra: [flag] is the `am` option (`--es`, `--ei`, ...). */
data class IntentExtra(val flag: String, val key: String, val value: String)

object NotificationCommands {
    /** Quotes [s] for a POSIX shell with single quotes (` ' ` becomes `'\''`). adb joins its arguments and the device shell parses them again, so every user value needs this. */
    fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** `xcrun simctl push <udid> <bundle> <file>`. */
    fun iosPush(xcrun: String, udid: String, bundleId: String, file: String) = listOf(xcrun, "simctl", "push", udid, bundleId, file)

    private val BUNDLE = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    fun validBundleId(id: String) = BUNDLE.matches(id)

    /** `adb -s <serial> shell "<command>"`: the command stays ONE argument. */
    fun adbShell(adb: String, serial: String, command: String) = listOf(adb, "-s", serial, "shell", command)

    /** A problem with the post (null when fine). Newlines cannot be passed on one command line reliably, so they are refused. */
    fun postProblem(p: AndroidPost): String? {
        val single = listOf("Title" to p.title, "Text" to p.text, "Tag" to p.tag, "Conversation" to p.conversation) +
            p.lines.map { "Inbox line" to it } + p.messages.map { "Message" to it }
        single.firstOrNull { it.second.contains('\n') || it.second.contains('\r') }?.let { return "${it.first} must be a single line" }
        if (p.tag.isBlank()) return "Tag is required (posting again with the same tag replaces the notification)"
        if (p.text.isBlank()) return "Text is required"
        if (p.style == PostStyle.INBOX && p.lines.none { it.isNotBlank() }) return "Inbox style needs at least one line"
        if (p.style == PostStyle.MESSAGING) {
            if (p.conversation.isBlank()) return "Messaging style needs a conversation title"
            val msgs = p.messages.filter { it.isNotBlank() }
            if (msgs.isEmpty()) return "Messaging style needs at least one who:text message"
            msgs.firstOrNull { !it.contains(':') || it.substringBefore(':').isBlank() }?.let { return "Message \"$it\" must look like who:text" }
        }
        return null
    }

    /** The remote command string of the post. Order as verified on the device: style and its arguments, then -t, tag, text. */
    fun androidPost(p: AndroidPost): String = buildString {
        append("cmd notification post")
        when (p.style) {
            PostStyle.PLAIN -> Unit
            PostStyle.BIGTEXT -> append(" -S bigtext")
            PostStyle.INBOX -> { append(" -S inbox"); p.lines.filter { it.isNotBlank() }.forEach { append(" --line ").append(shellQuote(it)) } }
            PostStyle.MESSAGING -> {
                append(" -S messaging --conversation ").append(shellQuote(p.conversation))
                p.messages.filter { it.isNotBlank() }.forEach { append(" --message ").append(shellQuote(it)) }
            }
        }
        if (p.title.isNotBlank()) append(" -t ").append(shellQuote(p.title))
        append(' ').append(shellQuote(p.tag)).append(' ').append(shellQuote(p.text))
    }

    const val CLEAR_SHELL = "service call notification 1 s16 com.android.shell i32 0"
    const val LIST = "cmd notification list"

    /** Entries of `cmd notification list` (`user|package|id|tag|uid`) posted by the shell. */
    fun shellNotificationCount(listOutput: String) = listOutput.lines().count { it.split('|').getOrNull(1) == "com.android.shell" }

    private val TYPES = mapOf("s" to "--es", "i" to "--ei", "z" to "--ez", "l" to "--el", "f" to "--ef")

    /** Parses extras: one `[s:|i:|z:|l:|f:]key=value` per line; blank lines and lines starting with `#` are skipped. Returns the extras or the first error. */
    fun parseExtras(text: String): Pair<List<IntentExtra>, String?> {
        val out = mutableListOf<IntentExtra>()
        for ((i, raw) in text.lines().withIndex()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) return out to "Extras line ${i + 1} must look like key=value"
            var key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1)
            var type = "s"
            val colon = key.indexOf(':')
            if (colon == 1 && key[0].toString() in TYPES) { type = key.substring(0, 1); key = key.substring(2).trim() }
            if (key.isEmpty() || key.any { it.isWhitespace() }) return out to "Extras line ${i + 1}: the key must not be empty or contain spaces"
            when (type) {
                "i" -> if (value.trim().toIntOrNull() == null) return out to "Extras line ${i + 1}: \"$value\" is not an int"
                "l" -> if (value.trim().toLongOrNull() == null) return out to "Extras line ${i + 1}: \"$value\" is not a long"
                "f" -> if (value.trim().toFloatOrNull() == null) return out to "Extras line ${i + 1}: \"$value\" is not a float"
                "z" -> if (value.trim() !in setOf("true", "false")) return out to "Extras line ${i + 1}: a boolean must be true or false"
            }
            out += IntentExtra(TYPES.getValue(type), key, if (type == "s") value else value.trim())
        }
        return out to null
    }

    fun broadcastProblem(i: IntentSpec): String? {
        if (i.action.isBlank() && i.component.isBlank()) return "Give an action or a component (am broadcast needs an intent)"
        listOf("Action" to i.action, "Package" to i.pkg, "Component" to i.component).firstOrNull { (_, v) -> v.any { it.isWhitespace() } }
            ?.let { return "${it.first} must not contain spaces" }
        if (i.component.isNotBlank() && !i.component.contains('/')) return "Component must look like package/.Class"
        return parseExtras(i.extras).second
    }

    /** The remote command string of the broadcast. Call [broadcastProblem] first. */
    fun androidBroadcast(i: IntentSpec): String = buildString {
        append("am broadcast")
        if (i.action.isNotBlank()) append(" -a ").append(shellQuote(i.action.trim()))
        if (i.component.isNotBlank()) append(" -n ").append(shellQuote(i.component.trim()))
        else if (i.pkg.isNotBlank()) append(" -p ").append(shellQuote(i.pkg.trim()))
        for (e in parseExtras(i.extras).first) append(' ').append(e.flag).append(' ').append(shellQuote(e.key)).append(' ').append(shellQuote(e.value))
    }

    /** simctl: success is exit 0 AND "Notification sent to ..." on stdout (invalid JSON exits 0 with an error on stderr). */
    fun interpretIosPush(exit: Int, stdout: String, stderr: String): NotificationResult {
        val out = stdout.trim()
        if (exit == 0 && out.startsWith("Notification sent to")) return NotificationResult(out.lines().first(), false)
        val text = stderr.trim().ifBlank { out }.ifBlank { "simctl push failed with exit code $exit and no output" }
        return NotificationResult(text, true)
    }

    /** `cmd notification post` prints "posting for user N:"; failures print "Error occurred..." or "Unknown command". */
    fun interpretAndroidPost(exit: Int, stdout: String, stderr: String): NotificationResult {
        val text = stdout.trim().ifBlank { stderr.trim() }
        val failed = exit != 0 || text.contains("Error occurred") || text.startsWith("Unknown command") || text.startsWith("Exception")
        return NotificationResult(text.ifBlank { "No output (exit code $exit)" }, failed)
    }

    /** `am broadcast` ends with "Broadcast completed: result=N"; errors are a Java stack trace on stdout. */
    fun interpretBroadcast(exit: Int, stdout: String, stderr: String): NotificationResult {
        val text = stdout.trim().ifBlank { stderr.trim() }
        val completed = text.lines().any { it.startsWith("Broadcast completed") }
        if (exit == 0 && completed) {
            return NotificationResult(text.lines().filter { it.isNotBlank() }.joinToString(" | "), false)
        }
        val lines = text.lines().filter { it.isNotBlank() }
        val exceptionLine = lines.firstOrNull { it.startsWith("java.") || it.startsWith("android.") }
        val msg = if (lines.firstOrNull()?.startsWith("Exception occurred") == true && exceptionLine != null) exceptionLine else lines.take(3).joinToString(" | ")
        return NotificationResult(msg.ifBlank { "No output (exit code $exit)" }, true)
    }
}

/** Runs the commands. Blocking; call off the EDT. */
class IosNotifications(private val xcrun: String, private val udid: String, private val runner: CommandRunner) {
    /** Sends [payload] (compact JSON) to [bundleId]. The payload goes through a temporary file that is deleted again. */
    fun push(bundleId: String, payload: String): NotificationResult {
        val file = File.createTempFile("jbvm-push", ".json")
        try {
            file.writeBytes(payload.toByteArray(Charsets.UTF_8))
            val r = runner.run(NotificationCommands.iosPush(xcrun, udid, bundleId, file.path), Duration.ofSeconds(20))
            return NotificationCommands.interpretIosPush(r.exitCode, r.text, r.stderr)
        } catch (e: Exception) {
            return NotificationResult(e.message ?: e.javaClass.simpleName, true)
        } finally {
            file.delete()
        }
    }
}

class AndroidNotifications(private val adb: String, private val serial: String, private val runner: CommandRunner) {
    private fun shell(command: String) = runner.run(NotificationCommands.adbShell(adb, serial, command), Duration.ofSeconds(20))

    fun post(p: AndroidPost): NotificationResult = attempt {
        val r = shell(NotificationCommands.androidPost(p))
        NotificationCommands.interpretAndroidPost(r.exitCode, r.text, r.stderr)
    }

    fun broadcast(i: IntentSpec): NotificationResult = attempt {
        val r = shell(NotificationCommands.androidBroadcast(i))
        NotificationCommands.interpretBroadcast(r.exitCode, r.text, r.stderr)
    }

    /** Cancels the shell package's notifications (binder transaction 1, verified on Android 17 only), then lists to verify. */
    fun clearShell(): NotificationResult = attempt {
        shell(NotificationCommands.CLEAR_SHELL)
        val left = NotificationCommands.shellNotificationCount(shell(NotificationCommands.LIST).text)
        if (left == 0) NotificationResult("Cleared: no shell notifications left", false)
        else NotificationResult("$left shell notification(s) remain. The cancel call is version specific; swipe them away in the shade instead.", true)
    }

    private fun attempt(block: () -> NotificationResult) = try { block() } catch (e: Exception) { NotificationResult(e.message ?: e.javaClass.simpleName, true) }
}

/** One remembered send: [kind] is "ios", "post" or "intent", [fields] what is needed to refill the form. */
data class HistoryEntry(val kind: String, val fields: Map<String, String>) {
    /** A one-line description for the combo. */
    val label: String get() = when (kind) {
        "ios" -> "iOS ${fields["bundle"].orEmpty()}: " + (fields["payload"].orEmpty().let { runCatching { ApnsPayloads.inspect(it).compact }.getOrDefault(it) })
        "post" -> "Post ${fields["tag"].orEmpty()}: ${fields["title"].orEmpty()} ${fields["text"].orEmpty()}".trim()
        else -> "Intent ${fields["action"].orEmpty().ifBlank { fields["component"].orEmpty() }}"
    }.take(100)
}

/** The last [MAX] sends, newest first, without duplicates, stored as one JSON string per entry. */
object PayloadHistory {
    const val MAX = 15
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    fun add(list: List<HistoryEntry>, entry: HistoryEntry): List<HistoryEntry> = (listOf(entry) + list.filter { it != entry }).take(MAX)

    fun serialize(e: HistoryEntry): String = JsonObject().apply {
        addProperty("kind", e.kind)
        add("fields", JsonObject().also { o -> e.fields.toSortedMap().forEach { (k, v) -> o.addProperty(k, v) } })
    }.let { gson.toJson(it) }

    /** Null for a line that is not a valid entry (it is dropped when loading). */
    fun parse(s: String): HistoryEntry? = runCatching {
        val o = JsonParser.parseString(s).asJsonObject
        val f = o.getAsJsonObject("fields").entrySet().associate { it.key to it.value.asString }
        HistoryEntry(o.get("kind").asString, f)
    }.getOrNull()

    fun load(lines: List<String>): List<HistoryEntry> = lines.mapNotNull(::parse).take(MAX)
    fun save(list: List<HistoryEntry>): List<String> = list.take(MAX).map(::serialize)
}
