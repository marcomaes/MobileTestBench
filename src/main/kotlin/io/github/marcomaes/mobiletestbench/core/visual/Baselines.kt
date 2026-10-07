package io.github.marcomaes.mobiletestbench.core.visual

import com.google.gson.GsonBuilder
import io.github.marcomaes.mobiletestbench.core.Rect
import io.github.marcomaes.mobiletestbench.core.Size
import java.awt.image.BufferedImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.imageio.ImageIO

/** The `<name>.json` next to a baseline PNG. Sizes are the logical size of the device and the pixel size of the saved frame. */
data class BaselineInfo(
    val device: String = "",
    val logicalWidth: Int = 0,
    val logicalHeight: Int = 0,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    /** Epoch milliseconds. */
    val savedAt: Long = 0,
    /** Areas skipped by the comparison (status bar clock, ads...), in logical coordinates as [x, y, width, height]. */
    val ignore: List<List<Int>> = emptyList(),
) {
    val logicalSize: Size get() = Size(logicalWidth, logicalHeight)
    val frameSize: Size get() = Size(frameWidth, frameHeight)
    val ignoreRects: List<Rect> get() = ignore.filter { it.size == 4 }.map { Rect(it[0], it[1], it[2], it[3]) }
    fun withIgnore(rects: List<Rect>) = copy(ignore = rects.map { listOf(it.x, it.y, it.width, it.height) })
}

/** A baseline found on disk. */
data class Baseline(val name: String, val png: File, val info: BaselineInfo)

/** Pure naming rules for baseline files. */
object BaselineNames {
    /** A file name part: letters, digits, dot, dash and underscore stay, the rest becomes `_`; never empty, no leading dot. */
    fun sanitize(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[^A-Za-z0-9._ -]"), "_").replace(Regex("\\s+"), " ").trim().trimStart('.')
        return cleaned.ifEmpty { "baseline" }.take(80)
    }

    /** Default name for a new baseline: the foreground app when known, else just the date. */
    fun defaultName(app: String?, now: Date = Date()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(now)
        return if (app.isNullOrBlank()) "screen-$stamp" else "${sanitize(app)}-$stamp"
    }

    /**
     * Why [raw] cannot be the new name of the baseline [current], or null when it can. Names compare after [sanitize]
     * and ignoring case (a case-insensitive file system treats "A" and "a" as one file), so a case-only change of the
     * baseline itself is allowed.
     */
    fun renameError(raw: String, current: String, existing: Collection<String>): String? {
        if (raw.isBlank()) return "Enter a name"
        val target = sanitize(raw)
        if (target == current) return "That is already the name"
        if (existing.any { it != current && it.equals(target, ignoreCase = true) }) return "A baseline named \"$target\" already exists"
        return null
    }

    /** Parses "x, y, w, h" (commas or spaces); null unless four integers with a positive size and a non negative origin. */
    fun parseRect(text: String): Rect? {
        val n = text.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: return null }
        if (n.size != 4 || n[0] < 0 || n[1] < 0 || n[2] <= 0 || n[3] <= 0) return null
        return Rect(n[0], n[1], n[2], n[3])
    }
}

/** Baselines on disk: `<root>/<device name>/<name>.png` plus `<name>.json`. Blocking file IO: call off the EDT. */
class BaselineStore(private val root: File) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun folder(deviceName: String): File = File(root, BaselineNames.sanitize(deviceName))

    private fun png(deviceName: String, name: String) = File(folder(deviceName), BaselineNames.sanitize(name) + ".png")
    private fun json(png: File) = File(png.parentFile, png.nameWithoutExtension + ".json")

    /** Writes the frame; saving over an existing name replaces the image and keeps its ignore regions. */
    fun save(deviceName: String, name: String, frame: BufferedImage, logical: Size?, now: Long = System.currentTimeMillis()): Baseline {
        val file = png(deviceName, name)
        file.parentFile.mkdirs()
        val kept = readInfo(json(file))?.ignore ?: emptyList()
        if (!ImageIO.write(frame, "png", file)) throw java.io.IOException("Could not write a PNG")
        val l = logical ?: Size(frame.width, frame.height)
        val info = BaselineInfo(deviceName, l.width, l.height, frame.width, frame.height, now, kept)
        writeInfo(file, info)
        return Baseline(file.nameWithoutExtension, file, info)
    }

    /** Writes [frame] over the image of [baseline] itself (whatever its file name) and keeps its ignore regions. */
    fun replaceImage(baseline: Baseline, frame: BufferedImage, logical: Size?, now: Long = System.currentTimeMillis()): Baseline {
        val kept = readInfo(json(baseline.png))?.ignore ?: emptyList()
        if (!ImageIO.write(frame, "png", baseline.png)) throw java.io.IOException("Could not write a PNG")
        val l = logical ?: Size(frame.width, frame.height)
        val info = BaselineInfo(baseline.info.device, l.width, l.height, frame.width, frame.height, now, kept)
        writeInfo(baseline.png, info)
        return Baseline(baseline.png.nameWithoutExtension, baseline.png, info)
    }

    fun list(deviceName: String): List<Baseline> =
        folder(deviceName).listFiles { f -> f.isFile && f.name.endsWith(".png") }.orEmpty()
            .map { Baseline(it.nameWithoutExtension, it, readInfo(json(it)) ?: BaselineInfo(device = deviceName)) }
            .sortedBy { it.name.lowercase() }

    fun load(baseline: Baseline): BufferedImage = ImageIO.read(baseline.png) ?: throw java.io.IOException("${baseline.png.name} is not a readable PNG")

    fun updateIgnore(baseline: Baseline, rects: List<Rect>): Baseline {
        val info = baseline.info.withIgnore(rects)
        writeInfo(baseline.png, info)
        return baseline.copy(info = info)
    }

    /** Renames the image and its sidecar; the ignore regions go with it. Throws when the new name is taken. */
    fun rename(baseline: Baseline, newName: String): Baseline {
        val target = File(baseline.png.parentFile, BaselineNames.sanitize(newName) + ".png")
        val sameFile = target.name.equals(baseline.png.name, ignoreCase = true)
        if (target.exists() && !sameFile) throw java.io.IOException("A baseline named \"${target.nameWithoutExtension}\" already exists")
        if (target.name == baseline.png.name) return baseline
        val oldJson = json(baseline.png)
        val newJson = json(target)
        if (newJson.exists() && !sameFile) throw java.io.IOException("A baseline named \"${target.nameWithoutExtension}\" already exists")
        if (!baseline.png.renameTo(target)) throw java.io.IOException("Could not rename ${baseline.png.name}")
        if (oldJson.isFile && !oldJson.renameTo(newJson)) {
            target.renameTo(baseline.png)
            throw java.io.IOException("Could not rename the baseline sidecar")
        }
        return Baseline(target.nameWithoutExtension, target, baseline.info)
    }

    fun delete(baseline: Baseline) {
        baseline.png.delete()
        json(baseline.png).delete()
    }

    private fun writeInfo(png: File, info: BaselineInfo) = json(png).writeText(gson.toJson(info))

    /** Null for a missing or unreadable sidecar (the baseline is still usable, without its remembered sizes). */
    fun readInfo(file: File): BaselineInfo? =
        runCatching { if (file.isFile) gson.fromJson(file.readText(), BaselineInfo::class.java) else null }.getOrNull()
}
