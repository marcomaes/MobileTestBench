package io.github.marcomaes.mobiletestbench.core.input

import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.ios.MobilecliClient
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner

/**
 * Hardware buttons of the Input tab. A separate type from `DeviceKey` on purpose: the recorder and player use `DeviceKey`, and nothing
 * here may be recorded without a player mapping. Names are mobilecli's (UPPER CASE only) and were verified on an Android emulator and an
 * iOS simulator with mobilecli 1.0.17.
 */
enum class HardwareButton(
    val label: String,
    /** mobilecli button name on Android, null when mobilecli has none (the adb key code is used). */
    val androidName: String?,
    /** adb key code; null when the button does not exist on Android. */
    val androidKeyCode: Int?,
    /** mobilecli button name on iOS simulators; null when the button does not exist there. */
    val iosName: String?,
    val androidHint: String,
    val iosHint: String,
) {
    VOLUME_UP("Volume up", "VOLUME_UP", 24, "VOLUME_UP",
        "The first press while the volume panel is hidden may only show the panel", "Pressed, but the effect is not observable from here"),
    VOLUME_DOWN("Volume down", "VOLUME_DOWN", 25, "VOLUME_DOWN",
        "The first press while the volume panel is hidden may only show the panel", "Pressed, but the effect is not observable from here"),
    HOME("Home", "HOME", 3, "HOME", "", ""),
    POWER("Power / Lock", "POWER", 26, "LOCK",
        "Toggles: sleeps the screen, a second press wakes it", "Lock toggles; wake with it, then press Home to unlock"),
    BACK("Back", "BACK", 4, null, "", "Android only"),
    APP_SWITCH("App switch", "APP_SWITCH", 187, null, "", "Android only"),
    ASSISTANT("Assistant", null, 219, null, "The assistant overlay appears after a few seconds, if the Google app is present", "Android only (Siri has no simulator command)"),
    SHAKE("Shake", null, null, null, "iOS simulators only", "Sends the simulator shake; an app that handles it reacts (for example Undo Typing)");

    fun supports(platform: Platform) = if (platform == Platform.ANDROID) androidKeyCode != null else iosName != null || this == SHAKE

    fun hint(platform: Platform) = if (platform == Platform.ANDROID) androidHint else iosHint

    /** mobilecli's name for this button on [platform], or null when mobilecli cannot press it there. */
    fun mobilecliName(platform: Platform): String? = if (platform == Platform.ANDROID) androidName else iosName

    companion object {
        fun forPlatform(platform: Platform): List<HardwareButton> = values().filter { it.supports(platform) }

        /** The Android key code of the wake key (there is no mobilecli name for it). */
        const val ANDROID_WAKEUP = 224
        const val ANDROID_ESCAPE = 111
        const val SHAKE_NOTIFICATION = "com.apple.UIKit.SimulatorShake"
    }
}

/** Special keys. [mobilecli] is the name `mobilecli io keys` takes (arrows are plain up/down/left/right, not arrowup); [androidKeyCode] is the adb fallback. */
enum class InputKey(val label: String, val mobilecli: String, val androidKeyCode: Int) {
    TAB("Tab", "tab", 61),
    ESCAPE("Esc", "escape", 111),
    ENTER("Enter", "enter", 66),
    BACKSPACE("Backspace", "backspace", 67),
    DELETE("Delete", "delete", 112),
    UP("Up", "up", 19),
    DOWN("Down", "down", 20),
    LEFT("Left", "left", 21),
    RIGHT("Right", "right", 22),
}

/** Parsers for the read-backs shown after an action. They work on the full command output. */
object InputParsers {
    /** `Awake`, `Asleep` or `Dozing` from `dumpsys power`. */
    fun wakefulness(dumpsysPower: String): String? = Regex("mWakefulness=(\\w+)").find(dumpsysPower)?.groupValues?.get(1)

    data class Volume(val level: Int, val min: Int?, val max: Int?) {
        override fun toString() = if (max != null) "$level/$max" else "$level"
    }

    /** From `cmd media_session volume --stream 3 --get` (`[V] volume is 5 in range [0..15]`), else a `streamVolume:5` line of `dumpsys audio`. */
    fun volume(output: String): Volume? {
        Regex("volume is (\\d+) in range \\[(\\d+)\\.\\.(\\d+)\\]").find(output)?.let {
            return Volume(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }
        return Regex("streamVolume:(\\d+)").find(output)?.let { Volume(it.groupValues[1].toInt(), null, null) }
    }

    /** True when the soft keyboard window is visible (`mImeWindowVis` is not 0). `mInputShown` is not reliable. Null when the line is missing. */
    fun imeVisible(dumpsysInputMethod: String): Boolean? {
        val m = Regex("mImeWindowVis=(0x)?([0-9a-fA-F]+)").find(dumpsysInputMethod) ?: return null
        return m.groupValues[2].toLong(if (m.groupValues[1].isNotEmpty()) 16 else 10) != 0L
    }

    /** True when the simulator is locked (`notifyutil -g com.apple.springboard.lockstate` prints `... lockstate 1`). */
    fun locked(notifyutil: String): Boolean? = Regex("lockstate\\s+(\\d+)").find(notifyutil)?.groupValues?.get(1)?.let { it != "0" }
}

/**
 * Runs the Input tab actions for one device. Every method blocks (call off the EDT), returns the exact result text to show and throws
 * with the tool's own message on failure. [mobilecli] and [mobilecliId] are null when mobilecli is not installed: Android buttons and keys
 * then use adb key events, everything else says that it needs mobilecli.
 */
class InputActions(
    private val platform: Platform,
    private val deviceId: String,
    private val runner: CommandRunner,
    private val adb: String?,
    private val xcrun: String?,
    private val mobilecli: MobilecliClient?,
    private val mobilecliId: String?,
) {
    val hasMobilecli get() = mobilecli != null && mobilecliId != null

    private fun adbShell(vararg args: String) =
        runner.runChecked(listOf(adb ?: error("adb is not available"), "-s", deviceId, "shell") + args)

    private fun keyEvent(code: Int) {
        adbShell("input", "keyevent", "$code")
    }

    fun press(button: HardwareButton): String {
        check(button.supports(platform)) { "${button.label} is not available on ${if (platform == Platform.ANDROID) "Android" else "iOS"}" }
        if (button == HardwareButton.SHAKE) {
            runner.runChecked(listOf(xcrun ?: error("xcrun is not available"), "simctl", "spawn", deviceId, "notifyutil", "-p", HardwareButton.SHAKE_NOTIFICATION))
            return "Sent the shake gesture"
        }
        val name = button.mobilecliName(platform)
        if (platform == Platform.ANDROID) {
            if (name != null && hasMobilecli) mobilecli!!.button(mobilecliId!!, name) else keyEvent(button.androidKeyCode!!)
            return "Pressed ${name ?: "KEYCODE ${button.androidKeyCode}"}"
        }
        if (!hasMobilecli) error("Hardware buttons on iOS need mobilecli")
        mobilecli!!.button(mobilecliId!!, name!!)
        return "Pressed $name"
    }

    /** Android only: the wake key, so a sleeping screen can be woken deterministically (the Power button toggles). */
    fun wake(): String {
        keyEvent(HardwareButton.ANDROID_WAKEUP)
        return "Sent WAKEUP"
    }

    fun pressKey(key: InputKey): String {
        if (hasMobilecli) mobilecli!!.keys(mobilecliId!!, listOf(key.mobilecli))
        else if (platform == Platform.ANDROID) keyEvent(key.androidKeyCode)
        else error("Keys on iOS need mobilecli")
        return "Pressed ${key.mobilecli}"
    }

    /** Android: hides the soft keyboard with Escape (never Back, which leaves the screen) when it is visible. */
    fun hideKeyboard(): String {
        check(platform == Platform.ANDROID) { "Hiding the keyboard is only supported on Android" }
        val visible = InputParsers.imeVisible(adbShell("dumpsys", "input_method").text)
        if (visible == false) return "The keyboard is not shown"
        keyEvent(HardwareButton.ANDROID_ESCAPE)
        return "Sent Escape" + if (visible == null) " (could not tell whether the keyboard was shown)" else ""
    }

    /** True/false when the keyboard state can be read (Android), null otherwise. */
    fun keyboardVisible(): Boolean? =
        if (platform == Platform.ANDROID) InputParsers.imeVisible(adbShell("dumpsys", "input_method").text) else null

    /** What the device reports after a button press: wakefulness and media volume on Android, the lock state on a simulator. Null when nothing can be read. */
    fun status(): String? = when {
        platform == Platform.ANDROID -> {
            val awake = InputParsers.wakefulness(adbShell("dumpsys", "power").text)
            val volume = InputParsers.volume(adbShell("cmd", "media_session", "volume", "--stream", "3", "--get").text)
            listOfNotNull(awake?.let { "screen $it" }, volume?.let { "media volume $it" }).joinToString(", ").ifEmpty { null }
        }
        xcrun != null -> InputParsers.locked(
            runner.run(listOf(xcrun, "simctl", "spawn", deviceId, "notifyutil", "-g", "com.apple.springboard.lockstate")).text,
        )?.let { if (it) "locked" else "unlocked" }
        else -> null
    }

    fun clipboardGet(): String = requireMobilecli().clipboardGet(mobilecliId!!)

    fun clipboardSet(text: String): String {
        requireMobilecli().clipboardSet(mobilecliId!!, text)
        return if (text.isEmpty()) "Clipboard cleared" else "Clipboard set (${text.length} characters)"
    }

    /** Sets the clipboard, then presses the paste combo: ctrl+v on Android, cmd+v on iOS (which shows an Allow Paste prompt on the device). */
    fun setAndPaste(text: String): String {
        clipboardSet(text)
        mobilecli!!.keys(mobilecliId!!, listOf(if (platform == Platform.ANDROID) "ctrl+v" else "cmd+v"))
        return if (platform == Platform.ANDROID) "Clipboard set and paste pressed"
        else "Clipboard set and paste pressed. Tap Allow Paste in the dialog on the device; the plugin cannot confirm the paste"
    }

    private fun requireMobilecli(): MobilecliClient {
        check(hasMobilecli) { CLIPBOARD_NEEDS_MOBILECLI }
        return mobilecli!!
    }

    companion object {
        const val CLIPBOARD_NEEDS_MOBILECLI =
            "Clipboard needs mobilecli (adb cannot read or set the clipboard on recent Android versions; simctl pbcopy did not work on the tested simulator)"
    }
}
