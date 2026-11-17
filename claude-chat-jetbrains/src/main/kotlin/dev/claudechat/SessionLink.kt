package dev.claudechat

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.JBProtocolCommand
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lien vers une conversation : `jetbrains://<produit>/claude-chat/<session>[?cwd=<dossier>]`. */
data class SessionLink(val id: String, val cwd: String?)

object SessionLinks {
    /** Identifiants de session de la CLI (UUID) ; refuse tout ce qui pourrait sortir du dossier des sessions. */
    private val ID = Regex("^[0-9A-Za-z-]{8,64}$")

    /** Lit la cible (`<session>` ou `session/<session>`) et les paramètres d'un lien ; null si ce n'est pas une session valide. */
    fun parse(target: String?, parameters: Map<String, String>): SessionLink? {
        val id = target?.trim('/')?.removePrefix("session/")?.takeIf { ID.matches(it) } ?: return null
        return SessionLink(id, parameters["cwd"]?.trim()?.takeIf { it.isNotEmpty() })
    }
}

/**
 * Ramène une conversation à l'écran depuis une autre application : sélectionne l'onglet qui porte la session, ou la charge
 * dans un nouvel onglet si elle n'est pas ouverte. `idea jetbrains://idea/claude-chat/<session>` suffit à l'invoquer.
 */
class OpenSessionCommand : JBProtocolCommand("claude-chat") {
    override suspend fun execute(target: String?, parameters: Map<String, String>, fragment: String?): String? {
        val link = SessionLinks.parse(target, parameters)
            ?: return "Lien Claude Chat invalide : jetbrains://<produit>/claude-chat/<session> est attendu"
        val project = withContext(Dispatchers.EDT) { pickProject(link) } ?: return "Claude Chat : aucun projet ouvert"
        withContext(Dispatchers.EDT) { ProjectUtil.focusProjectWindow(project, true) }
        if (withContext(Dispatchers.EDT) { ChatToolWindowFactory.selectTab(project, link.id) }) return null
        val cwd = project.basePath ?: return "Claude Chat : le projet n'a pas de dossier"
        val session = withContext(Dispatchers.IO) {
            SessionStore.list(cwd, 0, Int.MAX_VALUE, true).firstOrNull { it.id == link.id }?.let { SessionStore.load(it) }
        } ?: return "Claude Chat : la session ${link.id} est introuvable dans ce projet"
        withContext(Dispatchers.EDT) { ChatToolWindowFactory.openTab(project, session) }
        return null
    }

    /** Le projet qui a déjà la conversation ouverte, sinon celui du dossier demandé, sinon le premier ouvert. */
    private fun pickProject(link: SessionLink): Project? {
        val open = ProjectManager.getInstance().openProjects.filter { !it.isDisposed }
        return open.firstOrNull { ChatToolWindowFactory.hasTab(it, link.id) }
            ?: open.firstOrNull { link.cwd != null && it.basePath == link.cwd }
            ?: open.firstOrNull()
    }
}
