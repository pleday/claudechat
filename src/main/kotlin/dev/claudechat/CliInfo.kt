package dev.claudechat

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.Locale

/** Modèle proposé par la CLI (réponse `initialize`). [efforts] vide : le modèle n'accepte aucun niveau d'effort. */
data class CliModel(val id: String, val alias: String, val name: String, val description: String, val efforts: List<String>) {
    /** Alias court (« sonnet ») par opposition à une version précise (« claude-opus-4-6 »). */
    val isAlias: Boolean get() = !alias.startsWith("claude-")
}

/** Ce que la CLI annonce au démarrage : commandes slash et modèles. */
data class CliInit(val commands: List<CliCommand>, val models: List<CliModel>)

private fun JsonElement?.str(): String? = this?.takeIf { it.isJsonPrimitive }?.asString

/** Charge utile d'une réponse de contrôle (`response.response`), ou null si le format est inattendu. */
fun controlPayload(j: JsonObject): JsonObject? =
    j["response"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("response")?.takeIf { it.isJsonObject }?.asJsonObject

/**
 * Modèles d'une réponse `initialize`. L'entrée « default » est ignorée (elle double un autre modèle) et les doublons
 * sont retirés sur l'identifiant résolu, l'alias court passant avant la version précise.
 */
fun parseModels(j: JsonObject): List<CliModel> {
    val arr = controlPayload(j)?.get("models")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
    return arr.mapNotNull { e ->
        val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val alias = o["value"].str()?.takeIf { it.isNotBlank() && it != "default" } ?: return@mapNotNull null
        val id = o["resolvedModel"].str()?.takeIf { it.isNotBlank() } ?: alias
        val supports = o["supportsEffort"]?.takeIf { it.isJsonPrimitive }?.asBoolean == true
        val efforts = if (!supports) emptyList() else
            (o["supportedEffortLevels"] as? JsonArray)?.mapNotNull { it.str() }.orEmpty()
        CliModel(id, alias, o["displayName"].str() ?: id, o["description"].str().orEmpty(), efforts)
    }.distinctBy { it.id }
}

/** Une ligne du détail du contexte : [kind] vaut used, deferred, buffer ou free. */
data class ContextCategory(val id: String, val name: String, val tokens: Int, val kind: String)

/** Contexte d'une conversation tel que mesuré par la CLI (`get_context_usage`). */
data class ContextUsage(
    val total: Int, val max: Int, val model: String?,
    val categories: List<ContextCategory>,
    val autoCompactAt: Int?,
    /** Répartition des messages : outils (appels, résultats), pièces jointes, réponses de Claude, messages de l'utilisateur. */
    val toolCalls: Int, val toolResults: Int, val attachments: Int, val assistant: Int, val user: Int,
    /** Mesuré sur un processus à part, sans les serveurs MCP : leurs outils ne sont pas comptés. */
    val approximate: Boolean = false,
) {
    val percent: Int get() = if (max > 0) Math.round(total * 100.0 / max).toInt() else 0
}

private val CONTEXT_LABELS = mapOf(
    "system_prompt" to "Prompt système", "system_tools" to "Outils système", "system_tools_deferred" to "Outils système (différés)",
    "skills" to "Skills", "messages" to "Messages", "memory_files" to "Fichiers mémoire (CLAUDE.md)", "mcp_tools" to "Outils MCP",
    "custom_agents" to "Agents", "agents" to "Agents", "autocompact_buffer" to "Réserve de compaction", "free_space" to "Espace libre",
)

/** [payload] : la charge utile de la réponse `get_context_usage` (voir [controlPayload]). */
fun parseContextUsage(payload: JsonObject): ContextUsage? {
    fun int(o: JsonObject?, k: String) = o?.get(k)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
    val max = int(payload, "maxTokens")
    if (max <= 0) return null
    val cats = (payload["categories"] as? JsonArray)?.mapNotNull { e ->
        val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val id = o["id"].str() ?: return@mapNotNull null
        ContextCategory(id, CONTEXT_LABELS[id] ?: o["name"].str() ?: id, int(o, "tokens"), o["kind"].str() ?: "used")
    }.orEmpty()
    val mb = payload["messageBreakdown"]?.takeIf { it.isJsonObject }?.asJsonObject
    return ContextUsage(
        int(payload, "totalTokens"), max, payload["model"].str(), cats,
        payload["autoCompactThreshold"]?.takeIf { it.isJsonPrimitive }?.asInt?.takeIf { payload["isAutoCompactEnabled"]?.asBoolean != false },
        int(mb, "toolCallTokens"), int(mb, "toolResultTokens"), int(mb, "attachmentTokens"), int(mb, "assistantMessageTokens"), int(mb, "userMessageTokens"),
    )
}

/** « 134,6k », « 1,0M », « 842 » (format français, indépendant de la langue de la machine). */
fun fmtTokens(n: Int): String = when {
    n >= 1_000_000 -> String.format(Locale.FRANCE, "%.1fM", n / 1e6)
    n >= 1000 -> String.format(Locale.FRANCE, "%.1fk", n / 1000.0)
    else -> "$n"
}

/** Détail du contexte en HTML (fenêtre au clic sur l'anneau). */
fun contextReportHtml(u: ContextUsage): String {
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val used = u.categories.filter { it.kind == "used" && it.tokens > 0 }.sortedByDescending { it.tokens }
    val usedTotal = used.sumOf { it.tokens }.coerceAtLeast(1)
    val cells = 14
    val rows = used.joinToString("") { c ->
        val filled = Math.round(c.tokens * cells.toDouble() / usedTotal).toInt().coerceIn(if (c.tokens > 0) 1 else 0, cells)
        "<tr><td>${esc(c.name)}</td><td align='right'>&nbsp;${fmtTokens(c.tokens)}&nbsp;</td>" +
            "<td><font color='gray'>${"▰".repeat(filled)}${"▱".repeat(cells - filled)}</font></td></tr>"
    }
    val toolTokens = u.toolCalls + u.toolResults
    val breakdown = listOf(
        "outils" to toolTokens, "pièces jointes" to u.attachments, "réponses de Claude" to u.assistant, "vos messages" to u.user,
    ).filter { it.second > 0 }.joinToString(" · ") { "${it.first} ${fmtTokens(it.second)}" }
    fun kindTokens(k: String) = u.categories.filter { it.kind == k }.sumOf { it.tokens }
    val foot = listOfNotNull(
        u.autoCompactAt?.let { "compaction automatique à ${fmtTokens(it)}" },
        kindTokens("buffer").takeIf { it > 0 }?.let { "réserve ${fmtTokens(it)}" },
        kindTokens("free").takeIf { it > 0 }?.let { "libre ${fmtTokens(it)}" },
        kindTokens("deferred").takeIf { it > 0 }?.let { "outils différés ${fmtTokens(it)} (hors total)" },
    ).joinToString(" · ")
    return "<html><body style='width:400px'>" +
        "<b>Contexte : ${fmtTokens(u.total)} / ${fmtTokens(u.max)} tokens (${u.percent} %)</b>" +
        (u.model?.let { " · ${esc(ModelCatalog.label(it))}" } ?: "") +
        "<table cellspacing='1'>$rows</table>" +
        (if (breakdown.isNotEmpty()) "<br/>Messages : $breakdown" else "") +
        (if (foot.isNotEmpty()) "<br/><font color='gray'>$foot</font>" else "") +
        (if (u.approximate) "<br/><font color='gray'>Mesure sur un processus à part : les outils des serveurs MCP ne sont pas comptés.</font>" else "") +
        "</body></html>"
}
