package dev.claudechat

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows

val PERMISSION_MODES = listOf(
    "manual" to "Demander",
    "acceptEdits" to "Modifs auto",
    "plan" to "Plan",
    "auto" to "Auto",
    "dontAsk" to "Sans demande",
    "bypassPermissions" to "Tout autoriser",
)

/** Explication de chaque mode de permission (infobulle). */
val PERMISSION_HELP = mapOf(
    "Demander" to "Claude demande ton accord avant chaque action sensible",
    "Modifs auto" to "Les modifications de fichiers sont acceptées sans demander ; le reste est demandé",
    "Plan" to "Claude propose un plan à valider avant d'agir",
    "Auto" to "Mode automatique de la CLI",
    "Sans demande" to "Tout ce qui demanderait une autorisation est refusé automatiquement",
    "Tout autoriser" to "Aucune demande : Claude agit librement (à réserver aux environnements sûrs)",
)
val EFFORTS = listOf("auto", "low", "medium", "high", "xhigh", "max")

class ClaudeConfigurable : BoundConfigurable("Claude Chat") {
    private val mcpTable = McpTablePanel()

    override fun apply() {
        super.apply()
        ApplicationManager.getApplication().executeOnPooledThread { RemoteBridge.reconfigure() }
    }

    override fun createPanel() = panel {
        val s = ClaudeSettings.get()
        val version = JBLabel("…")
        fun refreshVersion() {
            version.text = "…"
            ApplicationManager.getApplication().executeOnPooledThread {
                val v = CliTurn.run(s.cliPath(), "--version", timeoutSec = 20)
                ApplicationManager.getApplication().invokeLater({ version.text = v }, ModalityState.any())
            }
        }
        group("CLI claude") {
            row("Exécutable :") {
                textField().columns(COLUMNS_LARGE).bindText(s.state::cliPath)
                    .comment("Vide = ~/.local/bin/claude, sinon le PATH.")
            }
            row("Version :") {
                cell(version)
                button("Actualiser") { refreshVersion() }
                button("Mettre à jour la CLI") {
                    version.text = "Mise à jour…"
                    ApplicationManager.getApplication().executeOnPooledThread {
                        val out = CliTurn.run(s.cliPath(), "update", timeoutSec = 300)
                        ApplicationManager.getApplication().invokeLater({ version.text = out.lineSequence().lastOrNull() ?: ""; refreshVersion() }, ModalityState.any())
                    }
                }
            }
            row { refreshVersion(); comment("Connexion et comptes : lance `claude auth login` dans un terminal (ou /login dans une conversation).") }
        }
        group("Conversation") {
            row("Modèle par défaut :") { textField().columns(COLUMNS_LARGE).bindText(s.state::model) }
            row("Modèles supplémentaires :") {
                textField().columns(COLUMNS_LARGE).bindText(s.state::customModels)
                    .comment("Identifiants séparés par des virgules ; ajoutés au sélecteur.")
            }
            row("Instructions système :") {
                textArea().rows(4).columns(COLUMNS_LARGE).bindText(s.state::system)
                    .comment("Ajoutées au prompt système. CLAUDE.md du projet est géré par la CLI.")
            }
            row { checkBox("Ouvrir automatiquement le diff d'une modification proposée").bindSelected(s.state::autoDiff) }
            row {
                checkBox("Reprendre automatiquement quand le quota de la session est rempli").bindSelected(s.state::autoResume)
                    .comment("Attend la réinitialisation indiquée par la CLI, puis relance « reprends » dans la même session. Interrupteur aussi disponible par onglet.")
            }
            row("Délai de reprise après le début de la session suivante (minutes) :") { intTextField(0..120).bindIntText(s.state::autoResumeDelayMin) }
            row { checkBox("Notification à la fin d'une tâche ou lors d'une demande d'approbation").bindSelected(s.state::notify) }
        }
        group("Confidentialité") {
            row {
                checkBox("Autoriser la télémétrie (désactivée par défaut, révocable)").bindSelected(s.state::telemetryConsent)
                    .comment("Le plugin n'envoie aucune donnée tant que ce consentement n'est pas donné.")
            }
        }
        group("Accès à distance (smartphone, via Telegram)") {
            row {
                comment("Débloque une session depuis ton téléphone : demandes d'autorisation, plans et questions arrivent dans un chat Telegram avec des boutons. " +
                    "1) Crée un bot avec @BotFather et colle son jeton. 2) Active et applique. 3) « Appairer » puis envoie le code au bot. " +
                    "Seul le chat appairé peut répondre. Le contenu des demandes (commandes, extraits de fichiers) transite par Telegram.")
            }
            row { checkBox("Autoriser l'accès à distance (interrupteur 📱 dans chaque conversation)").bindSelected(s.state::remoteEnabled) }
            row { checkBox("Activé par défaut dans les nouvelles conversations").bindSelected(s.state::remoteDefault) }
            row("Jeton du bot :") {
                passwordField().columns(COLUMNS_LARGE).bindText({ s.telegramToken ?: "" }, { s.telegramToken = it })
            }
            row("Chat appairé :") {
                val lbl = JBLabel(s.state.telegramChatId.ifBlank { "aucun" })
                cell(lbl)
                button("Appairer mon téléphone") {
                    apply()
                    ApplicationManager.getApplication().executeOnPooledThread {
                        RemoteBridge.reconfigure()
                        val code = RemoteBridge.newPairingCode()
                        ApplicationManager.getApplication().invokeLater({
                            if (code == null) com.intellij.openapi.ui.Messages.showErrorDialog("Active l'accès à distance et renseigne le jeton du bot.", "Appairage")
                            else com.intellij.openapi.ui.Messages.showInfoMessage(
                                "Dans Telegram, ouvre ton bot et envoie :\n\n/pair $code\n\nPuis rouvre ces réglages pour voir le chat appairé.", "Appairage")
                        }, ModalityState.any())
                    }
                }
                button("Oublier le téléphone") { s.state.telegramChatId = ""; lbl.text = "aucun"; ApplicationManager.getApplication().executeOnPooledThread { RemoteBridge.reconfigure() } }
            }
        }
        group("Serveurs MCP supplémentaires (HTTP)") {
            row {
                comment("Ajoutés à ceux déjà configurés dans la CLI (`claude mcp list`). Jeton transmis en en-tête Authorization: Bearer.")
            }
            row {
                cell(mcpTable.component).align(Align.FILL)
                    .onReset { mcpTable.load(s) }
                    .onIsModified { mcpTable.isModified() }
                    .onApply { mcpTable.save(s) }
            }
        }
    }
}
