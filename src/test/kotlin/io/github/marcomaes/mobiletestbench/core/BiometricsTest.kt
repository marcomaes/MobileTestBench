package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.biometrics.AndroidBiometrics
import io.github.marcomaes.mobiletestbench.core.biometrics.BiometricCommands
import io.github.marcomaes.mobiletestbench.core.biometrics.EmuReply
import io.github.marcomaes.mobiletestbench.core.biometrics.FingerprintPrints
import io.github.marcomaes.mobiletestbench.core.biometrics.IosBiometrics
import io.github.marcomaes.mobiletestbench.core.biometrics.IosBiometry
import io.github.marcomaes.mobiletestbench.core.exec.CommandFailedException
import io.github.marcomaes.mobiletestbench.core.exec.CommandResult
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class BiometricsTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    private class FakeRunner(val answer: (List<String>) -> CommandResult) : CommandRunner(emptyMap()) {
        val commands = mutableListOf<List<String>>()
        override fun run(command: List<String>, timeout: Duration): CommandResult {
            commands += command
            return answer(command)
        }
    }

    private fun ok(text: String = "") = CommandResult(0, text.toByteArray(), "")

    @Test fun biometryFromModelName() {
        assertEquals(IosBiometry.FACE_ID, BiometricCommands.biometryFor("iPhone 17 Pro"))
        assertEquals(IosBiometry.FACE_ID, BiometricCommands.biometryFor("iPhone X"))
        assertEquals(IosBiometry.FACE_ID, BiometricCommands.biometryFor("iPhone 15 Pro"))
        assertEquals(IosBiometry.TOUCH_ID, BiometricCommands.biometryFor("iPhone SE (3rd generation)"))
        assertEquals(IosBiometry.TOUCH_ID, BiometricCommands.biometryFor("iPhone 8"))
        assertEquals(IosBiometry.TOUCH_ID, BiometricCommands.biometryFor("iPhone 7 Plus"))
        assertEquals(IosBiometry.TOUCH_ID, BiometricCommands.biometryFor("iPad (10th generation)"))
        assertEquals(IosBiometry.FACE_ID, BiometricCommands.biometryFor("iPad Pro 13-inch (M4)"))
        assertEquals(IosBiometry.TOUCH_ID, BiometricCommands.biometryFor("iPad Pro (9.7-inch)"))
        assertEquals(IosBiometry.TOUCH_ID, BiometricCommands.biometryFor("iPhone 6s Plus"))
        assertEquals(IosBiometry.TOUCH_ID, BiometricCommands.biometryFor("iPhone 6s"))
        assertEquals(IosBiometry.FACE_ID, BiometricCommands.biometryFor("iPad Pro (11-inch) (1st generation)"))
        assertEquals(IosBiometry.TOUCH_ID, BiometricCommands.biometryFor("iPad Pro (12.9-inch) (1st generation)"))
        assertEquals(IosBiometry.TOUCH_ID, BiometricCommands.biometryFor("iPad Pro (12.9-inch) (2nd generation)"))
        assertEquals(IosBiometry.FACE_ID, BiometricCommands.biometryFor("iPad Pro (12.9-inch) (3rd generation)"))
        assertEquals(IosBiometry.TOUCH_ID, BiometricCommands.biometryFor("iPad Air"))
    }

    @Test fun iosCommands() {
        val enroll = BiometricCommands.iosEnroll("xcrun", "U")
        assertEquals(listOf("xcrun", "simctl", "spawn", "U", "notifyutil", "-s", "com.apple.BiometricKit.enrollmentChanged", "1"), enroll[0])
        assertEquals(listOf("xcrun", "simctl", "spawn", "U", "notifyutil", "-p", "com.apple.BiometricKit.enrollmentChanged"), enroll[1])
        assertEquals("0", BiometricCommands.iosUnenroll("xcrun", "U")[0].last())
        assertEquals("com.apple.BiometricKit_Sim.pearl.match", BiometricCommands.iosMatch("xcrun", "U", IosBiometry.FACE_ID).last())
        assertEquals("com.apple.BiometricKit_Sim.pearl.nomatch", BiometricCommands.iosNoMatch("xcrun", "U", IosBiometry.FACE_ID).last())
        assertEquals("com.apple.BiometricKit_Sim.fingerTouch.match", BiometricCommands.iosMatch("xcrun", "U", IosBiometry.TOUCH_ID).last())
        assertEquals("com.apple.BiometricKit_Sim.fingerTouch.nomatch", BiometricCommands.iosNoMatch("xcrun", "U", IosBiometry.TOUCH_ID).last())
    }

    @Test fun enrollmentState() {
        assertEquals(false, BiometricCommands.parseEnrollment("com.apple.BiometricKit.enrollmentChanged 0\n"))
        assertEquals(true, BiometricCommands.parseEnrollment("com.apple.BiometricKit.enrollmentChanged 1"))
        assertNull(BiometricCommands.parseEnrollment("garbage"))
    }

    @Test fun androidCommandsAndIdValidation() {
        assertEquals(listOf("adb", "-s", "emulator-5554", "emu", "finger", "touch", "3"), BiometricCommands.androidTouch("adb", "emulator-5554", 3))
        assertEquals(listOf("adb", "-s", "emulator-5554", "emu", "finger", "remove"), BiometricCommands.androidLift("adb", "emulator-5554"))
        assertEquals(1 to null, BiometricCommands.parseFingerId(" 1 "))
        assertNull(BiometricCommands.parseFingerId("0").first)
        assertNull(BiometricCommands.parseFingerId("11").first)
        assertNull(BiometricCommands.parseFingerId("abc").first)
        assertNull(BiometricCommands.parseFingerId("").first)
        try { BiometricCommands.androidTouch("adb", "s", 0); throw AssertionError("expected failure") } catch (_: IllegalArgumentException) { }
    }

    @Test fun emuReplyFromFixture() {
        val lines = fixture("android-emu-finger-outputs.txt").lines()
        assertEquals(EmuReply.Ok, BiometricCommands.parseEmuReply(lines[0]))
        assertEquals(EmuReply.Ko("invalid fingerid"), BiometricCommands.parseEmuReply(lines[1]))
        assertEquals(EmuReply.Ko("missing fingerid"), BiometricCommands.parseEmuReply(lines[2]))
        assertNull(BiometricCommands.parseEmuReply("some help text"))
        assertNull(BiometricCommands.parseEmuReply(""))
    }

    @Test fun fingerprintDumpFromFixture() {
        val text = fixture("android-dumpsys-fingerprint-none.txt")
        assertEquals(listOf(FingerprintPrints(0, 0, 0, 0)), BiometricCommands.parseFingerprintDump(text))
        assertEquals(0, BiometricCommands.enrolledCount(text))
        val enrolled = """{"service":"x","prints":[{"id":0,"count":2,"accept":5,"reject":1,"acquire":0}]}"""
        assertEquals(2, BiometricCommands.enrolledCount(enrolled))
        assertNull(BiometricCommands.enrolledCount("no json here"))
    }

    @Test fun iosActionsRunInOrderAndReportFailure() {
        val runner = FakeRunner { ok() }
        val ios = IosBiometrics("xcrun", "U", runner)
        ios.enroll()
        assertEquals(listOf("-s", "-p"), runner.commands.map { it[5] })
        assertTrue(ios.match(IosBiometry.FACE_ID).text.contains("pearl.match"))
        assertFalse(ios.noMatch(IosBiometry.TOUCH_ID).error)

        val failing = IosBiometrics("xcrun", "U", FakeRunner { CommandResult(148, ByteArray(0), "Invalid device: U") })
        try { failing.unenroll(); throw AssertionError("expected failure") } catch (e: CommandFailedException) { assertEquals("Invalid device: U", e.message) }
        assertNull(failing.readEnrolled())
    }

    @Test fun androidTouchReportsOkAndKoAndAdbFailure() {
        val okRunner = FakeRunner { ok("OK\n") }
        val result = AndroidBiometrics("adb", "emulator-5554", okRunner).touch(1)
        assertFalse(result.error)
        assertTrue(result.text.contains("does not mean authentication succeeded"))

        val ko = AndroidBiometrics("adb", "emulator-5554", FakeRunner { ok("KO: invalid fingerid\n") }).touch(1)
        assertTrue(ko.error)
        assertTrue(ko.text.contains("invalid fingerid"))

        val adbFail = AndroidBiometrics("adb", "emulator-5554", FakeRunner { CommandResult(1, ByteArray(0), "error: device 'emulator-5554' not found") })
        try { adbFail.lift(); throw AssertionError("expected failure") } catch (e: CommandFailedException) { assertTrue(e.message!!.contains("not found")) }
    }
}
