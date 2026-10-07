package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.exec.CommandResult
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import io.github.marcomaes.mobiletestbench.core.input.HardwareButton
import io.github.marcomaes.mobiletestbench.core.input.InputActions
import io.github.marcomaes.mobiletestbench.core.input.InputKey
import io.github.marcomaes.mobiletestbench.core.input.InputParsers
import io.github.marcomaes.mobiletestbench.core.ios.MobilecliClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Duration

class InputTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    private class Fake(val answer: (List<String>) -> CommandResult) : CommandRunner(emptyMap()) {
        val commands = mutableListOf<List<String>>()
        override fun run(command: List<String>, timeout: Duration): CommandResult {
            commands += command
            return answer(command)
        }
    }

    private fun ok(text: String = "") = CommandResult(0, text.toByteArray(), "")
    private fun fail(text: String) = CommandResult(1, text.toByteArray(), "")

    private fun android(runner: CommandRunner, mobilecli: Boolean) = InputActions(
        Platform.ANDROID, "emulator-5554", runner, "adb", null, if (mobilecli) MobilecliClient("mobilecli", runner) else null, if (mobilecli) "Pixel_9" else null,
    )

    private fun ios(runner: CommandRunner, mobilecli: Boolean = true) = InputActions(
        Platform.IOS, "UDID", runner, null, "xcrun", if (mobilecli) MobilecliClient("mobilecli", runner) else null, if (mobilecli) "UDID" else null,
    )

    @Test fun buttonsPerPlatform() {
        assertEquals(
            listOf("VOLUME_UP", "VOLUME_DOWN", "HOME", "POWER", "BACK", "APP_SWITCH", "ASSISTANT"),
            HardwareButton.forPlatform(Platform.ANDROID).map { it.name },
        )
        assertEquals(listOf("VOLUME_UP", "VOLUME_DOWN", "HOME", "POWER", "SHAKE"), HardwareButton.forPlatform(Platform.IOS).map { it.name })
        assertEquals("LOCK", HardwareButton.POWER.mobilecliName(Platform.IOS))
        assertEquals("POWER", HardwareButton.POWER.mobilecliName(Platform.ANDROID))
        assertNull(HardwareButton.ASSISTANT.mobilecliName(Platform.ANDROID))
        assertTrue(HardwareButton.values().all { b -> listOfNotNull(b.androidName, b.iosName).all { it == it.uppercase() } })
    }

    @Test fun keyNamesAreMobilecliSpelling() {
        assertEquals(listOf("tab", "escape", "enter", "backspace", "delete", "up", "down", "left", "right"), InputKey.values().map { it.mobilecli })
    }

    @Test fun parsesReadBacks() {
        assertEquals("Awake", InputParsers.wakefulness("  mWakefulness=Awake\n mWakefulnessChanging=false"))
        assertEquals("5/15", InputParsers.volume("[V] volume is 5 in range [0..15]").toString())
        assertEquals("5", InputParsers.volume("   streamVolume:5").toString())
        assertNull(InputParsers.volume("nothing"))
        assertEquals(true, InputParsers.imeVisible("  mImeWindowVis=3\n"))
        assertEquals(false, InputParsers.imeVisible("  mImeWindowVis=0\n"))
        assertEquals(true, InputParsers.imeVisible("mImeWindowVis=0x1"))
        assertNull(InputParsers.imeVisible("nothing"))
        assertEquals(true, InputParsers.locked("com.apple.springboard.lockstate 1\n"))
        assertEquals(false, InputParsers.locked("com.apple.springboard.lockstate 0"))
    }

    @Test fun mobilecliButtonAndClipboardFixtures() {
        val sent = mutableListOf<List<String>>()
        val runner = Fake { cmd -> sent += cmd; ok(fixture("mobilecli-button-ok.json")) }
        MobilecliClient("mobilecli", runner).button("Pixel_9", "VOLUME_UP")
        assertEquals(listOf("mobilecli", "io", "button", "--device", "Pixel_9", "VOLUME_UP"), sent.single())

        val get = Fake { ok(fixture("mobilecli-clipboard-get-ok.json")) }
        assertEquals("line1\nline \"two\" é ✓ \$HOME", MobilecliClient("m", get).clipboardGet("x"))
        assertEquals("", MobilecliClient("m", Fake { ok(fixture("mobilecli-clipboard-get-empty.json")) }).clipboardGet("x"))

        val set = Fake { ok(fixture("mobilecli-clipboard-set-ok.json")) }
        MobilecliClient("m", set).clipboardSet("x", "-dash")
        assertEquals(listOf("m", "io", "clipboard", "set", "--device", "x", "--", "-dash"), set.commands.single())
    }

    @Test fun unsupportedButtonSurfacesMobilecliText() {
        val runner = Fake { fail(fixture("mobilecli-button-error-android.json")) }
        try {
            MobilecliClient("m", runner).button("Pixel_9", "ASSISTANT")
            fail()
        } catch (e: MobilecliException) {
            assertTrue(e.message!!, e.message!!.contains("unsupported button key: ASSISTANT"))
        }
    }

    @Test fun androidButtonsGoThroughMobilecliOrAdb() {
        val withCli = Fake { ok(fixture("mobilecli-button-ok.json")) }
        assertEquals("Pressed POWER", android(withCli, true).press(HardwareButton.POWER))
        assertEquals(listOf("mobilecli", "io", "button", "--device", "Pixel_9", "POWER"), withCli.commands.single())

        val adbOnly = Fake { ok() }
        android(adbOnly, false).press(HardwareButton.APP_SWITCH)
        assertEquals(listOf("adb", "-s", "emulator-5554", "shell", "input", "keyevent", "187"), adbOnly.commands.single())

        val assistant = Fake { ok() }
        android(assistant, true).press(HardwareButton.ASSISTANT)
        assertEquals(listOf("adb", "-s", "emulator-5554", "shell", "input", "keyevent", "219"), assistant.commands.single())
    }

    @Test fun iosLockShakeAndMissingMobilecli() {
        val r = Fake { ok(fixture("mobilecli-button-ok.json")) }
        ios(r).press(HardwareButton.POWER)
        assertEquals("LOCK", r.commands.single().last())
        val shake = Fake { ok() }
        ios(shake, false).press(HardwareButton.SHAKE)
        assertEquals(listOf("xcrun", "simctl", "spawn", "UDID", "notifyutil", "-p", "com.apple.UIKit.SimulatorShake"), shake.commands.single())
        try { ios(Fake { ok() }, false).press(HardwareButton.HOME); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("mobilecli")) }
        try { ios(Fake { ok() }).press(HardwareButton.BACK); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("not available")) }
    }

    @Test fun keysFallBackToAdbOnAndroidOnly() {
        val adbOnly = Fake { ok() }
        android(adbOnly, false).pressKey(InputKey.TAB)
        assertEquals("61", adbOnly.commands.single().last())
        val cli = Fake { ok(fixture("mobilecli-button-ok.json")) }
        android(cli, true).pressKey(InputKey.LEFT)
        assertEquals(listOf("mobilecli", "io", "keys", "--device", "Pixel_9", "left"), cli.commands.single())
        try { ios(Fake { ok() }, false).pressKey(InputKey.TAB); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("mobilecli")) }
    }

    @Test fun hideKeyboardSendsEscapeOnlyWhenShown() {
        val shown = Fake { cmd -> if (cmd.contains("dumpsys")) ok("mImeWindowVis=3") else ok() }
        android(shown, false).hideKeyboard()
        assertEquals("111", shown.commands.last().last())
        val hidden = Fake { ok("mImeWindowVis=0") }
        assertEquals("The keyboard is not shown", android(hidden, false).hideKeyboard())
        assertEquals(1, hidden.commands.size)
        try { ios(Fake { ok() }).hideKeyboard(); fail() } catch (e: IllegalStateException) { }
    }

    @Test fun clipboardNeedsMobilecliAndPasteComboPerPlatform() {
        try { android(Fake { ok() }, false).clipboardGet(); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.startsWith("Clipboard needs mobilecli")) }
        val a = Fake { ok(fixture("mobilecli-clipboard-set-ok.json")) }
        android(a, true).setAndPaste("hi")
        assertEquals(listOf("clipboard", "keys"), a.commands.map { it[2] })
        assertEquals("ctrl+v", a.commands.last().last())
        val i = Fake { ok(fixture("mobilecli-clipboard-set-ok.json")) }
        assertTrue(ios(i).setAndPaste("hi").contains("Allow Paste"))
        assertEquals("cmd+v", i.commands.last().last())
    }

    @Test fun statusReadBack() {
        val r = Fake { cmd ->
            when {
                cmd.contains("power") -> ok("mWakefulness=Asleep")
                else -> ok("[V] volume is 6 in range [0..15]")
            }
        }
        assertEquals("screen Asleep, media volume 6/15", android(r, false).status())
        assertEquals("locked", ios(Fake { ok("com.apple.springboard.lockstate 1") }).status())
        assertFalse(HardwareButton.SHAKE.supports(Platform.ANDROID))
    }
}
