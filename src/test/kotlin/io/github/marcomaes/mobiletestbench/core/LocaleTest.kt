package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.display.AndroidLocale
import io.github.marcomaes.mobiletestbench.core.display.IosLocale
import io.github.marcomaes.mobiletestbench.core.display.LocaleActions
import io.github.marcomaes.mobiletestbench.core.display.LocaleParsers
import io.github.marcomaes.mobiletestbench.core.display.LocalePresets
import io.github.marcomaes.mobiletestbench.core.display.LocaleState
import io.github.marcomaes.mobiletestbench.core.display.LocaleValidation
import io.github.marcomaes.mobiletestbench.core.exec.CommandResult
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class LocaleTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()
    private fun ok(text: String) = CommandResult(0, text.toByteArray(), "")

    private class FakeRunner(val answer: (List<String>) -> CommandResult) : CommandRunner(emptyMap()) {
        val commands = mutableListOf<List<String>>()
        override fun run(command: List<String>, timeout: Duration): CommandResult {
            commands += command
            return answer(command)
        }
    }

    @Test fun validatesLanguageTags() {
        for (t in LocalePresets.LANGUAGES + listOf("en", "zh-Hans-CN", "sr-Latn-RS", "es-419", "ar-SA-u-nu-latn")) assertNull(t, LocaleValidation.languageError(t))
        for (t in listOf("", "  ", "en-US,nl-NL", "bad tag!!", "e", "english", "en_US", "en-")) assertNotNull(t, LocaleValidation.languageError(t))
    }

    @Test fun validatesTimeZoneIds() {
        for (t in LocalePresets.TIME_ZONES) assertNull(t, LocaleValidation.timeZoneError(t))
        for (t in listOf("", "Not/AZone", "Tokyo", "europe/amsterdam x")) assertNotNull(t, LocaleValidation.timeZoneError(t))
    }

    @Test fun parsesRealOutput() {
        assertEquals(listOf("en-US", "nl-NL"), LocaleParsers.androidLanguages("en-US,nl-NL\n"))
        assertEquals(emptyList<String>(), LocaleParsers.androidLanguages("null\n"))
        assertEquals("en-US", LocaleParsers.deviceLocale(fixture("android_get_device_locale.txt")))
        assertNull(LocaleParsers.deviceLocale(fixture("android_set_device_locale_invalid.txt")))
        assertEquals(listOf("nl-NL"), LocaleParsers.appLocales(fixture("android_get_app_locales.txt")))
        assertEquals(emptyList<String>(), LocaleParsers.appLocales(fixture("android_get_app_locales_empty.txt")))
        assertNull(LocaleParsers.appLocales(fixture("android_set_app_locales_unknown_package.txt")))
        assertEquals(listOf("en-NL", "nl-NL"), LocaleParsers.iosLanguages(fixture("ios_defaults_read_AppleLanguages.txt")))
        assertEquals("en_NL", LocaleParsers.iosLocale(fixture("ios_defaults_read_AppleLocale.txt")))
        assertEquals("Error: Invalid locale tag: en-US,nl-NL", LocaleParsers.commandError(fixture("android_set_device_locale_invalid.txt")))
        assertNull(LocaleParsers.commandError(""))
    }

    @Test fun normalisesAndBuildsIosValues() {
        assertEquals("en-US", LocaleParsers.toHyphen("en_US"))
        assertEquals("fr_FR", LocaleParsers.toIosLocale("fr-FR"))
        assertEquals("zh_CN", LocaleParsers.toIosLocale("zh-Hans-CN"))
        assertEquals("nl", LocaleParsers.toIosLocale("nl"))
        assertEquals(listOf("fr-FR", "en-NL", "nl-NL"), LocaleParsers.iosLanguageList("fr-FR", listOf("en-NL", "nl-NL")))
        assertEquals(listOf("nl-NL", "en-NL"), LocaleParsers.iosLanguageList("nl-NL", listOf("en-NL", "nl-NL")))
        assertEquals("(\"fr-FR\",\"en-NL\")", LocaleParsers.plistArray(listOf("fr-FR", "en-NL")))
    }

    /** A tiny fake Android: state in variables, commands update them like the real tools. */
    private fun android(): Pair<AndroidLocale, FakeRunner> {
        var langs = "en-US,nl-NL"
        var tz = "Europe/Amsterdam"
        val r = FakeRunner { c ->
            val s = c.drop(4)
            when {
                s == listOf("settings", "get", "system", "system_locales") -> ok(langs + "\n")
                s == listOf("cmd", "locale", "get-device-locale") -> ok(langs.substringBefore(',') + "\n")
                s.take(3) == listOf("cmd", "locale", "set-device-locale") ->
                    if (s[3].contains(',')) ok("Error: Invalid locale tag: ${s[3]}\n") else { langs = s[3]; ok("") }
                s == listOf("getprop", "persist.sys.timezone") -> ok(tz + "\n")
                s.take(3) == listOf("cmd", "alarm", "set-timezone") -> { if (s[3] != "Not/AZone") tz = s[3]; ok("") }
                else -> CommandResult(1, ByteArray(0), "unexpected $c")
            }
        }
        return AndroidLocale("adb", "emulator-5554", r) to r
    }

    @Test fun androidReadsLanguageListAndZone() {
        val st = android().first.read()
        assertEquals(listOf("en-US", "nl-NL"), st.languages)
        assertEquals("en-US", st.primary)
        assertEquals("Europe/Amsterdam", st.timeZone)
    }

    @Test fun androidChangeAndResetRestoresFirstLanguageAndZone() {
        val (b, runner) = android()
        val actions = LocaleActions()
        val before = b.read()
        assertTrue(actions.androidListLossWarning(before, "fr-FR")!!.contains("en-US, nl-NL"))
        assertNull(actions.changeLanguage("d", b, before, "fr-FR"))
        assertNull(actions.changeTimeZone("d", b, before, "Asia/Tokyo"))
        assertEquals(listOf("fr-FR"), b.read().languages)
        assertEquals("Asia/Tokyo", b.read().timeZone)
        // a second change keeps the first baseline
        assertNull(actions.changeLanguage("d", b, b.read(), "de-DE"))
        val outcome = actions.reset("d", b)
        assertNull(outcome.failure)
        assertTrue(outcome.note!!.contains("nl-NL"))
        assertEquals(listOf("en-US"), b.read().languages)
        assertEquals("Europe/Amsterdam", b.read().timeZone)
        assertTrue(runner.commands.any { it.takeLast(4) == listOf("cmd", "locale", "set-device-locale", "fr-FR") })
        assertEquals(false, actions.reset("d", b).restored)
    }

    /** Android fake whose tools exit 0 but ignore (or reject by text / exit code) chosen valid inputs. */
    private fun badAndroid(mode: String): AndroidLocale {
        val langs = "en-US,nl-NL"
        val tz = "Europe/Amsterdam"
        val r = FakeRunner { c ->
            val s = c.drop(4)
            when {
                s == listOf("settings", "get", "system", "system_locales") -> ok(langs + "\n")
                s == listOf("cmd", "locale", "get-device-locale") -> ok("en-US\n")
                s.take(3) == listOf("cmd", "locale", "set-device-locale") -> when (mode) {
                    "text" -> ok("Error: Invalid locale tag\n")
                    "exit" -> CommandResult(3, ByteArray(0), "boom")
                    else -> ok("") // silently ignored
                }
                s == listOf("getprop", "persist.sys.timezone") -> ok(tz + "\n")
                s.take(3) == listOf("cmd", "alarm", "set-timezone") -> ok("") // ignored
                else -> CommandResult(1, ByteArray(0), "unexpected $c")
            }
        }
        return AndroidLocale("adb", "emulator-5554", r)
    }

    @Test fun androidRejectsInvalidInputBeforeAnyCommand() {
        val (b, _) = android()
        val st = b.read()
        assertEquals("Unknown time zone id \"Not/AZone\"", runCatching { b.setTimeZone("Not/AZone") }.exceptionOrNull()?.message)
        assertTrue(LocaleActions().changeLanguage("d", b, st, "en-US,nl-NL")!!.contains("one language tag"))
    }

    @Test fun androidReportsSilentFailuresByReadBack() {
        val st = badAndroid("silent").read()
        val tz = runCatching { badAndroid("silent").setTimeZone("Australia/Sydney") }.exceptionOrNull()
        assertEquals("The device reports Europe/Amsterdam instead of Australia/Sydney", tz?.message)
        val lang = runCatching { badAndroid("silent").setLanguage("fr-FR", st) }.exceptionOrNull()
        assertTrue(lang!!.message!!.contains("device reports en-US instead of fr-FR"))
    }

    @Test fun androidReportsErrorTextAndExitCode() {
        val st = badAndroid("text").read()
        assertTrue(runCatching { badAndroid("text").setLanguage("fr-FR", st) }.exceptionOrNull()!!.message!!.contains("Invalid locale tag"))
        assertTrue(runCatching { badAndroid("exit").setLanguage("fr-FR", st) }.exceptionOrNull()!!.message!!.contains("boom"))
    }

    @Test fun failedWriteLeavesNoBaselineWhenDeviceUnchanged() {
        val b = badAndroid("silent")
        val before = b.read()
        val actions = LocaleActions()
        assertNotNull(actions.changeLanguage("d", b, before, "fr-FR"))
        assertNotNull(actions.changeTimeZone("d", b, before, "Australia/Sydney"))
        assertEquals(false, actions.baseline.entry("d")?.languageChanged ?: false)
        assertEquals(false, actions.baseline.entry("d")?.timeZoneChanged ?: false)
        assertEquals(false, actions.reset("d", b).restored)
    }

    @Test fun failedWriteKeepsEarlierBaseline() {
        val (b, _) = android()
        val actions = LocaleActions()
        assertNull(actions.changeLanguage("d", b, b.read(), "fr-FR"))
        // second change fails (comma rejected) but the device did change earlier: baseline must stay
        assertNotNull(actions.changeLanguage("d", b, b.read(), "a,b"))
        assertEquals(true, actions.baseline.entry("d")!!.languageChanged)
    }

    @Test fun resetKeepsBaselineAfterFailureAndRetries() {
        var fail = true
        var langs = "en-US,nl-NL"
        val r = FakeRunner { c ->
            val s = c.drop(4)
            when {
                s == listOf("settings", "get", "system", "system_locales") -> ok(langs + "\n")
                s == listOf("cmd", "locale", "get-device-locale") -> ok(langs.substringBefore(',') + "\n")
                s.take(3) == listOf("cmd", "locale", "set-device-locale") ->
                    if (fail && s[3] == "en-US") CommandResult(1, ByteArray(0), "adb offline") else { langs = s[3]; ok("") }
                else -> CommandResult(1, ByteArray(0), "unexpected $c")
            }
        }
        val b = AndroidLocale("adb", "e", r)
        val actions = LocaleActions()
        assertNull(actions.changeLanguage("d", b, b.read(), "fr-FR"))
        assertEquals("adb offline", actions.reset("d", b).failure)
        assertEquals(true, actions.baseline.entry("d")!!.languageChanged)
        fail = false
        val second = actions.reset("d", b)
        assertNull(second.failure)
        assertEquals(listOf("en-US"), b.read().languages)
    }

    @Test fun iosSetLanguageDetectsReadBackMismatch() {
        val r = FakeRunner { c ->
            val s = c.drop(5)
            when {
                s == listOf("read", "-g", "AppleLanguages") -> ok("(\n    \"en-NL\"\n)\n")
                s == listOf("read", "-g", "AppleLocale") -> ok("en_NL\n")
                s.take(2) == listOf("write", "-g") -> ok("")
                else -> CommandResult(1, ByteArray(0), "unexpected $c")
            }
        }
        val b = IosLocale("xcrun", "UDID", r) { "UTC" }
        val err = runCatching { b.setLanguage("fr-FR", b.read()) }.exceptionOrNull()
        assertEquals("The simulator reports en-NL instead of fr-FR", err?.message)
    }

    @Test fun androidSingleLanguageNeedsNoWarning() {
        assertNull(LocaleActions().androidListLossWarning(LocaleState(listOf("en-US"), null, "UTC"), "nl-NL"))
    }

    private fun ios(initial: List<String>?, locale: String?): Triple<IosLocale, FakeRunner, () -> Pair<List<String>?, String?>> {
        var langs = initial
        var loc = locale
        val r = FakeRunner { c ->
            val s = c.drop(5) // xcrun simctl spawn udid defaults
            when {
                s == listOf("read", "-g", "AppleLanguages") ->
                    langs?.let { l -> ok(l.joinToString(",\n", "(\n", "\n)\n") { "    \"$it\"" }) } ?: CommandResult(1, ByteArray(0), "The domain/default pair of (kCFPreferencesAnyApplication, AppleLanguages) does not exist")
                s == listOf("read", "-g", "AppleLocale") -> loc?.let { ok(it + "\n") } ?: CommandResult(1, ByteArray(0), "does not exist")
                s.take(3) == listOf("write", "-g", "AppleLanguages") -> { langs = LocaleParsers.iosLanguages(s[3].trim('(', ')').replace(",", "\n")); ok("") }
                s.take(3) == listOf("write", "-g", "AppleLocale") -> { loc = s[3]; ok("") }
                s == listOf("delete", "-g", "AppleLanguages") -> { langs = null; ok("") }
                s == listOf("delete", "-g", "AppleLocale") -> { loc = null; ok("") }
                else -> CommandResult(1, ByteArray(0), "unexpected $c")
            }
        }
        return Triple(IosLocale("xcrun", "UDID", r) { "Europe/Amsterdam" }, r) { langs to loc }
    }

    @Test fun iosReadsHostZoneAndLanguages() {
        val st = ios(listOf("en-NL", "nl-NL"), "en_NL").first.read()
        assertEquals(listOf("en-NL", "nl-NL"), st.languages)
        assertEquals("en_NL", st.appleLocale)
        assertEquals("Europe/Amsterdam", st.timeZone)
        assertTrue(st.timeZoneFollowsHost)
    }

    @Test fun iosChangeKeepsOtherLanguagesAndResetRestoresExactly() {
        val (b, runner, now) = ios(listOf("en-NL", "nl-NL"), "en_NL")
        val actions = LocaleActions()
        val before = b.read()
        assertNull(actions.changeLanguage("d", b, before, "fr-FR"))
        assertEquals(listOf("fr-FR", "en-NL", "nl-NL") to "fr_FR", now())
        assertTrue(runner.commands.any { it.last() == "(\"fr-FR\",\"en-NL\",\"nl-NL\")" })
        assertTrue(actions.changeTimeZone("d", b, before, "UTC")!!.contains("cannot change"))
        val outcome = actions.reset("d", b)
        assertNull(outcome.failure)
        assertNull(outcome.note)
        assertEquals(listOf("en-NL", "nl-NL") to "en_NL", now())
    }

    @Test fun iosResetDeletesKeysThatDidNotExist() {
        val (b, _, now) = ios(null, null)
        val actions = LocaleActions()
        assertEquals(emptyList<String>(), b.read().languages)
        assertNull(actions.changeLanguage("d", b, b.read(), "de-DE"))
        assertEquals(listOf("de-DE") to "de_DE", now())
        assertNull(actions.reset("d", b).failure)
        assertEquals(null to null, now())
    }
}
