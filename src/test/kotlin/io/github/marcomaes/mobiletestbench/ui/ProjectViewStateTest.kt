package io.github.marcomaes.mobiletestbench.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.marcomaes.mobiletestbench.core.project.ProjectLayout
import io.github.marcomaes.mobiletestbench.settings.ProjectViewState

class ProjectViewStateTest : BasePlatformTestCase() {
    fun testServiceLoadsAndSaves() {
        val service = project.getService(ProjectViewState::class.java)
        assertNotNull(service)
        val layout = ProjectLayout("emulator-5554", "Pixel_9", true, true, "Apps", 0.4f, 0.6f)
        service.save(layout)
        assertEquals(layout, service.layout())

        // a state written to the workspace file comes back through loadState
        val copy = ProjectViewState.ViewState()
        copy.lastDeviceName = "iPhone"
        copy.mirrorProportion = 7f // invalid, falls back to the default
        service.loadState(copy)
        assertEquals("iPhone", service.layout().lastDeviceName)
        assertEquals(ProjectLayout.DEFAULT_MIRROR, service.layout().mirrorProportion)
    }
}
