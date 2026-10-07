package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.exec.CommandResult
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import io.github.marcomaes.mobiletestbench.core.permissions.AndroidPermissions
import io.github.marcomaes.mobiletestbench.core.permissions.IosPermissions
import io.github.marcomaes.mobiletestbench.core.permissions.IosPrivacyAction
import io.github.marcomaes.mobiletestbench.core.permissions.IosPrivacyService
import io.github.marcomaes.mobiletestbench.core.permissions.Permissions
import io.github.marcomaes.mobiletestbench.core.permissions.RuntimePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class PermissionsTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    private class FakeRunner(val answer: (List<String>) -> CommandResult) : CommandRunner(emptyMap()) {
        val commands = mutableListOf<List<String>>()
        override fun run(command: List<String>, timeout: Duration): CommandResult {
            commands += command
            return answer(command)
        }
    }

    private fun ok(text: String = "") = CommandResult(0, text.toByteArray(), "")
    private fun fail(code: Int, stdout: String = "", stderr: String = "") = CommandResult(code, stdout.toByteArray(), stderr)

    @Test fun parsesRuntimePermissionsFromRealDumpsys() {
        val set = Permissions.parseDumpsys(fixture("android-dumpsys-package-permissions.txt"))
        val camera = set.runtime.first { it.name == "android.permission.CAMERA" }
        assertFalse(camera.granted)
        assertEquals("CAMERA", camera.shortName)
        assertTrue("USER_SET" in camera.flags)
        val fine = set.runtime.first { it.name == "android.permission.ACCESS_FINE_LOCATION" }
        assertTrue(fine.granted)
        assertTrue(fine.userFixed)
        assertTrue(set.runtime.none { it.name == "android.permission.INTERNET" })
        assertEquals(4, set.runtime.size)
        assertEquals(2, set.installTimeCount)
    }

    @Test fun parsesNoRuntimeSection() {
        val set = Permissions.parseDumpsys("Packages:\n  Package [x] (1):\n    install permissions:\n      android.permission.INTERNET: granted=true\n")
        assertTrue(set.runtime.isEmpty())
        assertEquals(1, set.installTimeCount)
    }

    @Test fun permissionWithoutFlagsParses() {
        val set = Permissions.parseDumpsys("    User 0: x\n      runtime permissions:\n        android.permission.CAMERA: granted=true\n    other:\n")
        assertEquals(listOf(RuntimePermission("android.permission.CAMERA", true)), set.runtime)
    }

    @Test fun errorLinePrefersJavaExceptionLine() {
        val text = fixture("android-pm-grant-errors.txt").lines()
        val stack = text.drop(2).takeWhile { !it.startsWith("$") }.joinToString("\n")
        assertTrue(Permissions.errorLine("", stack).startsWith("java.lang.SecurityException: Permission android.permission.INTERNET"))
        assertEquals("Failure [package not found]", Permissions.errorLine("Failure [package not found]\nError: package not found", ""))
        assertEquals("failed without output", Permissions.errorLine("", ""))
    }

    @Test fun androidGrantAndRevokeBuildCommands() {
        val runner = FakeRunner { ok() }
        val p = AndroidPermissions("adb", "emulator-5554", runner)
        assertTrue(p.grant("com.x", "android.permission.CAMERA").ok)
        assertTrue(p.revoke("com.x", "android.permission.CAMERA").ok)
        assertEquals(listOf("adb", "-s", "emulator-5554", "shell", "pm", "grant", "com.x", "android.permission.CAMERA"), runner.commands[0])
        assertEquals(listOf("adb", "-s", "emulator-5554", "shell", "pm", "revoke", "com.x", "android.permission.CAMERA"), runner.commands[1])
    }

    @Test fun androidErrorUsesBothStreams() {
        val r = AndroidPermissions("adb", "s", FakeRunner { fail(1, stdout = "Failure [package not found]\nError: package not found") })
            .grant("com.not.installed", "android.permission.CAMERA")
        assertFalse(r.ok)
        assertEquals("Failure [package not found]", r.message)
        val r2 = AndroidPermissions("adb", "s", FakeRunner { fail(255, stderr = "\nException occurred while executing 'grant':\njava.lang.SecurityException: not changeable\n\tat x.y") })
            .grant("com.x", "android.permission.INTERNET")
        assertEquals("java.lang.SecurityException: not changeable", r2.message)
    }

    @Test fun androidRejectsUnsafeNames() {
        val runner = FakeRunner { ok() }
        val r = AndroidPermissions("adb", "s", runner).grant("com.x; reboot", "android.permission.CAMERA")
        assertFalse(r.ok)
        assertTrue(runner.commands.isEmpty())
    }

    @Test fun resetAllRevokesAndClearsFlagsPerPermissionAndNeverUsesGlobalReset() {
        val runner = FakeRunner { ok() }
        val perms = listOf(RuntimePermission("android.permission.CAMERA", true), RuntimePermission("android.permission.ACCESS_FINE_LOCATION", false))
        val results = AndroidPermissions("adb", "s", runner).resetAll("com.x", perms)
        assertEquals(6, results.size)
        assertEquals(listOf("revoke", "clear-permission-flags", "clear-permission-flags"), runner.commands.take(3).map { it[5] })
        assertEquals(listOf("user-set", "user-fixed"), runner.commands.drop(1).take(2).map { it.last() })
        assertTrue(runner.commands.none { "reset-permissions" in it })
    }

    @Test fun readParsesAndReportsFailure() {
        val text = fixture("android-dumpsys-package-permissions.txt")
        assertEquals(4, AndroidPermissions("adb", "s", FakeRunner { ok(text) }).read("com.x").runtime.size)
        val e = runCatching { AndroidPermissions("adb", "s", FakeRunner { fail(1, stderr = "device offline") }).read("com.x") }.exceptionOrNull()
        assertEquals("device offline", e?.message)
    }

    @Test fun iosCommandAlwaysCarriesBundleId() {
        val runner = FakeRunner { ok() }
        val p = IosPermissions("xcrun", "UDID", runner)
        assertTrue(p.apply(IosPrivacyAction.RESET, IosPrivacyService.ALL, "com.x.app").ok)
        assertEquals(listOf("xcrun", "simctl", "privacy", "UDID", "reset", "all", "com.x.app"), runner.commands[0])
        assertFalse(p.apply(IosPrivacyAction.RESET, IosPrivacyService.CAMERA, "").ok)
        assertEquals(1, runner.commands.size)
    }

    @Test fun iosErrorShowsFirstLine() {
        val usage = fixture("simctl-privacy-error-missing-bundle.txt").lines().drop(1).joinToString("\n")
        val r = IosPermissions("xcrun", "U", FakeRunner { fail(117, stderr = usage) }).apply(IosPrivacyAction.GRANT, IosPrivacyService.MICROPHONE, "com.x")
        assertFalse(r.ok)
        assertTrue(r.message.isNotBlank() && !r.message.contains('\n'))
    }

    @Test fun iosServicesMatchSimctlHelp() {
        val listed = fixture("simctl-privacy-help.txt").lines().first { it.trim().startsWith("service:") }
            .substringAfter("service:").trim().split(Regex("\\s+")).toSet()
        assertEquals(listed, IosPrivacyService.values().filter { it.documented }.map { it.simctl }.toSet())
        assertFalse(IosPrivacyService.CAMERA.documented)
    }

    @Test fun updatedSystemAppPrintedTwiceUsesFirstBlock() {
        val block = "  Package [x] (1):\n    User 0: x\n      runtime permissions:\n        android.permission.CAMERA: granted=true\n    other:\n"
        val set = Permissions.parseDumpsys("Packages:\n$block\nHidden system packages:\n" +
            block.replace("granted=true", "granted=false"))
        assertEquals(listOf(RuntimePermission("android.permission.CAMERA", true)), set.runtime)
    }

    @Test fun exitZeroWithExceptionOrFailureLineIsAnError() {
        val a = AndroidPermissions("adb", "s", FakeRunner { ok("java.lang.SecurityException: x\n\tat y") })
        val r = a.grant("com.x", "android.permission.CAMERA")
        assertFalse(r.ok)
        assertEquals("java.lang.SecurityException: x", r.message)
        assertFalse(AndroidPermissions("adb", "s", FakeRunner { ok("Failure [nope]") }).revoke("com.x", "android.permission.CAMERA").ok)
    }

    @Test fun resetAllKeepsClearingFlagsWhenRevokeFails() {
        val runner = FakeRunner { if ("revoke" in it) fail(1, stdout = "Failure [bad]") else ok() }
        val results = AndroidPermissions("adb", "s", runner).resetAll("com.x", listOf(RuntimePermission("android.permission.CAMERA", true)))
        assertEquals(listOf(false, true, true), results.map { it.ok })
        assertEquals(3, runner.commands.size)
    }
}
