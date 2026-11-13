package dev.claudechat

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.Change as VcsChange

/** Bouton « Générer le message de commit » dans la zone de message de commit. */
class GenerateCommitMessageAction : DumbAwareAction(
    "Générer le message de commit avec Claude", null, AllIcons.Actions.IntentionBulb,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val ctrl = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return
        val settings = ClaudeSettings.get()
        val selected = e.getData(VcsDataKeys.CHANGES)?.toList().orEmpty()
        val model = settings.state.model

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Claude : message de commit", true) {
            override fun run(indicator: ProgressIndicator) {
                val changes = selected.ifEmpty { ChangeListManager.getInstance(project).defaultChangeList.changes.toList() }
                if (changes.isEmpty()) {
                    Notifier.warn(project, "Aucun changement à décrire.")
                    return
                }
                val diff = buildDiff(changes, indicator)
                val out = StringBuilder()
                try {
                    out.append(CliTurn.oneShot(settings.cliPath(), project.basePath ?: ".", model, PROMPT + diff) { indicator.isCanceled })
                } catch (ex: Exception) {
                    Notifier.error(project, "Génération impossible : ${ex.message}")
                    return
                }
                val msg = out.toString().trim().removePrefix("```").removeSuffix("```").trim()
                if (msg.isNotEmpty() && !indicator.isCanceled) {
                    ApplicationManager.getApplication().invokeLater({ ctrl.setCommitMessage(msg) }, ModalityState.any())
                }
            }
        })
    }

    private fun buildDiff(changes: Collection<VcsChange>, indicator: ProgressIndicator): String {
        val sb = StringBuilder()
        for (c in changes) {
            if (sb.length > Constants.commitDiffMaxChars) { sb.append("\n[... diff tronqué ...]"); break }
            val path = (c.afterRevision ?: c.beforeRevision)?.file?.path ?: continue
            val before = runCatching { c.beforeRevision?.content }.getOrNull()
            val after = runCatching { c.afterRevision?.content }.getOrNull()
            sb.append("--- ").append(path).append('\n')
            when {
                before == null && after != null -> sb.append("(nouveau fichier)\n").append(after.take(6000)).append('\n')
                after == null -> sb.append("(fichier supprimé)\n")
                before != null -> {
                    val b = before.lines()
                    val a = after.lines()
                    val frags = runCatching {
                        ComparisonManager.getInstance().compareLines(before, after, ComparisonPolicy.DEFAULT, indicator)
                    }.getOrDefault(emptyList())
                    for (f in frags) {
                        sb.append("@@ -${f.startLine1 + 1} +${f.startLine2 + 1} @@\n")
                        for (i in f.startLine1 until f.endLine1) if (i < b.size) sb.append('-').append(b[i]).append('\n')
                        for (i in f.startLine2 until f.endLine2) if (i < a.size) sb.append('+').append(a[i]).append('\n')
                    }
                }
            }
        }
        return sb.toString()
    }

    companion object {
        val PROMPT: String = Constants.commitPrompt
    }
}
