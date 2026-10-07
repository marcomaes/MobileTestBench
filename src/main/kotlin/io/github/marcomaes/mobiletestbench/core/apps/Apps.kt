package io.github.marcomaes.mobiletestbench.core.apps

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.DeviceKind
import io.github.marcomaes.mobiletestbench.core.Platform

/** One installed app as listed by `mobilecli apps list`. */
data class AppInfo(val id: String, val name: String, val version: String = "")

/** The app in front. [activity] is Android only; [isHome] is true on the iOS home screen (SpringBoard). */
data class ForegroundApp(val id: String, val name: String, val activity: String? = null) {
    val isHome: Boolean get() = id == IOS_HOME

    companion object {
        const val IOS_HOME = "com.apple.springboard"
    }
}

/** Pure parsing, command building and validation for the app manager. */
object Apps {

    /** Parses the `data` of `apps list`, sorted by name (case-insensitive). Entries without a package name are dropped. */
    fun parseList(data: JsonElement?): List<AppInfo> {
        val array = data?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.let(::app) }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
    }

    private fun app(o: JsonObject): AppInfo? {
        val id = str(o, "packageName") ?: return null
        return AppInfo(id, str(o, "appName") ?: id, str(o, "version") ?: "")
    }

    /** Parses the `data` of `apps foreground`; null when there is none. */
    fun parseForeground(data: JsonElement?): ForegroundApp? {
        val o = data?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val id = str(o, "packageName") ?: return null
        return ForegroundApp(id, str(o, "appName") ?: id, str(o, "activity"))
    }

    private fun str(o: JsonObject, key: String): String? =
        o.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    /** Case-insensitive match of [query] against name or id; a blank query matches everything. */
    fun filter(apps: List<AppInfo>, query: String): List<AppInfo> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return apps
        return apps.filter { it.name.lowercase().contains(q) || it.id.lowercase().contains(q) }
    }

    /** Package ids of `pm list packages -3` output (`package:com.x` lines); the third-party (user installed) apps. */
    fun parseUserPackages(text: String): Set<String> =
        text.lineSequence().map { it.trim() }.filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").substringAfterLast('=').trim() }.filter { it.isNotEmpty() }.toSet()

    /**
     * mobilecli's list has no system flag. On Android an app is system when it is missing from [userPackages]
     * (`pm list packages -3`); with no such list (null) nothing counts as system. On iOS the `com.apple.` bundle id prefix marks system apps.
     */
    fun isSystem(device: Device, app: AppInfo, userPackages: Set<String>?): Boolean = when (device.platform) {
        Platform.ANDROID -> userPackages != null && app.id !in userPackages
        Platform.IOS -> app.id.startsWith("com.apple.")
    }

    /** Drops the system apps when [hide] is set; the app with id [keepId] (the selected one) always stays. */
    fun hideSystem(apps: List<AppInfo>, device: Device, userPackages: Set<String>?, hide: Boolean, keepId: String? = null): List<AppInfo> =
        if (!hide) apps else apps.filter { it.id == keepId || !isSystem(device, it, userPackages) }

    /** `mobilecli apps clear` works on Android and on iOS simulators, not on physical iPhones. */
    fun canClearData(device: Device): Boolean = device.platform == Platform.ANDROID || device.kind == DeviceKind.SIMULATOR

    /** Android-style activity names (`.MainActivity`, `com.x.Main`) only make sense on Android. */
    fun supportsActivity(device: Device): Boolean = device.platform == Platform.ANDROID

    /** Arguments (without `--device`) for `apps launch`. Blank locale or activity are left out; the activity only on Android. */
    fun launchArgs(device: Device, id: String, locale: String = "", activity: String = ""): List<String> = buildList {
        add("apps"); add("launch"); add(id)
        if (locale.isNotBlank()) { add("--locale"); add(locale.trim().replace(" ", "")) }
        if (activity.isNotBlank() && supportsActivity(device)) { add("--activity"); add(activity.trim()) }
    }

    fun terminateArgs(id: String) = listOf("apps", "terminate", id)
    fun clearArgs(id: String) = listOf("apps", "clear", id)
    fun uninstallArgs(id: String) = listOf("apps", "uninstall", id)
    fun installArgs(path: String) = listOf("apps", "install", path)

    /** Why [path] cannot be installed on [device] (null when it can). Checks the extension only; mobilecli checks the content. */
    fun installProblem(device: Device, path: String): String? {
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext !in INSTALL_EXTENSIONS) return "Only .apk, .zip and .ipa files can be installed"
        return when {
            device.platform == Platform.ANDROID && ext != "apk" -> "An Android device needs an .apk file"
            device.platform == Platform.IOS && device.kind == DeviceKind.SIMULATOR && ext == "apk" -> "An iOS simulator needs a .zip (zipped .app) file"
            device.platform == Platform.IOS && device.kind == DeviceKind.SIMULATOR && ext == "ipa" -> "An iOS simulator needs a .zip (zipped .app) file, an .ipa is for real devices"
            device.platform == Platform.IOS && device.kind == DeviceKind.PHYSICAL && ext != "ipa" -> "A real iPhone needs an .ipa file"
            else -> null
        }
    }

    val INSTALL_EXTENSIONS = setOf("apk", "zip", "ipa")

    /** Turns opaque simctl failures into something readable; other messages pass through. Terminating an installed app that is not running succeeds, so status 3 means not installed. */
    fun friendlyError(message: String): String = when {
        message.contains("exit status 3") -> "$message (app is not installed)"
        message.contains("exit status 4") -> "$message (app is not installed)"
        else -> message
    }
}

/** Lists the user installed (third-party) Android packages with `adb shell pm list packages -3`. Blocking. */
class AndroidPackages(private val adb: String, private val serial: String, private val runner: io.github.marcomaes.mobiletestbench.core.exec.CommandRunner) {
    fun userPackages(): Set<String> {
        val r = runner.run(listOf(adb, "-s", serial, "shell", "pm", "list", "packages", "-3"), java.time.Duration.ofSeconds(15))
        if (!r.ok) error(r.stderr.lineSequence().firstOrNull { it.isNotBlank() } ?: "pm list packages failed")
        return Apps.parseUserPackages(r.text)
    }
}
