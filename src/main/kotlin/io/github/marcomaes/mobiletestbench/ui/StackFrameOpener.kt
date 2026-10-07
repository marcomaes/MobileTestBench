package io.github.marcomaes.mobiletestbench.ui

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.marcomaes.mobiletestbench.core.logs.StackFrame
import io.github.marcomaes.mobiletestbench.core.logs.StackFrames

/** Opens the source of a stack trace line in the project, by file name (platform file index only, no Java PSI). */
object StackFrameOpener {

    /** The project file for [frame]: by file name, narrowed by the package path when several files have that name. Needs a read action and smart mode. */
    fun find(project: Project, frame: StackFrame): VirtualFile? {
        val files = FilenameIndex.getVirtualFilesByName(frame.file, GlobalSearchScope.projectScope(project)).toList()
        val chosen = StackFrames.choose(files.map { it.path }, frame) ?: return null
        return files.first { it.path == chosen }
    }

    /** Looks the file up in the background (waiting for indexing) and opens it at the line; [report] gets a status text on the EDT. */
    fun open(project: Project, frame: StackFrame, report: (String) -> Unit) {
        report("Looking for ${frame.file}...")
        ReadAction.nonBlocking<VirtualFile?> { find(project, frame) }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { file ->
                if (file == null) {
                    report("${frame.file} is not in this project (${frame.className ?: "no class"})")
                } else {
                    OpenFileDescriptor(project, file, frame.line - 1, 0).navigate(true)
                    report("Opened ${file.name}:${frame.line}")
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }
}
