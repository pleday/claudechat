package dev.claudechat

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory

/** Fenêtre d'outils avec plusieurs conversations en onglets (FR-53). */
class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.setTitleActions(listOf(object : DumbAwareAction("Nouvel onglet", "Ouvrir une nouvelle conversation", AllIcons.General.Add) {
            override fun actionPerformed(e: AnActionEvent) { openTab(project, null) }
        }))
        addTab(project, toolWindow)
        // Fermer le dernier onglet en recrée un vierge, pour que la fenêtre ne reste jamais vide.
        toolWindow.contentManager.addContentManagerListener(object : com.intellij.ui.content.ContentManagerListener {
            override fun contentRemoved(event: com.intellij.ui.content.ContentManagerEvent) {
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    if (!toolWindow.isDisposed && toolWindow.contentManager.contentCount == 0) addTab(project, toolWindow)
                }
            }
        })
    }

    companion object {
        const val ID = "Claude Chat"

        fun addTab(project: Project, tw: ToolWindow): ChatPanel {
            val disposer = Disposer.newDisposable("ClaudeChatTab")
            Disposer.register(tw.disposable, disposer)
            val panel = ChatPanel(project, disposer)
            Disposer.register(disposer) { panel.shutdown() }
            val content = ContentFactory.getInstance().createContent(panel, "Nouvelle conversation", false).apply {
                isCloseable = true
                setDisposer(disposer)
            }
            panel.content = content
            tw.contentManager.addContent(content)
            tw.contentManager.setSelectedContent(content)
            return panel
        }

        /** Ouvre un onglet (vide, ou chargé avec une session existante). */
        fun openTab(project: Project, session: Session?) {
            val tw = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
            tw.show { addTab(project, tw).also { p -> session?.let(p::loadSession) } }
        }

        /** Panneau de l'onglet sélectionné ; en crée un si tous les onglets ont été fermés. */
        fun ensurePanel(project: Project, tw: ToolWindow): ChatPanel =
            tw.contentManager.selectedContent?.component as? ChatPanel ?: addTab(project, tw)
    }
}
