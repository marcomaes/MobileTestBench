package io.github.marcomaes.mobiletestbench.core.discovery

import com.google.gson.JsonParser
import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.DeviceKind
import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.Size

object AdbDevicesParser {
    /** Parses `adb devices -l`. */
    fun parse(output: String): List<Device> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("List of devices") && !it.startsWith("*") }
            .mapNotNull { line ->
                val tokens = line.split(Regex("\\s+"))
                if (tokens.size < 2) return@mapNotNull null
                val serial = tokens[0]
                val state = tokens[1]
                val props = tokens.drop(2).filter { it.contains(':') }
                    .associate { it.substringBefore(':') to it.substringAfter(':') }
                val name = props["model"]?.replace('_', ' ') ?: serial
                Device(
                    id = serial,
                    name = name,
                    platform = Platform.ANDROID,
                    kind = if (serial.startsWith("emulator-")) DeviceKind.EMULATOR else DeviceKind.PHYSICAL,
                    state = state,
                )
            }
            .toList()
}

object SimctlDevicesParser {
    /** Parses `xcrun simctl list devices booted -j`. */
    fun parse(json: String): List<Device> {
        val devices = JsonParser.parseString(json).asJsonObject["devices"]?.asJsonObject ?: return emptyList()
        return devices.entrySet().flatMap { (runtime, list) ->
            val os = runtimeToVersion(runtime)
            list.asJsonArray.mapNotNull { element ->
                val obj = element.asJsonObject
                val state = obj["state"]?.asString ?: return@mapNotNull null
                if (state != "Booted") return@mapNotNull null
                Device(
                    id = obj["udid"].asString,
                    name = obj["name"]?.asString ?: "Simulator",
                    platform = Platform.IOS,
                    kind = DeviceKind.SIMULATOR,
                    osVersion = os,
                    state = state,
                )
            }
        }
    }

    /** "com.apple.CoreSimulator.SimRuntime.iOS-17-5" -> "iOS 17.5" */
    fun runtimeToVersion(runtime: String): String? {
        val tail = runtime.substringAfterLast("SimRuntime.", "").ifEmpty { return null }
        val parts = tail.split('-')
        if (parts.size < 2) return tail
        return parts.first() + " " + parts.drop(1).joinToString(".")
    }
}

/** Parses `mobilecli devices` (JSON). Only physical iOS devices: simulators come from simctl, Android from adb. */
object MobilecliDevicesParser {
    fun parse(json: String): List<Device> {
        val root = JsonParser.parseString(json).asJsonObject
        if (root["status"]?.asString == "error") error(root["error"]?.asString ?: "mobilecli failed")
        val devices = root["data"]?.asJsonObject?.get("devices")?.asJsonArray ?: return emptyList()
        return devices.map { it.asJsonObject }.mapNotNull { obj ->
            val type = obj["type"]?.asString?.lowercase()
            if (obj["platform"]?.asString != "ios" || type == "simulator") return@mapNotNull null
            val id = obj["id"]?.asString ?: return@mapNotNull null
            Device(
                id = id,
                name = obj["name"]?.asString ?: id,
                platform = Platform.IOS,
                kind = DeviceKind.PHYSICAL,
                osVersion = obj["version"]?.asString?.let { "iOS $it" },
                state = obj["state"]?.asString ?: "online",
            )
        }
    }
}

/** Parses the `screenSize` of `mobilecli device info` (iOS points). */
object MobilecliDeviceInfoParser {
    fun parseScreenSize(json: String): Size? {
        val size = JsonParser.parseString(json).asJsonObject["data"]?.asJsonObject
            ?.get("device")?.asJsonObject?.get("screenSize")?.asJsonObject ?: return null
        val w = size["width"]?.asInt ?: return null
        val h = size["height"]?.asInt ?: return null
        return Size(w, h).takeUnless { it.isEmpty }
    }
}
