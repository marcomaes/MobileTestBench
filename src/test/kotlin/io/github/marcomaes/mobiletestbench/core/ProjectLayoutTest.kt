package io.github.marcomaes.mobiletestbench.core

import com.google.gson.JsonParser
import io.github.marcomaes.mobiletestbench.core.apps.Apps
import io.github.marcomaes.mobiletestbench.core.apps.ForegroundApp
import io.github.marcomaes.mobiletestbench.core.project.ForegroundText
import io.github.marcomaes.mobiletestbench.core.project.LastDevice
import io.github.marcomaes.mobiletestbench.core.project.LayoutPatch
import io.github.marcomaes.mobiletestbench.core.project.ProjectLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectLayoutTest {
    private val pixel = Device("emulator-5554", "Pixel_9", Platform.ANDROID, DeviceKind.EMULATOR, state = "device")
    private val iphone = Device("F236", "iPhone 17 Pro", Platform.IOS, DeviceKind.SIMULATOR, state = "Booted")

    @Test fun normalizedFixesBadProportions() {
        val l = ProjectLayout(mirrorProportion = Float.NaN, toolsProportion = 1.5f, toolsTab = " Log ").normalized()
        assertEquals(ProjectLayout.DEFAULT_MIRROR, l.mirrorProportion)
        assertEquals(ProjectLayout.DEFAULT_TOOLS, l.toolsProportion)
        assertEquals("Log", l.toolsTab)
    }

    @Test fun keepsGoodProportions() {
        assertEquals(0.3f, ProjectLayout(mirrorProportion = 0.3f).normalized().mirrorProportion)
    }

    @Test fun mergeOnlyChangesSetFields() {
        val base = ProjectLayout(lastDeviceId = "a", inspectorOpen = true, toolsTab = "Apps")
        val m = base.merge(LayoutPatch(toolsOpen = true, toolsProportion = 0.4f))
        assertEquals("a", m.lastDeviceId)
        assertEquals(true, m.inspectorOpen)
        assertEquals(true, m.toolsOpen)
        assertEquals("Apps", m.toolsTab)
        assertEquals(0.4f, m.toolsProportion)
    }

    @Test fun withDevice() {
        val l = ProjectLayout().withDevice(pixel)
        assertEquals("emulator-5554", l.lastDeviceId)
        assertEquals("Pixel_9", l.lastDeviceName)
    }

    @Test fun tabIn() {
        assertEquals("Apps", ProjectLayout(toolsTab = "Apps").tabIn(listOf("Log", "Apps")))
        assertNull(ProjectLayout(toolsTab = "Gone").tabIn(listOf("Log")))
        assertNull(ProjectLayout().tabIn(listOf("Log")))
    }

    @Test fun lastDeviceByIdThenName() {
        val devices = listOf(pixel, iphone)
        assertEquals(iphone, LastDevice.find(devices, "F236", ""))
        // the emulator serial changed after a restart: same name
        assertEquals(pixel, LastDevice.find(devices, "emulator-5556", "Pixel_9"))
        assertNull(LastDevice.find(devices, "x", "Nexus"))
        assertNull(LastDevice.find(devices, "", ""))
    }

    @Test fun foregroundLabel() {
        assertEquals("Maps (com.maps)", ForegroundText.label(ForegroundApp("com.maps", "Maps")))
        assertEquals("com.x", ForegroundText.label(ForegroundApp("com.x", "com.x")))
        assertEquals("Home screen", ForegroundText.label(ForegroundApp(ForegroundApp.IOS_HOME, "SpringBoard")))
        assertEquals(ForegroundText.UNKNOWN, ForegroundText.label(null))
        assertEquals("com.maps", ForegroundText.copyText(ForegroundApp("com.maps", "Maps")))
        assertNull(ForegroundText.copyText(null))
    }

    @Test fun foregroundFromMobilecliData() {
        val data = JsonParser.parseString("""{"packageName":"com.mobilenext.playground","appName":"Playground","activity":".Main"}""")
        val app = Apps.parseForeground(data)
        assertEquals("Playground (com.mobilenext.playground)", ForegroundText.label(app))
        assertEquals(ForegroundText.UNKNOWN, ForegroundText.label(Apps.parseForeground(JsonParser.parseString("{}"))))
    }
}
