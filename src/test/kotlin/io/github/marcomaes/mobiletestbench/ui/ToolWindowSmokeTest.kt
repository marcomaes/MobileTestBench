package io.github.marcomaes.mobiletestbench.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Builds the whole tool window content. Every Device Tools tab is created in the constructor, so a tab that fails
 * to build (a bad cast, a missing resource) takes the whole tool window down: that is what this test is for.
 */
class ToolWindowSmokeTest : BasePlatformTestCase() {

    fun testToolWindowContentBuilds() {
        val panel = DeviceViewerPanel(project)
        try {
            assertNotNull(panel.component)
        } finally {
            Disposer.dispose(panel)
        }
    }
}
