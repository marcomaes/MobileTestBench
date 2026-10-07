package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.display.AndroidDisplay
import io.github.marcomaes.mobiletestbench.core.display.Appearance
import io.github.marcomaes.mobiletestbench.core.display.DisplayActions
import io.github.marcomaes.mobiletestbench.core.display.DisplayBackend
import io.github.marcomaes.mobiletestbench.core.display.DisplayBaseline
import io.github.marcomaes.mobiletestbench.core.display.DisplayDefaults
import io.github.marcomaes.mobiletestbench.core.display.DisplayKey
import io.github.marcomaes.mobiletestbench.core.display.DisplayParsers
import io.github.marcomaes.mobiletestbench.core.display.DisplayState
import io.github.marcomaes.mobiletestbench.core.display.FontPreset
import io.github.marcomaes.mobiletestbench.core.display.IosDisplay
import io.github.marcomaes.mobiletestbench.core.exec.CommandResult
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Duration

class DisplayTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    /** Answers by the last words of the command; records every command. */
    private class FakeRunner(val answer: (List<String>) -> CommandResult) : CommandRunner(emptyMap()) {
        val commands = mutableListOf<List<String>>()
        override fun run(command: List<String>, timeout: Duration): CommandResult {
            commands += command
            return answer(command)
        }
    }

    private fun ok(text: String) = CommandResult(0, text.toByteArray(), "")

    @Test fun parsesNightMode() {
        assertEquals("no", DisplayParsers.nightMode(fixture("android-uimode-night-no.txt")))
        assertEquals("auto", DisplayParsers.nightMode(fixture("android-uimode-night-auto.txt")))
        assertEquals("custom_schedule", DisplayParsers.nightMode("Night mode: custom_schedule\n"))
        assertNull(DisplayParsers.nightMode(fixture("android-uimode-night-error.txt")))
    }

    @Test fun unsetSettingsReadAsNull() {
        assertNull(DisplayParsers.settingValue("null\n"))
        assertNull(DisplayParsers.settingValue("\n"))
        assertNull(DisplayParsers.settingValue(""))
        assertEquals("1.3", DisplayParsers.settingValue("1.3\n"))
    }

    @Test fun simctlValuesAreLowercasedAndErrorsDetected() {
        assertEquals("extra-small", DisplayParsers.simctlValue(fixture("simctl-content-size-extra-small.txt")))
        assertEquals("small", DisplayParsers.simctlValue("Small\n"))
        assertNull(DisplayParsers.simctlValue("unsupported"))
        assertNull(DisplayParsers.simctlValue("Invalid argument"))
        assertEquals("Invalid argument", DisplayParsers.simctlError("Invalid argument\n"))
        assertEquals("Unknown apperance: bogus", DisplayParsers.simctlError("Unknown apperance: bogus"))
        assertNull(DisplayParsers.simctlError("large\n"))
    }

    @Test fun fontPresetMapping() {
        assertEquals(FontPreset.DEFAULT, FontPreset.fromAndroid("1.0"))
        assertEquals(FontPreset.DEFAULT, FontPreset.fromAndroid(null))
        assertEquals(FontPreset.SMALL, FontPreset.fromAndroid("0.85"))
        assertEquals(FontPreset.LARGE, FontPreset.fromAndroid("1.15"))
        assertEquals(FontPreset.LARGEST, FontPreset.fromAndroid("1.3"))
        assertNull(FontPreset.fromAndroid("1.5"))
        assertNull(FontPreset.fromAndroid("abc"))
        assertEquals(FontPreset.DEFAULT, FontPreset.fromIos("large"))
        assertEquals(FontPreset.LARGEST, FontPreset.fromIos("accessibility-extra-extra-extra-large"))
        assertNull(FontPreset.fromIos("medium"))
        assertEquals("1.15", FontPreset.LARGE.raw(Platform.ANDROID))
        assertEquals("extra-extra-large", FontPreset.LARGE.raw(Platform.IOS))
    }

    @Test fun appearanceMapping() {
        assertEquals(listOf(Appearance.AUTO, Appearance.LIGHT, Appearance.DARK), Appearance.forPlatform(Platform.ANDROID))
        assertEquals(listOf(Appearance.LIGHT, Appearance.DARK), Appearance.forPlatform(Platform.IOS))
        assertEquals("yes", Appearance.DARK.raw(Platform.ANDROID))
        assertEquals("no", Appearance.LIGHT.raw(Platform.ANDROID))
        assertEquals("dark", Appearance.DARK.raw(Platform.IOS))
        assertEquals(Appearance.LIGHT, Appearance.fromRaw(Platform.ANDROID, "no"))
        assertEquals(Appearance.AUTO, Appearance.fromRaw(Platform.ANDROID, "auto"))
        assertNull(Appearance.fromRaw(Platform.ANDROID, "custom_schedule"))
        assertEquals(Appearance.DARK, Appearance.fromRaw(Platform.IOS, "dark"))
    }

    @Test fun androidWriteCommands() {
        val d = AndroidDisplay("adb", "emulator-5554", FakeRunner { ok("") })
        val pre = listOf("adb", "-s", "emulator-5554", "shell")
        assertEquals(pre + listOf("cmd", "uimode", "night", "yes"), d.writeCommand(DisplayKey.APPEARANCE, "yes"))
        assertEquals(pre + listOf("cmd", "uimode", "night", "auto"), d.writeCommand(DisplayKey.APPEARANCE, null))
        assertEquals(pre + listOf("settings", "put", "system", "font_scale", "1.3"), d.writeCommand(DisplayKey.FONT_SIZE, "1.3"))
        assertEquals(pre + listOf("settings", "put", "global", "window_animation_scale", "0"), d.writeCommand(DisplayKey.WINDOW_SCALE, "0"))
        assertEquals(pre + listOf("settings", "delete", "global", "animator_duration_scale"), d.writeCommand(DisplayKey.ANIMATOR_SCALE, null))
        assertEquals(pre + listOf("settings", "put", "system", "show_touches", "1"), d.writeCommand(DisplayKey.SHOW_TOUCHES, "1"))
        assertEquals(pre + listOf("settings", "delete", "system", "show_touches"), d.writeCommand(DisplayKey.SHOW_TOUCHES, null))
        assertEquals(pre + listOf("setprop debug.layout true"), d.writeCommand(DisplayKey.LAYOUT_BOUNDS, "true"))
        // The empty value must be inside one shell string, adb drops an empty argument.
        assertEquals(pre + listOf("setprop debug.layout ''"), d.writeCommand(DisplayKey.LAYOUT_BOUNDS, null))
    }

    @Test fun androidRejectsValuesThatCouldReachTheShell() {
        val d = AndroidDisplay("adb", "s", FakeRunner { ok("") })
        for (bad in listOf("1; reboot", "a b", "", "\$(id)", "x'y")) {
            try { d.writeCommand(DisplayKey.FONT_SIZE, bad); fail("accepted: $bad") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun androidReadCollectsRawValuesAndErrors() {
        val runner = FakeRunner { cmd ->
            val line = cmd.drop(4).joinToString(" ")
            when {
                line == "cmd uimode night" -> ok("Night mode: no\n")
                line.endsWith("font_scale") -> ok("1.0\n")
                line.endsWith("animator_duration_scale") -> ok("null\n")
                line.endsWith("window_animation_scale") || line.endsWith("transition_animation_scale") -> ok("1.0\n")
                line.endsWith("show_touches") -> CommandResult(255, ByteArray(0), "boom\nsecond line")
                line == "getprop debug.layout" -> ok("\n")
                line.endsWith("pointer_location") -> ok("1\n")
                line.endsWith("always_finish_activities") -> ok("null\n")
                else -> error(line)
            }
        }
        val st = AndroidDisplay("adb", "s", runner).read()
        assertEquals("no", st.values[DisplayKey.APPEARANCE])
        assertEquals("1.0", st.values[DisplayKey.FONT_SIZE])
        assertTrue(st.has(DisplayKey.ANIMATOR_SCALE))
        assertNull(st.values[DisplayKey.ANIMATOR_SCALE])
        assertNull(st.values[DisplayKey.LAYOUT_BOUNDS])
        assertFalse(st.has(DisplayKey.SHOW_TOUCHES))
        assertEquals("boom", st.errors[DisplayKey.SHOW_TOUCHES])
        assertEquals(false, st.animationsOff)
        assertNull(st.showTouches)
        assertEquals(false, st.layoutBounds)
        assertEquals(true, st.pointerLocation)
        assertEquals(false, st.dontKeepActivities)
        assertTrue(st.errors.size == 1)
    }

    @Test fun androidPointerLocationAndDontKeepActivitiesCommands() {
        val d = AndroidDisplay("adb", "s", FakeRunner { ok("") })
        val pre = listOf("adb", "-s", "s", "shell")
        assertEquals(pre + listOf("settings", "get", "system", "pointer_location"), d.readCommand(DisplayKey.POINTER_LOCATION))
        assertEquals(pre + listOf("settings", "get", "global", "always_finish_activities"), d.readCommand(DisplayKey.DONT_KEEP_ACTIVITIES))
        assertEquals(pre + listOf("settings", "put", "system", "pointer_location", "1"), d.writeCommand(DisplayKey.POINTER_LOCATION, "1"))
        assertEquals(pre + listOf("settings", "put", "global", "always_finish_activities", "0"), d.writeCommand(DisplayKey.DONT_KEEP_ACTIVITIES, "0"))
        assertEquals(pre + listOf("settings", "delete", "global", "always_finish_activities"), d.writeCommand(DisplayKey.DONT_KEEP_ACTIVITIES, null))
        assertNull(DisplayState(mapOf()).pointerLocation)
        assertEquals(false, DisplayState(mapOf(DisplayKey.POINTER_LOCATION to null)).pointerLocation)
        assertEquals(true, DisplayState(mapOf(DisplayKey.DONT_KEEP_ACTIVITIES to "1")).dontKeepActivities)
        val android = DisplayDefaults.forPlatform(Platform.ANDROID)
        assertTrue(android.containsKey(DisplayKey.POINTER_LOCATION) && android.containsKey(DisplayKey.DONT_KEEP_ACTIVITIES))
        assertFalse(DisplayDefaults.forPlatform(Platform.IOS).containsKey(DisplayKey.POINTER_LOCATION))
    }

    @Test fun androidWriteFailureCarriesTheDeviceText() {
        val runner = FakeRunner { CommandResult(255, ByteArray(0), "Error: mode must be 'yes', 'no', or 'auto'\n") }
        try { AndroidDisplay("adb", "s", runner).write(DisplayKey.APPEARANCE, "yes"); fail() } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("Error: mode must be"))
        }
    }

    @Test fun derivedStateValues() {
        fun st(vararg v: Pair<DisplayKey, String?>) = DisplayState(mapOf(*v))
        val off = st(DisplayKey.WINDOW_SCALE to "0", DisplayKey.TRANSITION_SCALE to "0", DisplayKey.ANIMATOR_SCALE to "0.0")
        assertEquals(true, off.animationsOff)
        assertEquals(false, st(DisplayKey.WINDOW_SCALE to "0", DisplayKey.TRANSITION_SCALE to "0", DisplayKey.ANIMATOR_SCALE to null).animationsOff)
        assertNull(st(DisplayKey.WINDOW_SCALE to "0").animationsOff)
        assertEquals(true, st(DisplayKey.SHOW_TOUCHES to "1").showTouches)
        assertEquals(false, st(DisplayKey.SHOW_TOUCHES to "0").showTouches)
        assertEquals(true, st(DisplayKey.LAYOUT_BOUNDS to "true").layoutBounds)
    }

    @Test fun iosCommandsAndErrorDetection() {
        val runner = FakeRunner { cmd ->
            if (cmd.last() == "bogus") ok("Invalid argument\n") else ok("")
        }
        val d = IosDisplay("xcrun", "UDID", runner)
        assertEquals(listOf("xcrun", "simctl", "ui", "UDID", "content_size"), d.readCommand(DisplayKey.FONT_SIZE))
        assertEquals(listOf("xcrun", "simctl", "ui", "UDID", "appearance", "dark"), d.writeCommand(DisplayKey.APPEARANCE, "dark"))
        d.write(DisplayKey.APPEARANCE, "dark")
        d.write(DisplayKey.FONT_SIZE, null)
        assertEquals(listOf("xcrun", "simctl", "ui", "UDID", "content_size", "large"), runner.commands.last())
        try { d.write(DisplayKey.FONT_SIZE, "bogus"); fail() } catch (e: Exception) { assertEquals("Invalid argument", e.message) }
        try { d.write(DisplayKey.SHOW_TOUCHES, "1"); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun iosReadLowercasesAndReportsUnsupported() {
        val runner = FakeRunner { cmd -> if (cmd.last() == "appearance") ok("unsupported\n") else ok("extra-Small\n") }
        val st = IosDisplay("xcrun", "U", runner).read()
        assertEquals("extra-small", st.values[DisplayKey.FONT_SIZE])
        assertFalse(st.has(DisplayKey.APPEARANCE))
        assertTrue(st.errors.containsKey(DisplayKey.APPEARANCE))
    }

    @Test fun baselineKeepsTheFirstValueAndSkipsUnreadKeys() {
        val b = DisplayBaseline()
        val first = DisplayState(mapOf(DisplayKey.APPEARANCE to "no", DisplayKey.ANIMATOR_SCALE to null))
        b.remember("dev", first, listOf(DisplayKey.APPEARANCE, DisplayKey.ANIMATOR_SCALE, DisplayKey.SHOW_TOUCHES))
        b.remember("dev", DisplayState(mapOf(DisplayKey.APPEARANCE to "yes")), listOf(DisplayKey.APPEARANCE))
        val o = b.originals("dev")
        assertEquals("no", o[DisplayKey.APPEARANCE])
        assertTrue(o.containsKey(DisplayKey.ANIMATOR_SCALE) && o[DisplayKey.ANIMATOR_SCALE] == null)
        assertFalse(o.containsKey(DisplayKey.SHOW_TOUCHES))
        assertTrue(b.originals("other").isEmpty())
        b.clear("dev")
        assertTrue(b.originals("dev").isEmpty())
    }

    @Test fun resetPlanUsesOriginalsOrDefaults() {
        assertEquals(listOf(DisplayKey.APPEARANCE to "no"), DisplayDefaults.resetPlan(Platform.ANDROID, mapOf(DisplayKey.APPEARANCE to "no")))
        val android = DisplayDefaults.resetPlan(Platform.ANDROID, emptyMap()).toMap()
        assertEquals("auto", android[DisplayKey.APPEARANCE])
        assertEquals("1.0", android[DisplayKey.FONT_SIZE])
        assertTrue(android.containsKey(DisplayKey.ANIMATOR_SCALE) && android[DisplayKey.ANIMATOR_SCALE] == null)
        assertEquals(DisplayKey.values().size, android.size)
        val ios = DisplayDefaults.resetPlan(Platform.IOS, emptyMap()).toMap()
        assertEquals(mapOf(DisplayKey.APPEARANCE to "light", DisplayKey.FONT_SIZE to "large"), ios)
    }

    /** Records writes; fails for [failOn]. */
    private class FakeBackend(override val platform: Platform = Platform.ANDROID, val failOn: DisplayKey? = null) : DisplayBackend {
        override val keys = DisplayKey.values().toSet()
        val writes = mutableListOf<Pair<DisplayKey, String?>>()
        override fun read() = DisplayState(emptyMap())
        override fun write(key: DisplayKey, value: String?) {
            if (key == failOn) error("refused $key")
            writes += key to value
        }
    }

    @Test fun failedResetDoesNotRememberTheCurrentValues() {
        val actions = DisplayActions()
        val failing = FakeBackend(failOn = DisplayKey.LAYOUT_BOUNDS)
        val outcome = actions.reset("dev", Platform.ANDROID, failing)
        assertEquals("refused LAYOUT_BOUNDS", outcome.failure)
        assertFalse(outcome.restored)
        assertTrue(actions.baseline.originals("dev").isEmpty())
        // A second reset still applies the platform defaults, not stale pre-reset values.
        val ok = FakeBackend()
        val second = actions.reset("dev", Platform.ANDROID, ok)
        assertNull(second.failure)
        assertEquals(DisplayDefaults.resetPlan(Platform.ANDROID, emptyMap()), ok.writes)
    }

    @Test fun failedResetKeepsRememberedValuesAndSuccessClearsThem() {
        val actions = DisplayActions()
        val before = DisplayState(mapOf(DisplayKey.APPEARANCE to "no", DisplayKey.FONT_SIZE to "1.3"))
        assertNull(actions.change("dev", FakeBackend(), before, listOf(DisplayKey.FONT_SIZE to "1.0")))
        val failed = actions.reset("dev", Platform.ANDROID, FakeBackend(failOn = DisplayKey.FONT_SIZE))
        assertTrue(failed.restored && failed.failure != null)
        assertEquals(mapOf(DisplayKey.FONT_SIZE to "1.3"), actions.baseline.originals("dev"))
        val ok = FakeBackend()
        assertNull(actions.reset("dev", Platform.ANDROID, ok).failure)
        assertEquals(listOf(DisplayKey.FONT_SIZE to "1.3"), ok.writes)
        assertTrue(actions.baseline.originals("dev").isEmpty())
    }

    @Test fun changeRemembersBeforeWritingAndReportsFirstError() {
        val actions = DisplayActions()
        val before = DisplayState(mapOf(DisplayKey.WINDOW_SCALE to null, DisplayKey.TRANSITION_SCALE to "1.0"))
        val backend = FakeBackend(failOn = DisplayKey.TRANSITION_SCALE)
        val failure = actions.change("dev", backend, before, listOf(DisplayKey.WINDOW_SCALE to "0", DisplayKey.TRANSITION_SCALE to "0"))
        assertEquals("refused TRANSITION_SCALE", failure)
        assertEquals(listOf(DisplayKey.WINDOW_SCALE to "0"), backend.writes)
        assertEquals(2, actions.baseline.originals("dev").size)
    }

    @Test fun androidWritesLayoutBoundsFalseAndUnset() {
        val runner = FakeRunner { ok("") }
        val android = AndroidDisplay("adb", "emulator-5554", runner)
        android.write(DisplayKey.LAYOUT_BOUNDS, "false")
        android.write(DisplayKey.LAYOUT_BOUNDS, null)
        assertEquals(2, runner.commands.size)
        assertEquals("setprop debug.layout false", runner.commands[0].last())
        assertEquals("setprop debug.layout ''", runner.commands[1].last())
    }

    @Test fun iosReadReportsNonZeroExitAsError() {
        val runner = FakeRunner { CommandResult(1, ByteArray(0), "No devices are booted.") }
        val st = IosDisplay("xcrun", "UDID", runner).read()
        assertTrue(st.values.isEmpty())
        assertEquals(setOf(DisplayKey.APPEARANCE, DisplayKey.FONT_SIZE), st.errors.keys)
        assertTrue(st.errors.getValue(DisplayKey.APPEARANCE).contains("No devices are booted"))
    }
}
