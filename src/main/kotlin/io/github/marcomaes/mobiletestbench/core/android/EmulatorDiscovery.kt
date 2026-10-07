package io.github.marcomaes.mobiletestbench.core.android

import java.io.File

data class EmulatorGrpcEndpoint(val host: String, val port: Int, val token: String?)

/**
 * Every running emulator writes a `pid_<pid>.ini` discovery file with its console port,
 * gRPC port and (optional) gRPC auth token. Android Studio uses the same files.
 */
object EmulatorDiscovery {

    fun parseIni(text: String): Map<String, String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .associate { line -> line.substringBefore('=').trim() to line.substringAfter('=').trim() }

    /** "emulator-5554" -> 5554 */
    fun consolePort(serial: String): Int? = serial.removePrefix("emulator-").takeIf { serial.startsWith("emulator-") }?.toIntOrNull()

    fun endpointFromIni(values: Map<String, String>): EmulatorGrpcEndpoint? {
        val port = values["grpc.port"]?.toIntOrNull() ?: return null
        val token = values["grpc.token"]?.takeIf { it.isNotBlank() }
        return EmulatorGrpcEndpoint("localhost", port, token)
    }

    fun findEndpoint(serial: String, directories: List<File>): EmulatorGrpcEndpoint? {
        val console = consolePort(serial) ?: return null
        return directories.asSequence()
            .filter { it.isDirectory }
            .flatMap { dir -> dir.listFiles { f -> f.name.startsWith("pid_") && f.name.endsWith(".ini") }.orEmpty().asSequence() }
            .map { parseIni(it.readText()) }
            .firstOrNull { it["port.serial"]?.toIntOrNull() == console }
            ?.let(::endpointFromIni)
    }

    fun defaultDirectories(env: Map<String, String>, osName: String, userHome: String): List<File> {
        val os = osName.lowercase()
        return buildList {
            env["ANDROID_EMULATOR_HOME"]?.let { add(File(it, "running")) }
            when {
                os.contains("mac") -> add(File(userHome, "Library/Caches/TemporaryItems/avd/running"))
                os.contains("win") -> env["LOCALAPPDATA"]?.let { add(File(it, "Temp/avd/running")) }
                else -> {
                    env["XDG_RUNTIME_DIR"]?.let { add(File(it, "avd/running")) }
                    add(File("/tmp/android-${env["USER"] ?: System.getProperty("user.name")}/avd/running"))
                }
            }
        }
    }
}
