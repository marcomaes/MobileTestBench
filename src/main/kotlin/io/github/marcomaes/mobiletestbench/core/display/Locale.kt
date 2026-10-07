package io.github.marcomaes.mobiletestbench.core.display

import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.exec.CommandFailedException
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import java.time.Duration
import java.time.ZoneId
import java.util.TimeZone

/** Preset choices of the Language and Time zone combos. */
object LocalePresets {
    val LANGUAGES = listOf("en-US", "nl-NL", "de-DE", "fr-FR", "es-ES", "ja-JP", "ar-SA")
    val TIME_ZONES = listOf("Europe/Amsterdam", "UTC", "America/New_York", "Asia/Tokyo", "Australia/Sydney")

    /** Combo text for a language preset; the tag itself is what is applied. */
    fun languageLabel(tag: String): String = if (tag == "ar-SA") "ar-SA (right-to-left)" else tag
}

/** Shape checks for what the user types. Pure. */
object LocaleValidation {
    // language [-script] [-region] [-variant]* [-singleton-ext]*, e.g. en, nl-NL, zh-Hans-CN, sr-Latn-RS, ar-SA-u-nu-latn.
    private val BCP47 = Regex(
        "[A-Za-z]{2,3}(-[A-Za-z]{4})?(-([A-Za-z]{2}|[0-9]{3}))?(-([A-Za-z0-9]{5,8}|[0-9][A-Za-z0-9]{3}))*(-[A-WY-Za-wy-z0-9](-[A-Za-z0-9]{2,8})+)*",
    )

    /** Null when [tag] has the shape of a BCP 47 language tag, else the reason. The device may still not know the language. */
    fun languageError(tag: String): String? = when {
        tag.isBlank() -> "Enter a language tag such as nl-NL"
        tag.contains(',') -> "Enter one language tag only (no comma); Android keeps a single system language this way"
        !BCP47.matches(tag.trim()) -> "\"${tag.trim()}\" is not a BCP 47 language tag (examples: en-US, nl-NL, zh-Hans-CN)"
        else -> null
    }

    /** Null when [id] is a known IANA time zone id (checked against the JDK's list, which has the same ids). */
    fun timeZoneError(id: String): String? {
        val t = id.trim()
        return when {
            t.isEmpty() -> "Enter an IANA time zone id such as Europe/Amsterdam"
            t != "UTC" && !t.contains('/') -> "\"$t\" is not an IANA time zone id (Area/City, for example Asia/Tokyo, or UTC)"
            t !in ZoneId.getAvailableZoneIds() -> "Unknown time zone id \"$t\""
            else -> null
        }
    }
}

/** Parsing and normalisation of command output. Pure. */
object LocaleParsers {
    private val BRACKET = Regex("\\[([^\\]]*)]")

    /** `settings get system system_locales` prints `en-US,nl-NL`, or `null` when unset. */
    fun androidLanguages(text: String): List<String> =
        DisplayParsers.settingValue(text)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    /** `cmd locale get-device-locale` prints one tag (`en-US`), or an error line. Null when it is not a tag. */
    fun deviceLocale(text: String): String? =
        text.trim().takeIf { it.isNotEmpty() && !it.contains(' ') && LocaleValidation.languageError(it) == null }

    /** `cmd locale get-app-locales <pkg>` prints `Locales for <pkg> for user 0 are [nl-NL]`; empty list when unset, null for anything else. */
    fun appLocales(text: String): List<String>? {
        val m = BRACKET.find(text) ?: return null
        return m.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The error line of a `cmd` call, which exits 0 even for bad input; null when the output looks fine. */
    fun commandError(text: String): String? =
        text.lineSequence().map { it.trim() }.firstOrNull {
            it.startsWith("Error", ignoreCase = true) || it.startsWith("Unknown", ignoreCase = true) ||
                it.contains("Exception") || it.startsWith("Invalid", ignoreCase = true)
        }?.take(200)

    /** `defaults read -g AppleLanguages` prints a plist list: `(`, quoted or bare items one per line, `)`. */
    fun iosLanguages(text: String): List<String> =
        text.lineSequence().map { it.trim().trimEnd(',').trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() && it != "(" && it != ")" }.toList()

    /** `defaults read -g AppleLocale` prints `en_NL`. */
    fun iosLocale(text: String): String? = text.trim().takeIf { it.isNotEmpty() && !it.contains(' ') }

    /** `en_US` (dumpsys, AppleLocale) to `en-US`. */
    fun toHyphen(tag: String) = tag.replace('_', '-')

    /** iOS AppleLocale form of a language tag: language and region only, joined with an underscore (`zh-Hans-CN` gives `zh_CN`). */
    fun toIosLocale(tag: String): String {
        val parts = tag.trim().split('-')
        val region = parts.drop(1).firstOrNull { it.length == 2 && it.all(Char::isLetter) || it.length == 3 && it.all(Char::isDigit) }
        return if (region == null) parts[0].lowercase() else parts[0].lowercase() + "_" + region.uppercase()
    }

    /** The list for `AppleLanguages`: [tag] first, then the existing languages without a duplicate of it. */
    fun iosLanguageList(tag: String, existing: List<String>): List<String> =
        listOf(tag.trim()) + existing.filter { !it.equals(tag.trim(), ignoreCase = true) }

    /** A plist array literal for `defaults write`, for example `("fr-FR","en-NL")`. */
    fun plistArray(items: List<String>): String = items.joinToString(",", "(", ")") { "\"$it\"" }
}

/** What a locale read found. [timeZone] is the device's id (iOS: the Mac's, see [timeZoneFollowsHost]). Errors are per part. */
data class LocaleState(
    val languages: List<String>?,
    val appleLocale: String?,
    val timeZone: String?,
    val languageError: String? = null,
    val timeZoneError: String? = null,
    val timeZoneFollowsHost: Boolean = false,
) {
    val primary: String? get() = languages?.firstOrNull()
}

/** Reads and writes language and time zone of one device. Blocking; call off the EDT. Writes verify by reading back and throw on mismatch. */
interface LocaleBackend {
    val platform: Platform
    val canSetTimeZone: Boolean

    fun read(): LocaleState

    /** Sets the system language to [tag]. Throws with the tool's text (or the mismatch) on failure. */
    fun setLanguage(tag: String, current: LocaleState)

    fun setTimeZone(id: String)

    /** Puts the language back to what [baseline] captured. */
    fun restoreLanguage(baseline: LocaleBaseline.Entry)
}

/** adb based backend (Android 17 `cmd locale`, `cmd alarm`; older Android versions are unverified). */
class AndroidLocale(private val adb: String, private val serial: String, private val runner: CommandRunner) : LocaleBackend {
    override val platform = Platform.ANDROID
    override val canSetTimeZone = true

    private fun shell(vararg args: String) = listOf(adb, "-s", serial, "shell", *args)

    fun readLanguagesCommand() = shell("settings", "get", "system", "system_locales")
    fun readDeviceLocaleCommand() = shell("cmd", "locale", "get-device-locale")
    fun readTimeZoneCommand() = shell("getprop", "persist.sys.timezone")
    fun setLanguageCommand(tag: String) = shell("cmd", "locale", "set-device-locale", tag)
    fun setTimeZoneCommand(id: String) = shell("cmd", "alarm", "set-timezone", id)

    private fun text(command: List<String>): String {
        val r = runner.run(command, TIMEOUT)
        val all = r.text + r.stderr
        if (!r.ok) throw CommandFailedException(all.trim().lineSequence().firstOrNull { it.isNotBlank() }?.take(200) ?: "adb exited with code ${r.exitCode}")
        return r.text
    }

    override fun read(): LocaleState {
        var languages: List<String>? = null
        var langErr: String? = null
        try {
            languages = LocaleParsers.androidLanguages(text(readLanguagesCommand()))
            if (languages.isEmpty()) {
                // Unset list: fall back to the primary locale (Android 17 only).
                languages = LocaleParsers.deviceLocale(runCatching { text(readDeviceLocaleCommand()) }.getOrDefault(""))?.let { listOf(it) } ?: emptyList()
            }
        } catch (e: Exception) {
            langErr = e.message ?: e.javaClass.simpleName
        }
        var tz: String? = null
        var tzErr: String? = null
        try {
            tz = DisplayParsers.settingValue(text(readTimeZoneCommand()))
        } catch (e: Exception) {
            tzErr = e.message ?: e.javaClass.simpleName
        }
        return LocaleState(languages, null, tz, langErr, tzErr)
    }

    override fun setLanguage(tag: String, current: LocaleState) {
        require(LocaleValidation.languageError(tag) == null) { LocaleValidation.languageError(tag)!! }
        val r = runner.run(setLanguageCommand(tag.trim()), TIMEOUT)
        val out = (r.text + r.stderr).trim()
        // The cmd tool exits 0 for bad input, so the text is checked as well.
        LocaleParsers.commandError(out)?.let { throw CommandFailedException(it) }
        if (!r.ok) throw CommandFailedException(out.take(200).ifEmpty { "adb exited with code ${r.exitCode}" })
        val now = LocaleParsers.deviceLocale(text(readDeviceLocaleCommand()))
        if (now == null || !now.equals(tag.trim(), ignoreCase = true)) {
            throw CommandFailedException("The device reports ${now ?: "no locale"} instead of ${tag.trim()} (the device may not know that language)")
        }
    }

    override fun setTimeZone(id: String) {
        require(LocaleValidation.timeZoneError(id) == null) { LocaleValidation.timeZoneError(id)!! }
        val r = runner.run(setTimeZoneCommand(id.trim()), TIMEOUT)
        val out = (r.text + r.stderr).trim()
        LocaleParsers.commandError(out)?.let { throw CommandFailedException(it) }
        if (!r.ok) throw CommandFailedException(out.take(200).ifEmpty { "adb exited with code ${r.exitCode}" })
        // An id the device does not know is ignored silently, so read it back.
        val now = DisplayParsers.settingValue(text(readTimeZoneCommand()))
        if (now != id.trim()) throw CommandFailedException("The device reports ${now ?: "no time zone"} instead of ${id.trim()}")
    }

    /** Only the first language comes back: `set-device-locale` takes one tag and replaces the whole list. */
    override fun restoreLanguage(baseline: LocaleBaseline.Entry) {
        val first = baseline.languages?.firstOrNull() ?: return
        setLanguage(first, LocaleState(null, null, null))
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}

/** iOS simulator backend: `simctl spawn <udid> defaults` on the global domain. The time zone is the Mac's and cannot be set. */
class IosLocale(private val xcrun: String, private val udid: String, private val runner: CommandRunner, private val hostZone: () -> String = { TimeZone.getDefault().id }) : LocaleBackend {
    override val platform = Platform.IOS
    override val canSetTimeZone = false

    private fun defaults(vararg args: String) = listOf(xcrun, "simctl", "spawn", udid, "defaults", *args)

    fun readCommand(key: String) = defaults("read", "-g", key)
    fun writeLanguagesCommand(list: List<String>) = defaults("write", "-g", "AppleLanguages", LocaleParsers.plistArray(list))
    fun writeLocaleCommand(locale: String) = defaults("write", "-g", "AppleLocale", locale)
    fun deleteCommand(key: String) = defaults("delete", "-g", key)

    /** Null for a key that does not exist (defaults exits non zero with "does not exist"). */
    private fun readKey(key: String): String? {
        val r = runner.run(readCommand(key), TIMEOUT)
        val all = r.text + r.stderr
        if (!r.ok) {
            if (all.contains("does not exist")) return null
            throw CommandFailedException(all.trim().take(200).ifEmpty { "defaults exited with code ${r.exitCode}" })
        }
        return r.text
    }

    private fun run(command: List<String>) {
        val r = runner.run(command, TIMEOUT)
        if (!r.ok) throw CommandFailedException((r.text + r.stderr).trim().take(200).ifEmpty { "defaults exited with code ${r.exitCode}" })
    }

    override fun read(): LocaleState {
        var languages: List<String>? = null
        var locale: String? = null
        var err: String? = null
        try {
            languages = readKey("AppleLanguages")?.let { LocaleParsers.iosLanguages(it) } ?: emptyList()
            locale = readKey("AppleLocale")?.let { LocaleParsers.iosLocale(it) }
        } catch (e: Exception) {
            err = e.message ?: e.javaClass.simpleName
        }
        return LocaleState(languages, locale, hostZone(), err, null, timeZoneFollowsHost = true)
    }

    override fun setLanguage(tag: String, current: LocaleState) {
        require(LocaleValidation.languageError(tag) == null) { LocaleValidation.languageError(tag)!! }
        val list = LocaleParsers.iosLanguageList(tag, current.languages ?: emptyList())
        run(writeLanguagesCommand(list))
        run(writeLocaleCommand(LocaleParsers.toIosLocale(tag)))
        val back = LocaleParsers.iosLanguages(readKey("AppleLanguages") ?: "")
        if (back.firstOrNull() != tag.trim()) throw CommandFailedException("The simulator reports ${back.firstOrNull() ?: "no language"} instead of ${tag.trim()}")
    }

    override fun setTimeZone(id: String) = throw UnsupportedOperationException("The iOS simulator follows the time zone of the Mac")

    /** Writes the original strings back, or deletes the key when it did not exist. */
    override fun restoreLanguage(baseline: LocaleBaseline.Entry) {
        val languages = baseline.languages
        if (languages.isNullOrEmpty()) run(deleteCommand("AppleLanguages")) else run(writeLanguagesCommand(languages))
        val locale = baseline.appleLocale
        if (locale == null) run(deleteCommand("AppleLocale")) else run(writeLocaleCommand(locale))
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}

/** What the first language / time zone change per device replaced, so Reset can put it back. */
class LocaleBaseline {
    data class Entry(
        val languages: List<String>? = null,
        val appleLocale: String? = null,
        val languageChanged: Boolean = false,
        val timeZone: String? = null,
        val timeZoneChanged: Boolean = false,
    )

    private val entries = HashMap<String, Entry>()

    @Synchronized fun rememberLanguage(deviceKey: String, current: LocaleState) {
        val e = entries[deviceKey] ?: Entry()
        if (!e.languageChanged) entries[deviceKey] = e.copy(languages = current.languages, appleLocale = current.appleLocale, languageChanged = true)
    }

    @Synchronized fun rememberTimeZone(deviceKey: String, current: LocaleState) {
        val e = entries[deviceKey] ?: Entry()
        if (!e.timeZoneChanged) entries[deviceKey] = e.copy(timeZone = current.timeZone, timeZoneChanged = true)
    }

    @Synchronized fun entry(deviceKey: String): Entry? = entries[deviceKey]

    @Synchronized fun clearLanguage(deviceKey: String) {
        entries[deviceKey]?.let { entries[deviceKey] = it.copy(languageChanged = false, languages = null, appleLocale = null) }
    }

    @Synchronized fun clearTimeZone(deviceKey: String) {
        entries[deviceKey]?.let { entries[deviceKey] = it.copy(timeZoneChanged = false, timeZone = null) }
    }
}

/** Outcome of [LocaleActions.reset]: [failure] is the first error, [note] a caveat to show (Android language list), [restored] whether anything was changed. */
data class LocaleResetOutcome(val failure: String?, val note: String?, val restored: Boolean)

/** Orchestration of the language and time zone controls: baseline, write, reset. */
class LocaleActions(val baseline: LocaleBaseline = LocaleBaseline()) {
    /** Text for the confirm dialog before an Android system language change, null when nothing would be lost. */
    fun androidListLossWarning(current: LocaleState, newTag: String): String? {
        val langs = current.languages ?: return null
        if (langs.size < 2) return null
        return "Android keeps only ONE system language this way. Your list (${langs.joinToString(", ")}) will be replaced by $newTag, " +
            "and Reset to defaults can only bring back ${langs.first()}. Re-add the others in Settings > System > Languages."
    }

    fun changeLanguage(deviceKey: String, backend: LocaleBackend, before: LocaleState, tag: String): String? {
        val had = baseline.entry(deviceKey)?.languageChanged == true
        baseline.rememberLanguage(deviceKey, before)
        val failure = fail { backend.setLanguage(tag, before) }
        // A failed write that left the device as it was must not leave a baseline behind (Reset would "restore" and collapse the list).
        if (failure != null && !had) {
            val now = runCatching { backend.read() }.getOrNull()
            if (now != null && now.languages == before.languages && now.appleLocale == before.appleLocale) baseline.clearLanguage(deviceKey)
        }
        return failure
    }

    fun changeTimeZone(deviceKey: String, backend: LocaleBackend, before: LocaleState, id: String): String? {
        if (!backend.canSetTimeZone) return "The ${backend.platform.name} device cannot change its time zone"
        val had = baseline.entry(deviceKey)?.timeZoneChanged == true
        baseline.rememberTimeZone(deviceKey, before)
        val failure = fail { backend.setTimeZone(id) }
        if (failure != null && !had) {
            val now = runCatching { backend.read() }.getOrNull()
            if (now != null && now.timeZone == before.timeZone) baseline.clearTimeZone(deviceKey)
        }
        return failure
    }

    /** Restores what the tab changed. A failure leaves that part's baseline in place so a second Reset tries again. */
    fun reset(deviceKey: String, backend: LocaleBackend): LocaleResetOutcome {
        val e = baseline.entry(deviceKey) ?: return LocaleResetOutcome(null, null, false)
        var note: String? = null
        if (e.languageChanged) {
            val failure = fail { backend.restoreLanguage(e) }
            if (failure != null) return LocaleResetOutcome(failure, null, true)
            baseline.clearLanguage(deviceKey)
            val langs = e.languages
            if (backend.platform == Platform.ANDROID && langs != null && langs.size > 1) {
                note = "Language restored to ${langs.first()} only; re-add ${langs.drop(1).joinToString(", ")} in Settings > System > Languages."
            }
        }
        if (e.timeZoneChanged && e.timeZone != null) {
            val failure = fail { backend.setTimeZone(e.timeZone) }
            if (failure != null) return LocaleResetOutcome(failure, note, true)
            baseline.clearTimeZone(deviceKey)
        }
        return LocaleResetOutcome(null, note, e.languageChanged || e.timeZoneChanged)
    }

    private fun fail(block: () -> Unit): String? =
        runCatching(block).exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
}
