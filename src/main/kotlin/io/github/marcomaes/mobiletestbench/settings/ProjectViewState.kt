package io.github.marcomaes.mobiletestbench.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import io.github.marcomaes.mobiletestbench.core.project.ProjectLayout

/** Per-project panel state (last device, layout), stored in the project's workspace file, not in the application settings. */
@Service(Service.Level.PROJECT)
@State(name = "MobileTestBenchProject", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ProjectViewState : SimplePersistentStateComponent<ProjectViewState.ViewState>(ViewState()) {

    class ViewState : BaseState() {
        var lastDeviceId by string("")
        var lastDeviceName by string("")
        var inspectorOpen by property(false)
        var toolsOpen by property(false)
        var toolsTab by string("")
        var mirrorProportion by property(ProjectLayout.DEFAULT_MIRROR)
        var toolsProportion by property(ProjectLayout.DEFAULT_TOOLS)
    }

    fun layout(): ProjectLayout = state.run {
        ProjectLayout(
            lastDeviceId.orEmpty(), lastDeviceName.orEmpty(), inspectorOpen, toolsOpen, toolsTab.orEmpty(),
            mirrorProportion, toolsProportion,
        ).normalized()
    }

    fun save(layout: ProjectLayout) {
        val l = layout.normalized()
        state.apply {
            lastDeviceId = l.lastDeviceId
            lastDeviceName = l.lastDeviceName
            inspectorOpen = l.inspectorOpen
            toolsOpen = l.toolsOpen
            toolsTab = l.toolsTab
            mirrorProportion = l.mirrorProportion
            toolsProportion = l.toolsProportion
        }
    }
}
