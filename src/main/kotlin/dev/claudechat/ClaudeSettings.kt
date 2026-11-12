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

data class McpServer(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var url: String = "",
    var enabled: Boolean = true,
)

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
        var autoResumeDelayMin: Int = 2
        var autoDiff: Boolean = true
        var telemetryConsent: Boolean = false
        var remoteEnabled: Boolean = false
        var rlAt: Long = 0
        var rlFiveUtil: Double = -1.0
        var rlFiveReset: Long = 0
        var rlWeekUtil: Double = -1.0
        var rlWeekReset: Long = 0
        var remoteDefault: Boolean = false
        var telegramChatId: String = ""
        var mcpJson: String = "[]"
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

    // ---------- Connecteurs MCP (serveurs distants) ----------

    fun mcpServers(): MutableList<McpServer> =
        runCatching { Gson().fromJson(current.mcpJson, Array<McpServer>::class.java)?.toMutableList() }.getOrNull()
            ?: mutableListOf()

    fun saveMcpServers(list: List<McpServer>) {
        current.mcpJson = Gson().toJson(list)
    }

    /** Jetons stockés dans le coffre sécurisé, jamais dans le fichier de configuration. */
    fun mcpToken(id: String): String? = PasswordSafe.instance.getPassword(tokenAttrs(id))

    fun setMcpToken(id: String, token: String?) =
        PasswordSafe.instance.setPassword(tokenAttrs(id), token?.takeIf { it.isNotBlank() })

    fun mcpActiveCount(): Int = mcpServers().count { it.enabled && it.url.isNotBlank() }

    /** Serveurs activés, avec noms valides et uniques, prêts pour l'API. Lit le coffre : hors EDT de préférence. */
    fun activeMcp(): List<McpEntry> {
        val used = mutableSetOf<String>()
        return mcpServers().filter { it.enabled && it.url.isNotBlank() }.map { srv ->
            var base = srv.name.replace(Regex("[^A-Za-z0-9_-]"), "-").trim('-').ifBlank { "serveur" }
            var n = base
            var i = 2
            while (!used.add(n)) n = "$base-${i++}"
            McpEntry(n, srv.url.trim(), mcpToken(srv.id))
        }
    }

    private fun tokenAttrs(id: String) = CredentialAttributes(generateServiceName("ClaudeChat", "mcp:$id"))

    companion object {
        /** ~/.local/bin/claude s'il existe, sinon `claude` du PATH. */
        fun defaultCli(): String {
            val local = java.io.File(System.getProperty("user.home"), ".local/bin/claude")
            return if (local.canExecute()) local.path else "claude"
        }

            fun get(): ClaudeSettings = service()
    }
}
