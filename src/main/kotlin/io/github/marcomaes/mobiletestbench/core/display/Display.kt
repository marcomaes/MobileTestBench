package io.github.marcomaes.mobiletestbench.core.display

import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.exec.CommandFailedException
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import java.time.Duration
import kotlin.math.abs

/**
 * What the Display tab can read and change. Values are the platform's raw text (for example `yes` / `no` / `auto`
 * for Android night mode); null means "not set" so a restore can delete the setting again instead of writing a value.
 */
enum class DisplayKey {
    /** Android `cmd uimode night`, iOS `simctl ui appearance`. */
    APPEARANCE,

    /** Android `font_scale`, iOS `simctl ui content_size`. */
    FONT_SIZE,

    /** Android only: the three global animation scales. */
    WINDOW_SCALE,
    TRANSITION_SCALE,
    ANIMATOR_SCALE,

    /** Android only. */
    SHOW_TOUCHES,
    LAYOUT_BOUNDS,
    POINTER_LOCATION,
    DONT_KEEP_ACTIVITIES;

    companion object {
        val ANIMATIONS = listOf(WINDOW_SCALE, TRANSITION_SCALE, ANIMATOR_SCALE)
    }
}

/** A snapshot read from the device: the raw values that could be read, and the error text for those that could not. */
data class DisplayState(val values: Map<DisplayKey, String?>, val errors: Map<DisplayKey, String> = emptyMap()) {
    fun has(key: DisplayKey) = key in values

    /** True when all three animation scales are 0; null when any of them could not be read. Unset means the default, 1. */
    val animationsOff: Boolean?
        get() = if (DisplayKey.ANIMATIONS.any { it !in values }) null
        else DisplayKey.ANIMATIONS.all { values[it]?.toDoubleOrNull() == 0.0 }

    val showTouches: Boolean? get() = if (DisplayKey.SHOW_TOUCHES in values) values[DisplayKey.SHOW_TOUCHES] == "1" else null

    val layoutBounds: Boolean? get() = if (DisplayKey.LAYOUT_BOUNDS in values) values[DisplayKey.LAYOUT_BOUNDS].equals("true", ignoreCase = true) else null

    /** `settings system pointer_location`; unset reads as off. */
    val pointerLocation: Boolean? get() = if (DisplayKey.POINTER_LOCATION in values) values[DisplayKey.POINTER_LOCATION] == "1" else null

    /** `settings global always_finish_activities` ("Don't keep activities"); unset reads as off. */
    val dontKeepActivities: Boolean? get() = if (DisplayKey.DONT_KEEP_ACTIVITIES in values) values[DisplayKey.DONT_KEEP_ACTIVITIES] == "1" else null
}

/** Dark mode choice. iOS has no automatic mode. */
enum class Appearance(val label: String, private val android: String, private val ios: String?) {
    AUTO("Auto", "auto", null),
    LIGHT("Light", "no", "light"),
    DARK("Dark", "yes", "dark");

    fun raw(platform: Platform): String = if (platform == Platform.ANDROID) android else ios ?: error("$label is not available on iOS")

    companion object {
        /** The choices a platform offers. */
        fun forPlatform(platform: Platform): List<Appearance> = if (platform == Platform.ANDROID) values().toList() else values().filter { it.ios != null }

        /** Null for a value that is none of the choices (Android `custom_schedule` / `custom_bedtime`). */
        fun fromRaw(platform: Platform, raw: String?): Appearance? =
            values().firstOrNull { (if (platform == Platform.ANDROID) it.android else it.ios) == raw }
    }
}

/** Font size presets and what each platform maps them to (Android font_scale as in the Pixel settings, iOS content size category). */
enum class FontPreset(val label: String, val androidScale: String, val iosCategory: String) {
    SMALL("Small", "0.85", "small"),
    DEFAULT("Default", "1.0", "large"),
    LARGE("Large", "1.15", "extra-extra-large"),
    LARGEST("Largest", "1.3", "accessibility-extra-extra-extra-large");

    fun raw(platform: Platform): String = if (platform == Platform.ANDROID) androidScale else iosCategory

    companion object {
        /** The preset whose scale matches [raw] (an unset font_scale is the default 1.0); null for a custom value such as 1.5. */
        fun fromAndroid(raw: String?): FontPreset? {
            if (raw == null) return DEFAULT
            val v = raw.toDoubleOrNull() ?: return null
            return values().firstOrNull { abs(it.androidScale.toDouble() - v) < 0.005 }
        }

        fun fromIos(raw: String?): FontPreset? = values().firstOrNull { it.iosCategory == raw?.lowercase() }

        fun fromRaw(platform: Platform, raw: String?): FontPreset? = if (platform == Platform.ANDROID) fromAndroid(raw) else fromIos(raw)
    }
}

/** Parsing of command output. Pure. */
object DisplayParsers {
    private val NIGHT = Regex("Night mode:\\s*(\\S+)")

    /** `cmd uimode night` prints `Night mode: no`; returns `no`, `yes`, `auto`, `custom_schedule` or `custom_bedtime`. */
    fun nightMode(text: String): String? = NIGHT.find(text)?.groupValues?.get(1)?.lowercase()

    /** `settings get` and `getprop` print the literal `null` (or an empty line) for a value that was never set. */
    fun settingValue(text: String): String? = text.trim().takeIf { it.isNotEmpty() && it != "null" }

    /**
     * `simctl ui <udid> appearance|content_size` prints one word. The content size comes back with odd capitals
     * (`extra-Small`, `Small`), so it is lowercased. Null for `unsupported`, `unknown`, empty or a sentence (an error text).
     */
    fun simctlValue(text: String): String? =
        text.trim().lowercase().takeIf { it.isNotEmpty() && !it.contains(' ') && it != "unsupported" && it != "unknown" }

    /** simctl reports a bad value as `Invalid argument` (also `Unknown apperance: x`) but may still exit 0; returns that text, else null. */
    fun simctlError(text: String): String? =
        text.trim().takeIf { it.contains("Invalid argument", ignoreCase = true) || it.startsWith("Unknown", ignoreCase = true) }
}

/** Reads and writes the display settings of one device. Blocking; call off the EDT. */
interface DisplayBackend {
    val platform: Platform

    /** The keys this platform supports. */
    val keys: Set<DisplayKey>

    fun read(): DisplayState

    /** Writes the raw [value] (null: unset it, or the platform default). Throws with the tool's error text on failure. */
    fun write(key: DisplayKey, value: String?)
}

/** adb based backend for an Android emulator or device. */
class AndroidDisplay(private val adb: String, private val serial: String, private val runner: CommandRunner) : DisplayBackend {
    override val platform = Platform.ANDROID
    override val keys: Set<DisplayKey> = DisplayKey.values().toSet()

    /** The value goes through the device shell (adb joins the arguments into one command line), so only plain characters are allowed. */
    private val safe = Regex("[A-Za-z0-9._-]+")

    private fun shell(vararg args: String) = listOf(adb, "-s", serial, "shell", *args)

    fun readCommand(key: DisplayKey): List<String> = when (key) {
        DisplayKey.APPEARANCE -> shell("cmd", "uimode", "night")
        DisplayKey.FONT_SIZE -> shell("settings", "get", "system", "font_scale")
        DisplayKey.WINDOW_SCALE -> shell("settings", "get", "global", "window_animation_scale")
        DisplayKey.TRANSITION_SCALE -> shell("settings", "get", "global", "transition_animation_scale")
        DisplayKey.ANIMATOR_SCALE -> shell("settings", "get", "global", "animator_duration_scale")
        DisplayKey.SHOW_TOUCHES -> shell("settings", "get", "system", "show_touches")
        DisplayKey.LAYOUT_BOUNDS -> shell("getprop", "debug.layout")
        DisplayKey.POINTER_LOCATION -> shell("settings", "get", "system", "pointer_location")
        DisplayKey.DONT_KEEP_ACTIVITIES -> shell("settings", "get", "global", "always_finish_activities")
    }

    /**
     * The command that writes [value] (null: delete the setting; night mode `auto`; layout bounds empty). Layout bounds
     * (`debug.layout`) clears with an empty value, which has to travel inside one quoted shell string: adb loses an empty argument.
     */
    fun writeCommand(key: DisplayKey, value: String?): List<String> {
        require(value == null || safe.matches(value)) { "Unsupported value: $value" }
        fun setting(namespace: String, name: String) =
            if (value == null) shell("settings", "delete", namespace, name) else shell("settings", "put", namespace, name, value)
        return when (key) {
            DisplayKey.APPEARANCE -> shell("cmd", "uimode", "night", value ?: "auto")
            DisplayKey.FONT_SIZE -> setting("system", "font_scale")
            DisplayKey.WINDOW_SCALE -> setting("global", "window_animation_scale")
            DisplayKey.TRANSITION_SCALE -> setting("global", "transition_animation_scale")
            DisplayKey.ANIMATOR_SCALE -> setting("global", "animator_duration_scale")
            DisplayKey.SHOW_TOUCHES -> setting("system", "show_touches")
            DisplayKey.LAYOUT_BOUNDS -> shell("setprop debug.layout ${value ?: "''"}")
            DisplayKey.POINTER_LOCATION -> setting("system", "pointer_location")
            DisplayKey.DONT_KEEP_ACTIVITIES -> setting("global", "always_finish_activities")
        }
    }

    override fun read(): DisplayState {
        val values = LinkedHashMap<DisplayKey, String?>()
        val errors = LinkedHashMap<DisplayKey, String>()
        for (key in keys) {
            try {
                val result = runner.run(readCommand(key), TIMEOUT)
                if (!result.ok) throw CommandFailedException(errorText(result.text, result.stderr, result.exitCode))
                values[key] = if (key == DisplayKey.APPEARANCE) {
                    DisplayParsers.nightMode(result.text) ?: throw CommandFailedException("Unexpected output: ${result.text.trim().take(120)}")
                } else {
                    DisplayParsers.settingValue(result.text)
                }
            } catch (e: Exception) {
                errors[key] = e.message ?: e.javaClass.simpleName
            }
        }
        return DisplayState(values, errors)
    }

    override fun write(key: DisplayKey, value: String?) {
        val result = runner.run(writeCommand(key, value), TIMEOUT)
        if (!result.ok) throw CommandFailedException(errorText(result.text, result.stderr, result.exitCode))
    }

    private fun errorText(stdout: String, stderr: String, exit: Int): String =
        (stderr.trim().ifEmpty { stdout.trim() }.lineSequence().firstOrNull { it.isNotBlank() } ?: "adb exited with code $exit").take(200)

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}

/** `xcrun simctl ui` backend for an iOS simulator. Only appearance and font size exist there. */
class IosDisplay(private val xcrun: String, private val udid: String, private val runner: CommandRunner) : DisplayBackend {
    override val platform = Platform.IOS
    override val keys: Set<DisplayKey> = setOf(DisplayKey.APPEARANCE, DisplayKey.FONT_SIZE)

    private fun subcommand(key: DisplayKey) = if (key == DisplayKey.APPEARANCE) "appearance" else "content_size"

    fun readCommand(key: DisplayKey): List<String> = listOf(xcrun, "simctl", "ui", udid, subcommand(key))

    fun writeCommand(key: DisplayKey, value: String): List<String> = listOf(xcrun, "simctl", "ui", udid, subcommand(key), value)

    override fun read(): DisplayState {
        val values = LinkedHashMap<DisplayKey, String?>()
        val errors = LinkedHashMap<DisplayKey, String>()
        for (key in keys) {
            try {
                val result = runner.run(readCommand(key), TIMEOUT)
                val text = result.text + result.stderr
                if (!result.ok) throw CommandFailedException(text.trim().take(200).ifEmpty { "simctl exited with code ${result.exitCode}" })
                values[key] = DisplayParsers.simctlValue(result.text) ?: throw CommandFailedException("Not available: ${result.text.trim().take(120).ifEmpty { "no output" }}")
            } catch (e: Exception) {
                errors[key] = e.message ?: e.javaClass.simpleName
            }
        }
        return DisplayState(values, errors)
    }

    /** iOS has no "unset": a null value restores the default (light, content size large). */
    override fun write(key: DisplayKey, value: String?) {
        require(key in keys) { "Not supported on iOS: $key" }
        val v = value ?: DisplayDefaults.forPlatform(Platform.IOS).getValue(key) ?: error("no default")
        val result = runner.run(writeCommand(key, v), TIMEOUT)
        val text = (result.text + result.stderr).trim()
        // simctl exits 0 for a bad content size and only prints "Invalid argument".
        DisplayParsers.simctlError(text)?.let { throw CommandFailedException(it.take(200)) }
        if (!result.ok) throw CommandFailedException(text.take(200).ifEmpty { "simctl exited with code ${result.exitCode}" })
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}

/** The values the first change of this tab replaced, per device, so Reset restores exactly what was there (including "unset"). */
class DisplayBaseline {
    private val originals = HashMap<String, MutableMap<DisplayKey, String?>>()

    /** Remembers the current value of each of [keys] unless an earlier change already did. Keys that could not be read are skipped. */
    @Synchronized fun remember(deviceKey: String, current: DisplayState, keys: Collection<DisplayKey>) {
        val map = originals.getOrPut(deviceKey) { LinkedHashMap() }
        for (key in keys) if (key in current.values && key !in map) map[key] = current.values[key]
    }

    @Synchronized fun originals(deviceKey: String): Map<DisplayKey, String?> = originals[deviceKey]?.toMap() ?: emptyMap()

    @Synchronized fun clear(deviceKey: String) {
        originals.remove(deviceKey)
    }
}

/** Outcome of [DisplayActions.reset]: [failure] is the first write error (null on success), [restored] tells if remembered values were used. */
data class ResetOutcome(val failure: String?, val restored: Boolean)

/** Write orchestration of the Display tab: remember, write in order, stop at the first error; Reset clears the baseline only on success. */
class DisplayActions(val baseline: DisplayBaseline = DisplayBaseline()) {
    /** Remembers the values [changes] replace, then writes them. Returns the first error text or null. */
    fun change(deviceKey: String, backend: DisplayBackend, before: DisplayState, changes: List<Pair<DisplayKey, String?>>): String? {
        baseline.remember(deviceKey, before, changes.map { it.first })
        return write(backend, changes)
    }

    /**
     * Restores what this tab changed, or the platform defaults when nothing was remembered. Never remembers anything itself, so a failed
     * reset leaves the baseline as it was and a second Reset tries the same plan again.
     */
    fun reset(deviceKey: String, platform: Platform, backend: DisplayBackend): ResetOutcome {
        val originals = baseline.originals(deviceKey)
        val failure = write(backend, DisplayDefaults.resetPlan(platform, originals))
        if (failure == null) baseline.clear(deviceKey)
        return ResetOutcome(failure, originals.isNotEmpty())
    }

    private fun write(backend: DisplayBackend, changes: List<Pair<DisplayKey, String?>>): String? =
        changes.firstNotNullOfOrNull { (key, value) ->
            runCatching { backend.write(key, value) }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        }
}

object DisplayDefaults {
    /** Platform defaults used by Reset when this tab has not changed anything yet. Null: unset (Android). */
    fun forPlatform(platform: Platform): Map<DisplayKey, String?> =
        if (platform == Platform.ANDROID) {
            linkedMapOf(
                DisplayKey.APPEARANCE to "auto", DisplayKey.FONT_SIZE to "1.0",
                DisplayKey.WINDOW_SCALE to null, DisplayKey.TRANSITION_SCALE to null, DisplayKey.ANIMATOR_SCALE to null,
                DisplayKey.SHOW_TOUCHES to null, DisplayKey.LAYOUT_BOUNDS to null,
                DisplayKey.POINTER_LOCATION to null, DisplayKey.DONT_KEEP_ACTIVITIES to null,
            )
        } else {
            linkedMapOf(DisplayKey.APPEARANCE to "light", DisplayKey.FONT_SIZE to "large")
        }

    /** What Reset writes: the remembered originals, or the platform defaults when nothing was changed. */
    fun resetPlan(platform: Platform, originals: Map<DisplayKey, String?>): List<Pair<DisplayKey, String?>> =
        (originals.takeIf { it.isNotEmpty() } ?: forPlatform(platform)).map { it.key to it.value }
}
