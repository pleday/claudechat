package dev.claudechat

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager

/** Sous-menu « Claude Chat » du clic droit dans l'éditeur (visible si du texte est sélectionné). */
class ClaudeActionsGroup : ActionGroup() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.getData(CommonDataKeys.EDITOR)?.selectionModel?.hasSelection() == true
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val send = ActionManager.getInstance().getAction("ClaudeChat.SendSelection")
        return listOfNotNull(
            PromptAction("Expliquer", "explain"),
            PromptAction("Corriger", "fix"),
            PromptAction("Générer des tests", "tests"),
            PromptAction("Documenter", "doc"),
            PromptAction("Revue de code", "review"),
            send,
        ).toTypedArray()
    }
}

class PromptAction(text: String, private val command: String) : DumbAwareAction(text) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val text = editor.selectionModel.selectedText ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val tw = ToolWindowManager.getInstance(project).getToolWindow("Claude Chat") ?: return
        tw.show {
            ChatToolWindowFactory.ensurePanel(project, tw)
                .ask(command, file?.name ?: "", file?.extension ?: "", text)
        }
    }
}
