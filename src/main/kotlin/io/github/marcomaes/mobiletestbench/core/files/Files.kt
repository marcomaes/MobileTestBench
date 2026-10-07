package io.github.marcomaes.mobiletestbench.core.files

import com.google.gson.JsonObject
import io.github.marcomaes.mobiletestbench.core.MobilecliJson
import io.github.marcomaes.mobiletestbench.core.exec.CommandResult
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import java.io.File
import java.io.RandomAccessFile
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A failure of a file operation; the message is meant to be shown as is. */
class FilesException(message: String) : RuntimeException(message)

/** One entry of a directory listing. [modifiedMillis] is epoch milliseconds, null when unknown. */
data class FileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modifiedMillis: Long? = null,
    /** True for a symbolic link (Android only: iOS listings cannot tell a link from a file). A link is never a [isDir]; probe it with [FileBackend.kindOf]. */
    val isLink: Boolean = false,
)

enum class EntryKind { DIR, FILE }

/** Pure path helpers. Remote paths always use '/' and are absolute (mobilecli would read a leading '-' as a flag). */
object FilePaths {
    fun join(dir: String, name: String): String = if (dir.endsWith("/")) dir + name else "$dir/$name"

    fun name(path: String): String = path.trimEnd('/').substringAfterLast('/')

    fun parent(path: String): String {
        val t = path.trimEnd('/')
        val i = t.lastIndexOf('/')
        return if (i <= 0) "/" else t.substring(0, i)
    }

    /** Collapses `//`, `.` and `..` (never above "/"), drops a trailing slash. Relative input stays relative to "/". */
    fun normalize(path: String): String {
        val parts = ArrayDeque<String>()
        for (p in path.split('/')) when (p) {
            "", "." -> Unit
            ".." -> parts.removeLastOrNull()
            else -> parts.addLast(p)
        }
        return "/" + parts.joinToString("/")
    }

    fun isInside(root: String, path: String): Boolean {
        val r = normalize(root)
        val p = normalize(path)
        return r == "/" || p == r || p.startsWith("$r/")
    }

    /** The parent of [path] for the Up button, or null when [path] already is [root]. */
    fun up(root: String, path: String): String? {
        val p = normalize(path)
        return if (p == normalize(root)) null else parent(p).takeIf { isInside(root, it) }
    }

    /** Single quotes for the remote sh: `it's` becomes `'it'\''s'`. */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** A reason the name is not valid for a new folder, or null when it is fine. */
    fun nameProblem(name: String): String? = when {
        name.isBlank() -> "Enter a name"
        name == "." || name == ".." -> "'$name' is not a valid name"
        name.contains('/') -> "A name cannot contain '/'"
        name.any { it == '\n' || it == '\r' || it == '\u0000' } -> "A name cannot contain line breaks"
        else -> null
    }
}

/** Sizes and dates as shown in the table. */
object FileFormat {
    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0)
        else -> String.format(java.util.Locale.ROOT, "%.2f GB", bytes / 1073741824.0)
    }

    fun modified(millis: Long?, zone: ZoneId = ZoneId.systemDefault()): String =
        if (millis == null) "" else DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(millis).atZone(zone))

    fun typeLabel(e: FileEntry): String = when {
        e.isDir -> "Folder"
        e.isLink -> "Link"
        else -> "File"
    }
}

enum class SortKey { NAME, TYPE, SIZE, MODIFIED }

object FileSort {
    /** Folders (and links) first, then by [key]; ties by name. Descending reverses within the folder and file groups. */
    fun sort(entries: List<FileEntry>, key: SortKey = SortKey.NAME, ascending: Boolean = true): List<FileEntry> {
        val byName = compareBy<FileEntry> { it.name.lowercase() }.thenBy { it.name }
        val main: Comparator<FileEntry> = when (key) {
            SortKey.NAME -> byName
            SortKey.TYPE -> compareBy<FileEntry> { FileFormat.typeLabel(it) }.then(byName)
            SortKey.SIZE -> compareBy<FileEntry> { if (it.isDir) -1L else it.size }.then(byName)
            SortKey.MODIFIED -> compareBy<FileEntry> { it.modifiedMillis ?: Long.MIN_VALUE }.then(byName)
        }
        val ordered = if (ascending) main else main.reversed()
        return entries.sortedWith(compareBy<FileEntry> { if (it.isDir) 0 else 1 }.then(ordered))
    }
}

/** Parsers for the two listing formats. */
object FileParsers {

    /** `mobilecli fs ls` stdout (the `{"status":"ok","data":[...]}` envelope). Throws [MobilecliJson]'s exception on an error envelope. */
    fun parseMobilecliLs(stdout: String): List<FileEntry> {
        val data = MobilecliJson.data(stdout) ?: return emptyList()
        if (!data.isJsonArray) return emptyList()
        return data.asJsonArray.mapNotNull { e -> e.takeIf { it.isJsonObject }?.asJsonObject?.let(::mobilecliEntry) }
    }

    private fun mobilecliEntry(o: JsonObject): FileEntry? {
        val path = o.get("path")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val name = o.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: FilePaths.name(path)
        val isDir = o.get("isDir")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val size = if (isDir) 0L else o.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
        val time = o.get("modTime")?.takeIf { it.isJsonPrimitive }?.asString?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
        return FileEntry(name, path, isDir, size, time)
    }

    /** The common start folder of an iOS simulator device listing: the parent of the entry paths. Null for an empty listing. */
    fun parentOfEntries(entries: List<FileEntry>): String? = entries.firstOrNull()?.let { FilePaths.parent(it.path) }

    /**
     * Lines `%F|%s|%Y|%n` of `stat -c`, as printed by `find ... -exec stat`. [dir] is the listed directory; the entry name is the part of
     * `%n` after the last '/' (`./name` inside an app, a full path on device storage). Lines that do not parse (warnings) are skipped.
     */
    fun parseStatLines(output: String, dir: String): List<FileEntry> = output.lineSequence().mapNotNull { line ->
        val parts = line.trimEnd('\r').split('|', limit = 4)
        if (parts.size < 4) return@mapNotNull null
        val size = parts[1].toLongOrNull() ?: return@mapNotNull null
        val seconds = parts[2].toLongOrNull() ?: return@mapNotNull null
        val name = parts[3].trimEnd('/').substringAfterLast('/')
        if (name.isEmpty()) return@mapNotNull null
        val type = parts[0]
        val isDir = type == "directory"
        FileEntry(name, FilePaths.join(dir, name), isDir, if (isDir) 0L else size, seconds * 1000, isLink = type == "symbolic link")
    }.toList()
}

/** Turns tool output into a readable message. */
object FileErrors {
    /** The first useful text of a failed command: stderr, else stdout (run-as prints its errors there). */
    fun message(result: CommandResult, fallback: String): String =
        listOf(result.stderr.trim(), result.text.trim()).firstOrNull { it.isNotEmpty() }?.lines()?.take(3)?.joinToString(" ")?.take(300) ?: fallback

    /** Adds the way out for a non debuggable Android app. */
    fun appDataHint(message: String, packageName: String?): String =
        if (packageName != null && (message.contains("not debuggable") || message.contains("unknown package")))
            "$message. Only debuggable apps allow this; try Device storage and /sdcard/Android/data/$packageName"
        else message
}

/** What the Files tab needs from a device. All methods block; call them off the EDT. Failures are [FilesException]s. */
interface FileBackend {
    /** Navigation never goes above this folder. */
    val root: String

    /** Where the tab starts (inside [root]). */
    val home: String
    val isAppData: Boolean

    /** The android package or iOS bundle id of an app container, null for device storage. */
    val appId: String?

    /** Whether Download can fetch a whole folder. */
    val canDownloadFolder: Boolean

    fun list(path: String): List<FileEntry>

    /** Whether [path] (a symbolic link, or any non folder entry) is a folder or a file. */
    fun kindOf(path: String): EntryKind

    /** At most [maxBytes] bytes from the start of the file. */
    fun read(path: String, maxBytes: Int): ByteArray

    /** Copies [remote] into [localFolder] under its own name and returns the local file. A folder only when [canDownloadFolder]. */
    fun pull(remote: FileEntry, localFolder: File): File

    /** Copies [local] into the remote folder [remoteDir] under its own name and returns that remote path. */
    fun push(local: File, remoteDir: String): String

    fun mkdir(path: String)
    fun delete(entry: FileEntry)
}

/** Runs a command that streams a file through stdin or stdout. Returns the exit code and the text it printed apart from the streamed file. */
internal fun runStreaming(runner: CommandRunner, command: List<String>, stdinFile: File?, stdoutFile: File?, timeout: Duration): Pair<Int, String> {
    val process = runner.start(command)
    // One buffer per stream: the two pump threads must not share a StringBuilder.
    val errText = StringBuilder()
    val outText = StringBuilder()
    val threads = mutableListOf<Thread>()
    fun pump(name: String, block: () -> Unit) = Thread(block, "MobileTestBench-files-$name").apply { isDaemon = true; start(); threads += this }
    pump("err") { process.errorStream.use { s -> errText.append(String(s.readNBytes(4096), Charsets.UTF_8)); s.transferTo(java.io.OutputStream.nullOutputStream()) } }
    pump("out") {
        process.inputStream.use { s ->
            if (stdoutFile != null) stdoutFile.outputStream().use { s.copyTo(it) }
            else { outText.append(String(s.readNBytes(4096), Charsets.UTF_8)); s.transferTo(java.io.OutputStream.nullOutputStream()) }
        }
    }
    if (stdinFile != null) pump("in") { runCatching { process.outputStream.use { o -> stdinFile.inputStream().use { it.copyTo(o) } } } } else process.outputStream.close()
    if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
        process.destroyForcibly()
        throw FilesException("Timed out after ${timeout.seconds / 60} min")
    }
    threads.forEach { it.join(5000) }
    return process.exitValue() to listOf(errText, outText).joinToString("\n") { it.toString().trim() }.trim()
}

private val PACKAGE_NAME = Regex("[A-Za-z0-9._-]+")

/**
 * Android files over adb only. mobilecli is not used here: its `fs pull` reports success for a missing file and writes the shell's error
 * text into the local file, and its other errors lose the reason. [packageName] set means the app's private data through `run-as` (debuggable
 * apps only); paths stay absolute (`/data/user/0/<pkg>/...`) in the UI and are made relative to the app's data folder for `run-as`.
 */
class AndroidFiles(
    private val adb: String,
    private val serial: String,
    private val runner: CommandRunner,
    private val packageName: String? = null,
) : FileBackend {
    init {
        // The id ends up in the device shell (`run-as <id>`): only package name characters get that far.
        if (packageName != null && !PACKAGE_NAME.matches(packageName)) throw FilesException("'$packageName' is not a package name")
    }

    override val root: String = if (packageName == null) "/" else "/data/user/0/$packageName"
    override val home: String = if (packageName == null) "/sdcard" else root
    override val isAppData: Boolean get() = packageName != null
    override val appId: String? get() = packageName
    override val canDownloadFolder: Boolean get() = packageName == null

    private fun shellCommand(script: String): List<String> =
        listOf(adb, "-s", serial, "shell", if (packageName == null) script else "run-as $packageName sh -c ${FilePaths.shellQuote(script)}")

    /** The path as the shell sees it: unchanged on device storage, relative to the data folder in an app. */
    internal fun target(path: String): String {
        val p = FilePaths.normalize(path)
        if (packageName == null) return p
        if (!FilePaths.isInside(root, p)) throw FilesException("$p is outside the app's data folder")
        return if (p == root) "." else p.removePrefix("$root/")
    }

    private fun q(path: String) = FilePaths.shellQuote(target(path))

    private fun sh(script: String, timeout: Duration = Duration.ofSeconds(30)): CommandResult = runner.run(shellCommand(script), timeout)

    private fun fail(result: CommandResult, what: String): Nothing =
        throw FilesException(FileErrors.appDataHint(FileErrors.message(result, "$what failed (exit ${result.exitCode})"), packageName))

    override fun list(path: String): List<FileEntry> {
        val dir = FilePaths.normalize(path)
        val result = sh("find ${FilePaths.shellQuote(target(dir).trimEnd('/') + "/")} -mindepth 1 -maxdepth 1 -exec stat -c '%F|%s|%Y|%n' {} +")
        val entries = FileParsers.parseStatLines(result.text, dir)
        // find exits 1 when only some entries were unreadable; a failure with nothing parsed is the real error (also run-as's own message).
        if (!result.ok && entries.isEmpty()) fail(result, "Listing")
        return entries
    }

    override fun kindOf(path: String): EntryKind {
        val result = sh("stat -L -c %F ${q(path)}")
        if (!result.ok) fail(result, "Reading")
        return if (result.text.trim() == "directory") EntryKind.DIR else EntryKind.FILE
    }

    override fun read(path: String, maxBytes: Int): ByteArray {
        val result = sh("head -c $maxBytes ${q(path)}")
        if (!result.ok) fail(result, "Reading")
        return result.stdout
    }

    override fun pull(remote: FileEntry, localFolder: File): File {
        val local = File(localFolder, remote.name)
        val existedBefore = local.exists()
        if (packageName == null) {
            // adb pull exits 1 with a clear message; a folder is pulled recursively into <localFolder>/<name>.
            val result = runner.run(listOf(adb, "-s", serial, "pull", remote.path, if (remote.isDir) localFolder.path else local.path), Duration.ofMinutes(10))
            if (!result.ok) {
                // Only drop a file this call may have created; a file the user confirmed to overwrite stays when adb wrote nothing.
                if (!remote.isDir && !existedBefore) local.delete()
                fail(result, "Download")
            }
        } else {
            if (remote.isDir) throw FilesException("Downloading a folder of an app's data is not supported; download its files one by one")
            // Stream into a temporary file next to the target and move it over only on success: a failure or timeout leaves no partial file
            // and keeps an existing file the user confirmed to overwrite.
            val part = File(localFolder, ".${remote.name}.mobiletestbench-part")
            try {
                val (code, text) = runStreaming(runner, shellCommand("cat ${q(remote.path)}"), null, part, Duration.ofMinutes(10))
                if (code != 0) {
                    // run-as prints its own errors on stdout, which went into the file: read them back.
                    val message = text.ifBlank { part.takeIf { it.isFile }?.let { f -> String(f.readBytes().take(300).toByteArray(), Charsets.UTF_8).trim() }.orEmpty() }
                    throw FilesException(FileErrors.appDataHint(message.ifBlank { "Download failed (exit $code)" }, packageName))
                }
                java.nio.file.Files.move(part.toPath(), local.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally {
                part.delete()
            }
        }
        return local
    }

    override fun push(local: File, remoteDir: String): String {
        val remote = FilePaths.join(FilePaths.normalize(remoteDir), local.name)
        if (packageName == null) {
            val result = runner.run(listOf(adb, "-s", serial, "push", local.path, remote), Duration.ofMinutes(10))
            if (!result.ok) fail(result, "Upload")
        } else {
            val (code, text) = runStreaming(runner, shellCommand("cat > ${q(remote)}"), local, null, Duration.ofMinutes(10))
            if (code != 0) throw FilesException(FileErrors.appDataHint(text.ifBlank { "Upload failed (exit $code)" }, packageName))
        }
        return remote
    }

    override fun mkdir(path: String) {
        val result = sh("mkdir ${q(path)}")
        if (!result.ok) fail(result, "New folder")
    }

    override fun delete(entry: FileEntry) {
        val result = sh("rm ${if (entry.isDir) "-r " else ""}${q(entry.path)}")
        if (!result.ok) fail(result, "Delete")
    }
}

/**
 * iOS simulator files through `mobilecli fs`, with absolute host paths below [root] (the simulator's data folder, or an app's data container:
 * mobilecli's pull and push take no bundle id). [call] runs `mobilecli <args>` for the device and returns stdout, throwing with mobilecli's own text.
 * A simulator's files are plain host files, so previews and folder downloads read them directly.
 */
class IosFiles(
    private val call: (List<String>) -> String,
    override val root: String,
    override val appId: String? = null,
) : FileBackend {
    override val home: String get() = root
    override val isAppData: Boolean get() = appId != null
    override val canDownloadFolder: Boolean get() = true

    private fun guard(path: String): String {
        val p = FilePaths.normalize(path)
        if (!FilePaths.isInside(root, p)) throw FilesException("$p is outside the simulator's ${if (appId != null) "app container" else "data folder"}")
        return p
    }

    private fun run(args: List<String>): String = try {
        call(args)
    } catch (e: FilesException) {
        throw e
    } catch (e: Exception) {
        throw FilesException((e.message ?: e.javaClass.simpleName).replace(root, "<root>"))
    }

    override fun list(path: String): List<FileEntry> = try {
        FileParsers.parseMobilecliLs(run(listOf("fs", "ls", guard(path))))
    } catch (e: io.github.marcomaes.mobiletestbench.core.MobilecliException) {
        throw FilesException(e.message.orEmpty().replace(root, "<root>"))
    }

    override fun kindOf(path: String): EntryKind {
        val p = guard(path)
        val entries = list(p)
        return if (entries.size == 1 && FilePaths.normalize(entries[0].path) == p) EntryKind.FILE else EntryKind.DIR
    }

    override fun read(path: String, maxBytes: Int): ByteArray {
        val file = File(guard(path))
        if (file.isDirectory) throw FilesException("${file.name} is a folder")
        if (!file.isFile) throw FilesException("${file.name} cannot be read")
        return RandomAccessFile(file, "r").use { f ->
            val buffer = ByteArray(minOf(maxBytes.toLong(), f.length()).toInt())
            f.readFully(buffer)
            buffer
        }
    }

    override fun pull(remote: FileEntry, localFolder: File): File {
        val p = guard(remote.path)
        val local = File(localFolder, remote.name)
        if (remote.isDir) {
            if (!File(p).copyRecursively(local, overwrite = true)) throw FilesException("Copying ${remote.name} failed")
        } else {
            run(listOf("fs", "pull", p, local.path))
        }
        return local
    }

    override fun push(local: File, remoteDir: String): String {
        // iOS refuses a push to an existing directory: always push to the full file path.
        val remote = FilePaths.join(guard(remoteDir), local.name)
        run(listOf("fs", "push", local.path, remote))
        return remote
    }

    override fun mkdir(path: String) {
        run(listOf("fs", "mkdir", guard(path)))
    }

    override fun delete(entry: FileEntry) {
        run(if (entry.isDir) listOf("fs", "rm", "-r", guard(entry.path)) else listOf("fs", "rm", guard(entry.path)))
    }

    companion object {
        /** The simulator's data folder from `fs ls` without a path (the parent of the entries). */
        fun deviceRoot(call: (List<String>) -> String): String =
            FileParsers.parentOfEntries(FileParsers.parseMobilecliLs(call(listOf("fs", "ls"))))?.let(FilePaths::normalize)
                ?: throw FilesException("Could not find the simulator's data folder")

        /** The app data container from `simctl get_app_container <udid> <bundle> data` output; null when there is none (system apps print `(null)`). */
        fun parseContainer(result: CommandResult): String? =
            result.takeIf { it.ok }?.text?.trim()?.takeIf { it.isNotEmpty() && it != "(null)" && it.startsWith("/") }?.let(FilePaths::normalize)

        fun appContainer(runner: CommandRunner, xcrun: String, udid: String, bundleId: String): String {
            val result = runner.run(listOf(xcrun, "simctl", "get_app_container", udid, bundleId, "data"))
            return parseContainer(result) ?: throw FilesException(
                if (result.ok) "$bundleId has no data container on this simulator (system apps have none)"
                else "No data container for $bundleId: " + FileErrors.message(result, "app not installed"),
            )
        }
    }
}
