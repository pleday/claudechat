package dev.claudechat

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.google.gson.Gson
import java.util.UUID

@Service(Service.Level.APP)
@State(name = "ClaudeChatSettings", storages = [Storage("claude-chat.xml")])
class ClaudeSettings : PersistentStateComponent<ClaudeSettings.State> {
    class State {
        var cliPath: String = ""
        var model: String = "claude-sonnet-5-5"
        var effort: String = "auto"
        var permissionMode: String = "manual"
        var customModels: String = ""
        var system: String = ""
        var notify: Boolean = true
        var autoResume: Boolean = false
        var busyMode: String = "refine"
        var autoResumeDelayMin: Int = 2
        var autoDiff: Boolean = true
        var remoteEnabled: Boolean = false
        var rlAt: Long = 0
        var rlFiveUtil: Double = -1.0
        var rlFiveReset: Long = 0
        var rlWeekUtil: Double = -1.0
        var rlWeekReset: Long = 0
        var remoteDefault: Boolean = false
        var telegramChatId: String = ""
    }

    private var current = State()
    override fun getState(): State = current
    override fun loadState(state: State) { current = state }

    /** Exécutable `claude` : réglage, sinon ~/.local/bin/claude, sinon le PATH. */
    fun cliPath(): String {
        current.cliPath.takeIf { it.isNotBlank() }?.let { return it }
        return defaultCli()
    }

    /** Catalogue de modèles : valeurs par défaut + modèles personnalisés (FR-62). */
    fun models(): List<String> =
        (ModelCatalog.known.map { it.first } + current.customModels.split(',', '\n').map { it.trim() })
            .filter { it.isNotEmpty() }.distinct()

    /** Jeton du bot Telegram, dans le coffre sécurisé de l'IDE. */
    var telegramToken: String?
        get() = PasswordSafe.instance.getPassword(CredentialAttributes(generateServiceName("ClaudeChat", "telegramToken")))
        set(v) = PasswordSafe.instance.setPassword(CredentialAttributes(generateServiceName("ClaudeChat", "telegramToken")), v?.takeIf { it.isNotBlank() })


    companion object {
        /** ~/.local/bin/claude s'il existe, sinon `claude` du PATH. */
        fun defaultCli(): String {
            val local = java.io.File(System.getProperty("user.home"), ".local/bin/claude")
            return if (local.canExecute()) local.path else "claude"
        }

            fun get(): ClaudeSettings = service()
    }
}
