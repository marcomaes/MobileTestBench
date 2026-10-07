package io.github.marcomaes.mobiletestbench.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.Service
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.wm.ToolWindowManager
import io.github.marcomaes.mobiletestbench.core.inspector.ShowLocatorResult

/** What the editor action talks to: the device viewer in the tool window. */
interface LocatorTarget {
    /** Tries the locator in [text] (a selection or a line of code) on the connected device. Called on the EDT. */
    fun showLocator(text: String)
}

/** Project level registry where a [DeviceViewerPanel] registers itself, so editor actions can reach it. */
@Service(Service.Level.PROJECT)
class LocatorTargets {
    @Volatile var current: LocatorTarget? = null
        private set

    fun register(target: LocatorTarget) {
        current = target
    }

    /** Only the registered target may unregister itself (a second panel may have replaced it). */
    fun unregister(target: LocatorTarget) {
        if (current === target) current = null
    }
}

/** The text to look for a locator in: the selection, else the line with the caret. */
object EditorLocatorText {
    fun of(editor: Editor): String {
        editor.selectionModel.selectedText?.takeIf { it.isNotBlank() }?.let { return it }
        val document = editor.document
        if (document.textLength == 0) return ""
        val line = document.getLineNumber(editor.caretModel.offset.coerceIn(0, document.textLength))
        return document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
    }
}

/** Editor action "Show Locator on Device": evaluates the locator under the caret against the screen of the connected device. */
class ShowLocatorAction : AnAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val text = EditorLocatorText.of(editor)
        val targets = project.getService(LocatorTargets::class.java)
        val target = targets?.current
        if (target != null) return target.showLocator(text)
        // The tool window was never opened, so there is no viewer yet: open it, then try again.
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        if (toolWindow == null) return notifyNoDevice(project)
        toolWindow.show {
            val opened = targets?.current
            if (opened != null) opened.showLocator(text) else notifyNoDevice(project)
        }
    }

    private fun notifyNoDevice(project: Project) {
        NotificationGroupManager.getInstance().getNotificationGroup("Mobile Test Bench")
            .createNotification(ShowLocatorResult.NoDevice.message().replaceFirstChar { it.uppercase() }, NotificationType.WARNING)
            .notify(project)
    }

    companion object {
        const val TOOL_WINDOW_ID = "Mobile Devices"
    }
}
