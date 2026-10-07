package io.github.marcomaes.mobiletestbench.core.discovery

import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.DeviceKind
import io.github.marcomaes.mobiletestbench.core.android.AdbClient
import io.github.marcomaes.mobiletestbench.core.exec.CommandFailedException
import io.github.marcomaes.mobiletestbench.core.ios.MobilecliClient
import io.github.marcomaes.mobiletestbench.core.ios.SimctlClient
import io.github.marcomaes.mobiletestbench.core.power.OfflineDevices

data class DiscoveryResult(val devices: List<Device>, val warnings: List<String>)

class DeviceDiscovery(
    private val adb: AdbClient,
    private val simctl: SimctlClient?,
    private val mobilecli: MobilecliClient? = null,
) {
    fun discover(): DiscoveryResult {
        val warnings = mutableListOf<String>()
        val devices = mutableListOf<Device>()

        try {
            devices += AdbDevicesParser.parse(adb.devicesLongFormat()).map(::enrichAndroid)
        } catch (e: Exception) {
            warnings += if (e is java.io.IOException && e !is CommandFailedException) {
                // The process did not start at all: adb is not installed or the configured path is wrong.
                "Android: adb was not found (${adb.executable}). Install the Android SDK platform-tools or set the adb path in Settings | Tools | Mobile Test Bench."
            } else {
                "Android: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        if (simctl != null) {
            try {
                devices += SimctlDevicesParser.parse(simctl.listBootedJson())
            } catch (e: Exception) {
                warnings += "iOS: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        if (mobilecli != null) {
            try {
                devices += MobilecliDevicesParser.parse(mobilecli.realIosDevicesJson())
            } catch (e: Exception) {
                warnings += "iOS devices (mobilecli): ${e.message ?: e.javaClass.simpleName}"
            }
        }

        if (mobilecli != null) {
            try {
                devices += OfflineDevices.missingFrom(devices, mobilecli.devicesIncludingOfflineJson())
            } catch (e: Exception) {
                warnings += "Offline devices (mobilecli): ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return DiscoveryResult(devices, warnings)
    }

    private fun enrichAndroid(device: Device): Device {
        if (!device.isUsable) return device
        val avdName = if (device.kind == DeviceKind.EMULATOR) adb.avdName(device.id) else null
        val name = avdName?.replace('_', ' ')
        val os = runCatching { adb.osVersion(device.id) }.getOrNull()
        val avd = if (device.kind == DeviceKind.EMULATOR) avdName else null
        return device.copy(name = name ?: device.name, osVersion = os, avdName = avd)
    }
}
