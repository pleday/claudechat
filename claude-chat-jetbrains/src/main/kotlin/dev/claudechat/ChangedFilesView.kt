package dev.claudechat

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.openapi.vcs.changes.ui.SimpleAsyncChangesBrowser
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.AbstractAction
import javax.swing.JPanel
import java.awt.event.ActionEvent

/**
 * Fichiers modifiés par Claude, présentés avec les composants natifs de l'IDE : l'arbre des changements de la fenêtre
 * Version Control (groupement par dossier, icônes, menu), et son outil de comparaison (Ctrl+D, double-clic, navigation
 * d'un fichier au suivant). Chaque fichier est un `Change` « avant Claude » → « état actuel ».
 */
object ChangedFilesView {
    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(ChangedFilesView::class.java)

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

    /** Remet [files] dans leur état d'avant Claude : contenu restauré, ou fichier supprimé s'il a été créé ([deleteNew]). */
    fun rollback(files: List<ChangedFile>, deleteNew: Boolean) {
        for (f in files) runCatching {
            val p = Path.of(f.path)
            when {
                f.before != null -> { p.parent?.let { Files.createDirectories(it) }; Files.writeString(p, f.before) }
                deleteNew -> Files.deleteIfExists(p)
            }
        }.onFailure { LOG.warn("Rollback impossible pour ${f.path} : $it") }
        val fs = LocalFileSystem.getInstance()
        files.forEach { fs.refreshAndFindFileByPath(it.path)?.refresh(false, false) }
    }

    /** Fichiers existants parmi [changes] (un fichier supprimé n'a rien à ouvrir). */
    private fun openable(changes: Collection<Change>): List<VirtualFile> =
        changes.mapNotNull { c -> c.afterRevision?.file?.virtualFile?.takeIf { it.isValid && !it.isDirectory } }

    private fun Change.path(): String? = (afterRevision ?: beforeRevision)?.file?.path

    /**
     * Ouvre l'arbre des changements de l'IDE sur [files], avec une case à cocher par fichier (comme la fenêtre « Rollback » de
     * l'IDE). « Rollback » remet les fichiers cochés dans leur état d'avant Claude puis appelle [onRollback] avec ces fichiers ;
     * il rend les fichiers encore suivis, qui restent affichés. Le VFS est rafraîchi hors EDT : les fichiers créés par Claude
     * n'y sont pas forcément connus.
     */
    fun show(project: Project, files: List<ChangedFile>, onRollback: (List<ChangedFile>) -> List<ChangedFile>) {
        if (files.isEmpty()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val fs = LocalFileSystem.getInstance()
            files.forEach { fs.refreshAndFindFileByPath(it.path) }
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                // variante asynchrone : l'arbre est construit en arrière-plan (la version synchrone, dépréciée, consultait l'index
                // de fichiers sur l'EDT et provoquait des erreurs « opération lente »)
                val browser = SimpleAsyncChangesBrowser(project, true, false)
                val shown = files.toMutableList()
                fun display(list: List<ChangedFile>) {
                    val changes = toChanges(list)
                    browser.setChangesToDisplay(changes)
                    browser.viewer.setIncludedChanges(changes)
                }
                display(shown)
                val deleteNew = JBCheckBox("Supprimer les fichiers créés par Claude", true)
                val panel = JPanel(BorderLayout()).apply {
                    add(browser, BorderLayout.CENTER)
                    add(deleteNew, BorderLayout.SOUTH)
                    preferredSize = Dimension(JBUI.scale(700), JBUI.scale(480))
                }
                lateinit var rollbackAction: AbstractAction
                lateinit var openAction: AbstractAction
                fun updateButtons() {
                    rollbackAction.isEnabled = browser.includedChanges.isNotEmpty()
                    openAction.isEnabled = openable(browser.selectedChanges).isNotEmpty()
                }
                browser.viewer.setInclusionListener { updateButtons() }
                browser.viewer.addSelectionListener { updateButtons() }
                DialogBuilder(project).apply {
                    setTitle("Fichiers modifiés par Claude")
                    setCenterPanel(panel)
                    setPreferredFocusComponent(browser.preferredFocusedComponent)
                    openAction = object : AbstractAction("Ouvrir le fichier") {
                        init { putValue(SHORT_DESCRIPTION, "Ouvrir dans l'éditeur le fichier sélectionné") }
                        override fun actionPerformed(e: ActionEvent?) {
                            val vfs = openable(browser.selectedChanges)
                            vfs.forEach { OpenFileDescriptor(project, it).navigate(true) }
                        }
                    }
                    addAction(openAction)
                    rollbackAction = object : AbstractAction("Rollback") {
                        init { putValue(SHORT_DESCRIPTION, "Remettre les fichiers cochés dans leur état d'avant Claude") }
                        override fun actionPerformed(e: ActionEvent?) {
                            val paths = browser.includedChanges.mapNotNull { it.path() }.toSet()
                            val selected = shown.filter { it.path in paths }
                            if (selected.isEmpty()) return
                            isEnabled = false
                            val del = deleteNew.isSelected
                            ApplicationManager.getApplication().executeOnPooledThread {
                                rollback(selected, del)
                                ApplicationManager.getApplication().invokeLater({
                                    if (project.isDisposed) return@invokeLater
                                    val remaining = onRollback(selected)
                                    if (remaining.isEmpty()) dialogWrapper.close(0)
                                    else { shown.clear(); shown.addAll(remaining); display(shown); updateButtons() }
                                }, ModalityState.any())
                            }
                        }
                    }
                    addAction(rollbackAction)
                    addCloseButton()
                    updateButtons()
                }.showNotModal()
            }, ModalityState.defaultModalityState())
        }
    }
}
