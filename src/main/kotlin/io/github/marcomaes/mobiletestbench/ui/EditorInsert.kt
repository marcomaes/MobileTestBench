package io.github.marcomaes.mobiletestbench.ui

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project

/** Writes text at the caret of the editor that is open in the IDE. */
object EditorInsert {
    /** Returns null on success, otherwise why nothing was inserted. */
    fun insertAtCaret(project: Project, text: String): String? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        return when {
            editor == null -> "No editor open to insert into"
            editor.isViewer -> "The editor is read-only"
            else -> {
                WriteCommandAction.runWriteCommandAction(project, "Insert Locator", null, {
                    EditorModificationUtil.insertStringAtCaret(editor, text)
                })
                null
            }
        }
    }
}
