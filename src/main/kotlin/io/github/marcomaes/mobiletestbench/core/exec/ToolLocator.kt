package io.github.marcomaes.mobiletestbench.core.exec

import java.io.File

/** Finds adb and xcrun without relying on the IDE having the user's shell PATH. */
class ToolLocator(
    private val environment: Map<String, String>,
    private val osName: String = System.getProperty("os.name"),
    private val userHome: String = System.getProperty("user.home"),
    private val fallbackDirs: List<String> = listOf("/opt/homebrew/bin", "/usr/local/bin"),
) {
    val isMac: Boolean get() = osName.lowercase().contains("mac")
    val isWindows: Boolean get() = osName.lowercase().contains("win")

    fun adb(): String {
        val exe = if (isWindows) "adb.exe" else "adb"
        val sdkRoots = buildList {
            environment["ANDROID_HOME"]?.let { add(it) }
            environment["ANDROID_SDK_ROOT"]?.let { add(it) }
            when {
                isMac -> add("$userHome/Library/Android/sdk")
                isWindows -> environment["LOCALAPPDATA"]?.let { add("$it\\Android\\Sdk") }
                else -> add("$userHome/Android/Sdk")
            }
        }
        sdkRoots.map { File(it, "platform-tools/$exe") }.firstOrNull { it.canExecute() }?.let { return it.path }
        return findOnPath(exe) ?: exe
    }

    fun xcrun(): String = if (File("/usr/bin/xcrun").canExecute()) "/usr/bin/xcrun" else "xcrun"

    /** The mobilecli npm/brew binary, or null when it is not installed (physical iOS then stays unavailable). */
    fun mobilecli(): String? {
        findOnPath("mobilecli")?.let { return it }
        return fallbackDirs.map { File(it, "mobilecli") }.firstOrNull { it.canExecute() }?.path
    }

    private fun findOnPath(exe: String): String? {
        val path = environment["PATH"] ?: return null
        return path.split(File.pathSeparator)
            .map { File(it, exe) }
            .firstOrNull { it.canExecute() }
            ?.path
    }
}
