package io.github.marcomaes.mobiletestbench.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.marcomaes.mobiletestbench.settings.MobileTestBenchConfigurable
import io.github.marcomaes.mobiletestbench.settings.MobileTestBenchSettings

/** The Recording tab with its replay buttons and the Settings page with the replay options must build. */
class ReplayUiSmokeTest : BasePlatformTestCase() {

    fun testRecorderPanelBuilds() {
        val panel = RecorderPanel(project, {}, {}, {}, {})
        panel.setSteps(emptyList(), recording = false)
        panel.setReplaying(true)
        panel.setReplaying(false)
        assertNotNull(panel)
    }

    fun testSettingsPageBuildsAndHasReplayDefaults() {
        val state = MobileTestBenchSettings.getInstance().state
        assertEquals(10, state.replayTimeoutSeconds)
        assertEquals(0, state.replayDelayMs)
        val configurable = MobileTestBenchConfigurable()
        assertNotNull(configurable.createComponent())
        configurable.disposeUIResources()
    }
}
