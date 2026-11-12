package dev.claudechat

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Portée d'un serveur MCP, comme dans la CLI (`-s user|project|local`) ; claude.ai et « autres » ne sont pas modifiables ici. */
enum class McpScope(val title: String, val source: String, val removable: Boolean) {
    USER("Utilisateur", "mcpServers dans ~/.claude.json", true),
    PROJECT("Projet", ".mcp.json (partagé avec l'équipe)", true),
    LOCAL("Local", "ce projet, privé (~/.claude.json)", true),
    OTHER("Autres", "fournis par un plugin ou la CLI", false),
    CLAUDE_AI("claude.ai", "connecteurs de ton compte claude.ai", false);

    /** Valeur de l'option `-s` de la CLI. */
    val cliName: String get() = name.lowercase()
}

enum class McpStatus { CHECKING, CONNECTED, NEEDS_AUTH, FAILED, PENDING }

data class McpServer(
    val name: String,
    val scope: McpScope,
    val target: String = "",
    val type: String? = null,
    val status: McpStatus = McpStatus.CHECKING,
    val headers: Map<String, String> = emptyMap(),
    val env: Map<String, String> = emptyMap(),
)

data class ListedServer(val name: String, val target: String, val type: String?, val status: McpStatus)
data class Normalized(val servers: List<Pair<String, String>>, val error: String?)

object McpServers {
    val namePattern = Regex("^[A-Za-z0-9_.-]+$")
    private val typeSuffix = Regex("""^(.*) \(([A-Za-z]+)\)$""")

    fun statusOf(text: String): McpStatus {
        val t = text.lowercase()
        return when {
            "needs auth" in t -> McpStatus.NEEDS_AUTH
            "pending" in t -> McpStatus.PENDING
            "fail" in t || "error" in t -> McpStatus.FAILED
            "connected" in t -> McpStatus.CONNECTED
            else -> McpStatus.FAILED
        }
    }

    /** Analyse la sortie de `claude mcp list` : « nom: cible (TYPE) - statut ». */
    fun parseList(out: String): List<ListedServer> = out.lineSequence().mapNotNull { line ->
        val i = line.lastIndexOf(" - ")
        if (i < 0) return@mapNotNull null
        val head = line.substring(0, i)
        val j = head.indexOf(": ")
        if (j <= 0) return@mapNotNull null
        var target = head.substring(j + 2).trim()
        var type: String? = null
        typeSuffix.find(target)?.let { target = it.groupValues[1]; type = it.groupValues[2].lowercase() }
        ListedServer(head.substring(0, j).trim(), target, type, statusOf(line.substring(i + 3)))
    }.toList()

    private fun strings(o: JsonElement?): Map<String, String> =
        o?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.associate { it.key to (it.value.takeIf { v -> v.isJsonPrimitive }?.asString ?: "") } ?: emptyMap()

    private fun serverFrom(name: String, scope: McpScope, o: JsonObject): McpServer {
        val url = o["url"]?.takeIf { it.isJsonPrimitive }?.asString
        val command = o["command"]?.takeIf { it.isJsonPrimitive }?.asString
        val args = o["args"]?.takeIf { it.isJsonArray }?.asJsonArray?.joinToString(" ") { it.asString } ?: ""
        val type = o["type"]?.takeIf { it.isJsonPrimitive }?.asString ?: if (url != null) "http" else "stdio"
        return McpServer(name, scope, url ?: listOfNotNull(command, args.ifBlank { null }).joinToString(" "), type, McpStatus.CHECKING, strings(o["headers"]), strings(o["env"]))
    }

    private fun serversIn(obj: JsonObject?, scope: McpScope): List<McpServer> =
        obj?.get("mcpServers")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()
            ?.filter { it.value.isJsonObject }?.map { serverFrom(it.key, scope, it.value.asJsonObject) } ?: emptyList()

    private fun readObject(p: Path): JsonObject? =
        runCatching { JsonParser.parseString(Files.readString(p)).asJsonObject }.getOrNull()

    /** Lit les trois fichiers de configuration (utilisateur, local, projet) ; statuts « en vérification ». */
    fun readConfigs(
        cwd: String,
        claudeJson: Path = Path.of(System.getProperty("user.home"), ".claude.json"),
        projectFile: Path = Path.of(cwd, ".mcp.json"),
    ): List<McpServer> {
        val root = if (Files.isRegularFile(claudeJson)) readObject(claudeJson) else null
        val local = root?.get("projects")?.takeIf { it.isJsonObject }?.asJsonObject?.get(cwd)?.takeIf { it.isJsonObject }?.asJsonObject
        val project = if (Files.isRegularFile(projectFile)) readObject(projectFile) else null
        return serversIn(root, McpScope.USER) + serversIn(project, McpScope.PROJECT) + serversIn(local, McpScope.LOCAL)
    }

    /** Applique les statuts de `mcp list` aux serveurs configurés ; les autres sont rangés sous claude.ai / Autres. */
    fun merge(configs: List<McpServer>, listed: List<ListedServer>): List<McpServer> {
        val byName = listed.associateBy { it.name }
        val known = configs.map { c ->
            byName[c.name]?.let { c.copy(status = it.status, target = c.target.ifBlank { it.target }, type = c.type ?: it.type) } ?: c.copy(status = McpStatus.FAILED)
        }
        val names = configs.map { it.name }.toSet()
        val extra = listed.filter { it.name !in names }.map {
            McpServer(it.name, if (it.name.startsWith("claude.ai ")) McpScope.CLAUDE_AI else McpScope.OTHER, it.target, it.type, it.status)
        }
        return known + extra
    }

    /** Valeur masquée d'un en-tête ou d'une variable (jamais affichée en clair). */
    fun mask(value: String): String {
        val first = value.substringBefore(' ')
        return if (' ' in value && first in setOf("Bearer", "Basic", "Token")) "$first ••••••" else "••••••"
    }

    private fun isServer(e: JsonElement) = e.isJsonObject && (e.asJsonObject.has("command") || e.asJsonObject.has("url"))

    /**
     * Accepte une configuration `{"mcpServers": {...}}`, un objet `{nom: serveur}` ou la configuration d'un seul serveur
     * (le nom est alors obligatoire). Renvoie la liste (nom, JSON du serveur) ou une erreur lisible.
     */
    fun normalize(text: String, name: String): Normalized {
        val root = try { JsonParser.parseString(text.trim()) } catch (e: Exception) {
            return Normalized(emptyList(), "JSON invalide : ${e.message?.lineSequence()?.firstOrNull()?.take(120)}")
        }
        if (!root.isJsonObject) return Normalized(emptyList(), "La configuration doit être un objet JSON.")
        val obj = root.asJsonObject
        val map: JsonObject? = when {
            obj.has("mcpServers") && obj["mcpServers"].isJsonObject -> obj["mcpServers"].asJsonObject
            isServer(obj) -> null
            obj.size() > 0 && obj.entrySet().all { isServer(it.value) } -> obj
            else -> return Normalized(emptyList(), "Aucun serveur trouvé : attendu « command » (stdio) ou « url » (http / sse).")
        }
        if (map == null) {
            val n = name.trim()
            if (n.isEmpty()) return Normalized(emptyList(), "Donne un nom au serveur (champ « Nom »).")
            if (!namePattern.matches(n)) return Normalized(emptyList(), "Nom invalide : lettres, chiffres, « - », « _ » et « . » uniquement.")
            return Normalized(listOf(n to obj.toString()), null)
        }
        if (map.size() == 0) return Normalized(emptyList(), "« mcpServers » est vide.")
        val bad = map.entrySet().firstOrNull { !isServer(it.value) }
        if (bad != null) return Normalized(emptyList(), "Le serveur « ${bad.key} » n'a ni « command » ni « url ».")
        val invalid = map.keySet().firstOrNull { !namePattern.matches(it) }
        if (invalid != null) return Normalized(emptyList(), "Nom de serveur invalide : « $invalid ».")
        return Normalized(map.entrySet().map { it.key to it.value.toString() }, null)
    }

    private fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Détails affichés sous un serveur ; les valeurs d'en-têtes et de variables sont masquées. */
    fun detailsHtml(s: McpServer): String = buildString {
        append("<html>Portée : ").append(esc(s.scope.title))
        s.type?.let { append("<br/>Type : ").append(esc(it)) }
        if (s.target.isNotBlank()) append("<br/>Cible : <code>").append(esc(s.target)).append("</code>")
        if (s.headers.isNotEmpty()) append("<br/>En-têtes : ").append(s.headers.entries.joinToString(", ") { esc(it.key) + " : " + mask(it.value) })
        if (s.env.isNotEmpty()) append("<br/>Variables : ").append(s.env.keys.joinToString(", ") { esc(it) + "=" + mask("x") })
        append("</html>")
    }

    // ---------- Appels à la CLI ----------

    /** Exécute la CLI et renvoie (code de sortie, sortie fusionnée). Bloquant. */
    fun exec(cli: String, cwd: String, vararg args: String, timeoutSec: Long = 90): Pair<Int, String> = try {
        val p = ProcessBuilder(listOf(cli) + args).directory(java.io.File(cwd)).redirectErrorStream(true).start()
        p.outputStream.close()
        val out = StringBuffer()
        val t = Thread { out.append(p.inputStream.bufferedReader().readText()) }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroy(); -1 to "délai dépassé" }
        else { t.join(2000); p.exitValue() to out.toString().trim() }
    } catch (e: Exception) { -1 to "impossible de lancer la CLI : ${e.message}" }

    fun list(cli: String, cwd: String): List<ListedServer> = parseList(exec(cli, cwd, "mcp", "list").second)

    fun add(cli: String, cwd: String, name: String, json: String, scope: McpScope): Pair<Boolean, String> =
        exec(cli, cwd, "mcp", "add-json", "-s", scope.cliName, name, json).let { (code, out) -> (code == 0) to out.ifBlank { "Ajouté." } }

    fun remove(cli: String, cwd: String, name: String, scope: McpScope): Pair<Boolean, String> =
        exec(cli, cwd, "mcp", "remove", name, "-s", scope.cliName).let { (code, out) -> (code == 0) to out.ifBlank { "Supprimé." } }
}
