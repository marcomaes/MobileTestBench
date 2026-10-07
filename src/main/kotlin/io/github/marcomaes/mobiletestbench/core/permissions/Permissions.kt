package io.github.marcomaes.mobiletestbench.core.permissions

import io.github.marcomaes.mobiletestbench.core.exec.CommandResult
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import java.time.Duration

/** One runtime permission of an Android app as `dumpsys package` reports it. */
data class RuntimePermission(val name: String, val granted: Boolean, val flags: Set<String> = emptySet()) {
    /** `CAMERA` for `android.permission.CAMERA`; other namespaces keep their full name. */
    val shortName: String get() = name.removePrefix("android.permission.")

    /** The user chose "don't ask again": after a revoke the app gets no prompt until this flag is cleared. */
    val userFixed: Boolean get() = "USER_FIXED" in flags
}

/** [runtime] can be granted and revoked; [installTimeCount] permissions are fixed at install and cannot be changed. */
data class PermissionSet(val runtime: List<RuntimePermission>, val installTimeCount: Int)

/** What one permission action did: the command that ran and either its success or the first useful error line. */
data class PermissionResult(val commandLine: String, val ok: Boolean, val message: String) {
    val text: String get() = if (ok) "$commandLine: done" else "$commandLine: $message"
}

/** The iOS privacy actions of `simctl privacy`. */
enum class IosPrivacyAction(val simctl: String, val label: String) {
    GRANT("grant", "Grant"), REVOKE("revoke", "Revoke"), RESET("reset", "Reset")
}

/** The privacy services `simctl privacy` knows. [documented] is false for camera: accepted by simctl but not in its help text. */
enum class IosPrivacyService(val simctl: String, val label: String, val documented: Boolean = true) {
    ALL("all", "All services"),
    CALENDAR("calendar", "Calendar"),
    CONTACTS("contacts", "Contacts"),
    CONTACTS_LIMITED("contacts-limited", "Contacts (limited)"),
    LOCATION("location", "Location (while in use)"),
    LOCATION_ALWAYS("location-always", "Location (always)"),
    PHOTOS("photos", "Photos"),
    PHOTOS_ADD("photos-add", "Photos (add only)"),
    MEDIA_LIBRARY("media-library", "Media library"),
    MICROPHONE("microphone", "Microphone"),
    CAMERA("camera", "Camera", documented = false),
    MOTION("motion", "Motion and fitness"),
    REMINDERS("reminders", "Reminders"),
    SIRI("siri", "Siri");

    /** Shown next to the service when it is not part of the simctl help. */
    val note: String? get() = if (documented) null else "accepted by simctl but not in its help text"
}

/** Pure parsing, command building and error extraction for the permission dialog. */
object Permissions {
    private val permLine = Regex("""^\s+(\S+): granted=(true|false)(?:, flags=\[(.*)])?\s*$""")
    private val namePattern = Regex("[A-Za-z0-9._]+")
    private val bundlePattern = Regex("[A-Za-z0-9._-]+")

    /** True for a package or permission name that is safe to pass to a device shell. */
    fun validName(name: String) = namePattern.matches(name)

    /**
     * Parses `dumpsys package <pkg>`: the first `runtime permissions:` block (the changeable ones) and the count of the
     * first `install permissions:` block. Updated system apps are printed twice; the first block with a runtime section wins.
     */
    fun parseDumpsys(text: String): PermissionSet {
        val lines = text.lines()
        val runtimeAt = lines.indexOfFirst { it.trim() == "runtime permissions:" }
        val runtime = if (runtimeAt < 0) emptyList() else block(lines, runtimeAt).mapNotNull { line ->
            permLine.matchEntire(line)?.let { m ->
                RuntimePermission(
                    m.groupValues[1],
                    m.groupValues[2] == "true",
                    m.groupValues[3].split('|').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                )
            }
        }.distinctBy { it.name }
        val installAt = lines.indexOfFirst { it.trim() == "install permissions:" }
        val install = if (installAt < 0) 0 else block(lines, installAt).count { permLine.matches(it) }
        return PermissionSet(runtime, install)
    }

    /** The lines after [headerAt] that are indented deeper than the header. */
    private fun block(lines: List<String>, headerAt: Int): List<String> {
        val indent = lines[headerAt].takeWhile { it == ' ' }.length
        val out = mutableListOf<String>()
        for (i in headerAt + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank() || line.takeWhile { it == ' ' }.length <= indent) break
            out += line
        }
        return out
    }

    fun dumpsysArgs(pkg: String) = listOf("shell", "dumpsys", "package", pkg)
    fun grantArgs(pkg: String, permission: String) = listOf("shell", "pm", "grant", pkg, permission)
    fun revokeArgs(pkg: String, permission: String) = listOf("shell", "pm", "revoke", pkg, permission)

    /** Clears one "user decided" flag (`user-set`, `user-fixed`) so the app can ask again. */
    fun clearFlagArgs(pkg: String, permission: String, flag: String) = listOf("shell", "pm", "clear-permission-flags", pkg, permission, flag)

    /** `xcrun simctl privacy <udid> <action> <service> <bundle>`; the bundle id is always passed (a reset without one would hit every app). */
    fun iosArgs(udid: String, action: IosPrivacyAction, service: IosPrivacyService, bundle: String) =
        listOf("simctl", "privacy", udid, action.simctl, service.simctl, bundle)

    fun validBundle(bundle: String) = bundlePattern.matches(bundle)

    /**
     * The one line worth showing from the output of a failed command: the `java.` exception line of a pm stack trace,
     * else a `Failure [...]` line, else the first non blank line. Both streams are looked at, stdout first.
     */
    fun errorLine(stdout: String, stderr: String): String {
        val lines = (stdout.lines() + stderr.lines()).map { it.trim() }.filter { it.isNotEmpty() }
        return lines.firstOrNull { it.startsWith("java.") }
            ?: lines.firstOrNull { it.startsWith("Failure") }
            ?: lines.firstOrNull()
            ?: "failed without output"
    }

    /** pm can exit 0 with a stack trace in rare cases; treat an exception or Failure line as an error too. */
    fun failed(result: CommandResult): Boolean =
        !result.ok || (result.text + "\n" + result.stderr).lines().any { it.trim().startsWith("java.") || it.trim().startsWith("Failure") }
}

/** Reads and changes the runtime permissions of Android apps through adb. Blocking; call off the EDT. */
class AndroidPermissions(private val adb: String, private val serial: String, private val runner: CommandRunner) {

    /** Throws with the first error line when the package cannot be read. */
    fun read(pkg: String): PermissionSet {
        require(Permissions.validName(pkg)) { "Invalid package name: $pkg" }
        val result = runner.run(command(Permissions.dumpsysArgs(pkg)), Duration.ofSeconds(15))
        if (!result.ok) error(Permissions.errorLine(result.text, result.stderr))
        return Permissions.parseDumpsys(result.text)
    }

    fun grant(pkg: String, permission: String) = exec(Permissions.grantArgs(pkg, permission))
    fun revoke(pkg: String, permission: String) = exec(Permissions.revokeArgs(pkg, permission))

    /**
     * Android has no per-app reset (`pm reset-permissions` resets every app, so it is never used). This revokes each runtime
     * permission and clears its user-set and user-fixed flags so the app can prompt again. One result per command.
     */
    fun resetAll(pkg: String, permissions: List<RuntimePermission>): List<PermissionResult> = permissions.flatMap { p ->
        listOf(revoke(pkg, p.name)) + FLAGS.map { exec(Permissions.clearFlagArgs(pkg, p.name, it)) }
    }

    private fun exec(args: List<String>): PermissionResult {
        val shown = "adb " + args.joinToString(" ")
        if (args.drop(3).any { !Permissions.validName(it) && it !in FLAGS }) return PermissionResult(shown, false, "invalid name")
        return try {
            val r = runner.run(command(args), Duration.ofSeconds(15))
            if (Permissions.failed(r)) PermissionResult(shown, false, Permissions.errorLine(r.text, r.stderr)) else PermissionResult(shown, true, "")
        } catch (e: Exception) {
            PermissionResult(shown, false, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun command(args: List<String>) = listOf(adb, "-s", serial) + args

    companion object {
        val FLAGS = listOf("user-set", "user-fixed")
    }
}

/** Sets the privacy services of one iOS simulator app through `simctl privacy`. The state cannot be read back. Blocking. */
class IosPermissions(private val xcrun: String, private val udid: String, private val runner: CommandRunner) {

    fun apply(action: IosPrivacyAction, service: IosPrivacyService, bundle: String): PermissionResult {
        val args = Permissions.iosArgs(udid, action, service, bundle)
        val shown = "xcrun simctl privacy ${action.simctl} ${service.simctl} $bundle"
        if (!Permissions.validBundle(bundle)) return PermissionResult(shown, false, "invalid bundle id")
        return try {
            val r = runner.run(listOf(xcrun) + args, Duration.ofSeconds(15))
            if (r.ok) PermissionResult(shown, true, "") else PermissionResult(shown, false, Permissions.errorLine(r.text, r.stderr))
        } catch (e: Exception) {
            PermissionResult(shown, false, e.message ?: e.javaClass.simpleName)
        }
    }
}
