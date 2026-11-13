package dev.claudechat

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.openapi.vcs.changes.ui.SimpleChangesBrowser
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.SlowOperations
import com.intellij.vcsUtil.VcsUtil
import javax.swing.AbstractAction
import java.awt.event.ActionEvent

/**
 * Fichiers modifiés par Claude, présentés avec les composants natifs de l'IDE : l'arbre des changements de la fenêtre
 * Version Control (groupement par dossier, icônes, menu), et son outil de comparaison (Ctrl+D, double-clic, navigation
 * d'un fichier au suivant). Chaque fichier est un `Change` « avant Claude » → « état actuel ».
 */
object ChangedFilesView {
    /** « 3 fichiers modifiés par Claude · +42 −7 » (ligne au-dessus du champ de saisie). */
    fun summary(files: List<ChangedFile>): String {
        val n = files.size
        return "$n fichier${if (n > 1) "s" else ""} modifié${if (n > 1) "s" else ""} par Claude · +${files.sumOf { it.added }} −${files.sumOf { it.removed }}"
    }

    /**
     * Les `Change` de l'IDE pour [files] : avant = contenu mémorisé (absent pour un fichier créé), après = contenu actuel du
     * disque (absent pour un fichier supprimé). Le VFS est rafraîchi par l'appelant (hors EDT) pour les fichiers créés.
     */
    fun toChanges(files: List<ChangedFile>): List<Change> = files.map { c ->
        val path = VcsUtil.getFilePath(c.path, false)
        val before = c.before?.let { SimpleContentRevision(it, path, "Avant Claude") }
        val after = if (c.status == ChangeStatus.DELETED) null else CurrentContentRevision(path)
        Change(before, after)
    }

    /**
     * Ouvre l'arbre des changements de l'IDE sur [files] ; [onClear] vide le suivi (les prochaines modifications repartent de
     * l'état actuel des fichiers). Le VFS est rafraîchi hors EDT : les fichiers créés par Claude n'y sont pas forcément connus.
     */
    fun show(project: Project, files: List<ChangedFile>, onClear: () -> Unit) {
        if (files.isEmpty()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val fs = LocalFileSystem.getInstance()
            val known = files.mapNotNull { fs.refreshAndFindFileByPath(it.path) }
            // L'arbre des changements consulte l'index de fichiers (module de chaque fichier) dans son constructeur. Un fichier
            // tout juste créé le rend périmé : on le met à jour ici, hors EDT, où c'est permis et sans geler l'interface.
            ApplicationManager.getApplication().runReadAction {
                val index = ProjectFileIndex.getInstance(project)
                known.forEach { index.getModuleForFile(it) }
            }
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                // garde-fou : si l'index a encore bougé entre-temps, l'IDE signalerait une « opération lente sur l'EDT » alors que
                // ce code ne lui appartient pas (constructeur de SimpleChangesBrowser)
                val browser = SlowOperations.allowSlowOperations(ThrowableComputable<SimpleChangesBrowser, RuntimeException> {
                    SimpleChangesBrowser(project, toChanges(files))
                })
                DialogBuilder(project).apply {
                    setTitle("Fichiers modifiés par Claude")
                    setCenterPanel(browser)
                    setPreferredFocusComponent(browser.preferredFocusedComponent)
                    addAction(object : AbstractAction("Effacer la liste") {
                        init { putValue(SHORT_DESCRIPTION, "Oublier ces modifications : la prochaine comparaison repart de l'état actuel des fichiers") }
                        override fun actionPerformed(e: ActionEvent?) { dialogWrapper.close(0); onClear() }
                    })
                    addCloseButton()
                }.showNotModal()
            }, ModalityState.defaultModalityState())
        }
    }
}
