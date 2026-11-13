package dev.claudechat

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager

class SendSelectionAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.getData(CommonDataKeys.EDITOR)?.selectionModel?.hasSelection() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val text = editor.selectionModel.selectedText ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val tw = ToolWindowManager.getInstance(project).getToolWindow("Claude Chat") ?: return
        tw.show {
            ChatToolWindowFactory.ensurePanel(project, tw)
                .insertSnippet(file?.name ?: "", file?.extension ?: "", text)
        }
    }
}
