package io.github.marcomaes.mobiletestbench.core.biometrics

import io.github.marcomaes.mobiletestbench.core.exec.CommandFailedException
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import java.time.Duration

/** Face ID or Touch ID, as far as it can be told from the simulator model name. */
enum class IosBiometry(val label: String, val subject: String, val matchNotification: String, val noMatchNotification: String) {
    FACE_ID("Face ID", "face", "com.apple.BiometricKit_Sim.pearl.match", "com.apple.BiometricKit_Sim.pearl.nomatch"),
    TOUCH_ID("Touch ID", "finger", "com.apple.BiometricKit_Sim.fingerTouch.match", "com.apple.BiometricKit_Sim.fingerTouch.nomatch"),
}

/** Reply of the Android emulator console: the first line is `OK` or `KO: reason`. */
sealed class EmuReply {
    object Ok : EmuReply()
    data class Ko(val reason: String) : EmuReply()
}

/** Enrolled fingerprint templates in `dumpsys fingerprint`: [id] is the user bucket, [count] the number of templates in it. */
data class FingerprintPrints(val id: Int, val count: Int, val accept: Int, val reject: Int)

/** Pure command building and parsing. */
object BiometricCommands {
    const val ENROLLMENT = "com.apple.BiometricKit.enrollmentChanged"
    const val MIN_FINGER_ID = 1
    const val MAX_FINGER_ID = 10

    /** Face ID unless the model name says a home button / Touch ID device (iPhone SE, iPhone 8 and older, iPads other than the Face ID Pros: every iPad Pro except 9.7-inch, 10.5-inch and 12.9-inch 1st/2nd generation). A guess: simctl does not report it. */
    fun biometryFor(modelName: String): IosBiometry {
        val n = modelName.trim()
        if (Regex("iPhone SE", RegexOption.IGNORE_CASE).containsMatchIn(n)) return IosBiometry.TOUCH_ID
        if (Regex("^iPhone\\s+([5-8])s?(\\s+Plus)?$", RegexOption.IGNORE_CASE).containsMatchIn(n)) return IosBiometry.TOUCH_ID
        if (n.startsWith("iPad", ignoreCase = true)) {
            val facePro = Regex("Pro", RegexOption.IGNORE_CASE).containsMatchIn(n) &&
                !Regex("9\\.7|10\\.5|12\\.9-inch\\)\\s*\\((1st|2nd)", RegexOption.IGNORE_CASE).containsMatchIn(n)
            return if (facePro) IosBiometry.FACE_ID else IosBiometry.TOUCH_ID
        }
        return IosBiometry.FACE_ID
    }

    private fun spawn(xcrun: String, udid: String, vararg args: String) = listOf(xcrun, "simctl", "spawn", udid, "notifyutil") + args

    /** Enrolling is two processes: set the state to 1, then post the notification. Run them in order. */
    fun iosEnroll(xcrun: String, udid: String) = listOf(spawn(xcrun, udid, "-s", ENROLLMENT, "1"), spawn(xcrun, udid, "-p", ENROLLMENT))
    fun iosUnenroll(xcrun: String, udid: String) = listOf(spawn(xcrun, udid, "-s", ENROLLMENT, "0"), spawn(xcrun, udid, "-p", ENROLLMENT))
    fun iosReadEnrollment(xcrun: String, udid: String) = spawn(xcrun, udid, "-g", ENROLLMENT)
    fun iosMatch(xcrun: String, udid: String, b: IosBiometry) = spawn(xcrun, udid, "-p", b.matchNotification)
    fun iosNoMatch(xcrun: String, udid: String, b: IosBiometry) = spawn(xcrun, udid, "-p", b.noMatchNotification)

    fun validFingerId(id: Int) = id in MIN_FINGER_ID..MAX_FINGER_ID

    /** Parses the finger id field; null with a message when it is not 1..10. */
    fun parseFingerId(text: String): Pair<Int?, String?> {
        val id = text.trim().toIntOrNull()
        return if (id != null && validFingerId(id)) id to null else null to "Finger id must be a whole number from $MIN_FINGER_ID to $MAX_FINGER_ID"
    }

    fun androidTouch(adb: String, serial: String, id: Int): List<String> {
        require(validFingerId(id)) { "Finger id must be $MIN_FINGER_ID..$MAX_FINGER_ID" }
        return listOf(adb, "-s", serial, "emu", "finger", "touch", id.toString())
    }

    fun androidLift(adb: String, serial: String) = listOf(adb, "-s", serial, "emu", "finger", "remove")
    fun androidDump(adb: String, serial: String) = listOf(adb, "-s", serial, "shell", "dumpsys", "fingerprint")

    /** `adb emu` always exits 0, so the first line decides: `OK` or `KO: reason`. Null when neither (for example help text only). */
    fun parseEmuReply(stdout: String): EmuReply? {
        val lines = stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        lines.firstOrNull { it.startsWith("KO") }?.let { return EmuReply.Ko(it.removePrefix("KO").trimStart(':', ' ').ifBlank { "unknown" }) }
        return if (lines.firstOrNull() == "OK") EmuReply.Ok else null
    }

    /** `notifyutil -g` prints `<name> <state>`; true for a non-zero state. Null when not parseable. */
    fun parseEnrollment(stdout: String): Boolean? {
        val last = stdout.lines().map { it.trim() }.lastOrNull { it.startsWith(ENROLLMENT) } ?: return null
        return last.removePrefix(ENROLLMENT).trim().toLongOrNull()?.let { it != 0L }
    }

    private val PRINTS = Regex("\"id\"\\s*:\\s*(\\d+)\\s*,\\s*\"count\"\\s*:\\s*(\\d+)\\s*,\\s*\"accept\"\\s*:\\s*(\\d+)\\s*,\\s*\"reject\"\\s*:\\s*(\\d+)")

    /** The per user entries of the `prints` array of `dumpsys fingerprint`. Empty when the dump has none (or an unknown shape). */
    fun parseFingerprintDump(text: String): List<FingerprintPrints> =
        PRINTS.findAll(text).map { m -> m.groupValues.drop(1).map { it.toInt() }.let { FingerprintPrints(it[0], it[1], it[2], it[3]) } }.toList()

    /** Total enrolled templates; null when the dump had no `prints` entries at all (no fingerprint service). */
    fun enrolledCount(text: String): Int? = parseFingerprintDump(text).takeIf { it.isNotEmpty() }?.sumOf { it.count }
}

/** Outcome of one button: the exact [text] to show and whether it is an [error]. */
data class BiometricResult(val text: String, val error: Boolean = false)

/** iOS simulator side (`simctl spawn notifyutil`). Blocking. */
class IosBiometrics(private val xcrun: String, private val udid: String, private val runner: CommandRunner) {
    private fun run(cmd: List<String>): String {
        val r = runner.run(cmd, Duration.ofSeconds(15))
        if (!r.ok) throw CommandFailedException(r.stderr.trim().ifBlank { r.text.trim() }.ifBlank { "exit code ${r.exitCode}" }.take(300))
        return r.text
    }

    /** What this tab (or an earlier notifyutil call) set; not Simulator.app's own Features menu state. Null when unreadable. */
    fun readEnrolled(): Boolean? = runCatching { BiometricCommands.parseEnrollment(run(BiometricCommands.iosReadEnrollment(xcrun, udid))) }.getOrNull()

    fun enroll(): BiometricResult {
        BiometricCommands.iosEnroll(xcrun, udid).forEach { run(it) }
        return BiometricResult("Posted ${BiometricCommands.ENROLLMENT} with state 1 (enrolled). Whether the OS accepted it shows in the simulator (Face ID / Touch ID becomes usable).")
    }

    fun unenroll(): BiometricResult {
        BiometricCommands.iosUnenroll(xcrun, udid).forEach { run(it) }
        return BiometricResult("Posted ${BiometricCommands.ENROLLMENT} with state 0 (not enrolled).")
    }

    fun match(b: IosBiometry): BiometricResult {
        run(BiometricCommands.iosMatch(xcrun, udid, b))
        return BiometricResult("Posted ${b.matchNotification}. notifyutil exits 0 for any name, so this only means the notification was sent; an app sees it only while a ${b.label} prompt is showing.")
    }

    fun noMatch(b: IosBiometry): BiometricResult {
        run(BiometricCommands.iosNoMatch(xcrun, udid, b))
        return BiometricResult("Posted ${b.noMatchNotification}. This only means the notification was sent; an app sees it only while a ${b.label} prompt is showing.")
    }
}

/** Android emulator side (`adb emu finger`, `dumpsys fingerprint`). Blocking. */
class AndroidBiometrics(private val adb: String, private val serial: String, private val runner: CommandRunner) {
    private fun emu(cmd: List<String>): EmuReply {
        val r = runner.run(cmd, Duration.ofSeconds(15))
        val out = r.text + "\n" + r.stderr
        // adb itself fails (no device, console auth) with a non zero exit and no OK/KO line.
        val reply = BiometricCommands.parseEmuReply(out)
        if (reply == null) throw CommandFailedException(out.trim().lines().firstOrNull { it.isNotBlank() }?.take(300) ?: "No reply from adb (exit code ${r.exitCode})")
        return reply
    }

    fun touch(id: Int): BiometricResult = when (val r = emu(BiometricCommands.androidTouch(adb, serial, id))) {
        EmuReply.Ok -> BiometricResult("Console replied OK: finger $id touched the sensor. OK does not mean authentication succeeded: it is also the reply when no fingerprint is enrolled, and it carries no match information.")
        is EmuReply.Ko -> BiometricResult("Console replied KO: ${r.reason}", error = true)
    }

    fun lift(): BiometricResult = when (val r = emu(BiometricCommands.androidLift(adb, serial))) {
        EmuReply.Ok -> BiometricResult("Console replied OK: finger lifted.")
        is EmuReply.Ko -> BiometricResult("Console replied KO: ${r.reason}", error = true)
    }

    /** Raw `dumpsys fingerprint` entries; throws with the tool's text when the command fails. */
    fun prints(): List<FingerprintPrints> {
        val r = runner.run(BiometricCommands.androidDump(adb, serial), Duration.ofSeconds(15))
        if (!r.ok) throw CommandFailedException((r.stderr.ifBlank { r.text }).trim().take(300))
        return BiometricCommands.parseFingerprintDump(r.text)
    }
}
