package dev.claudechat

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

enum class Kind { USER, TEXT, THINKING, TOOL, NOTICE, ERROR }

/** Un élément du transcript (FR-01, FR-03, FR-04). */
class Entry(
    val kind: Kind,
    var text: String = "",
    val toolId: String? = null,
    val toolName: String? = null,
    val input: JsonObject? = null,
    var result: String? = null,
    var isError: Boolean = false,
    val attached: String? = null,
    /** Mention affichée à côté de « Vous » (ex. précision envoyée en cours de réponse). */
    val note: String? = null,
    /** Modèle ayant produit ce contenu (réponses de Claude). */
    val model: String? = null,
)

/** Conversation en cours. L'identifiant est celui de la session de la CLI (RG-4). */
class Session(
    var id: String? = null,
    var title: String = "Nouvelle conversation",
    val entries: MutableList<Entry> = mutableListOf(),
)

data class SessionInfo(val id: String, val title: String, val updated: Long, val archived: Boolean, val file: Path)

/** Dossier de données du plugin (RG-6) : ~/.claude-code-gui ou CCG_HOME. */
object DataHome {
    /** Surcharge pour les tests. */
    var override: Path? = null
    val dir: Path
        get() = (override ?: System.getenv("CCG_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), ".claude-code-gui")).also { Files.createDirectories(it) }
}

/** Sessions de la CLI : lecture des fichiers ~/.claude/projects/<projet>/ (fichiers .jsonl), sans rien modifier au contenu (RG-4). */
object SessionStore {
    private val gson = Gson()
    private val metaFile: Path get() = DataHome.dir.resolve("session-meta.json")

    /** Titres personnalisés et sessions archivées, propres au plugin. */
    class Meta(
        val titles: MutableMap<String, String> = mutableMapOf(),
        val archived: MutableSet<String> = mutableSetOf(),
    )

    /** Lecture tolérante : un champ absent ou null (fichier ancien ou tronqué) ne doit pas faire planter. */
    private fun meta(): Meta {
        val o = runCatching { JsonParser.parseString(Files.readString(metaFile)).asJsonObject }.getOrNull() ?: return Meta()
        val titles = (o["titles"] as? JsonObject)?.entrySet()
            ?.mapNotNull { (k, v) -> v.takeIf { it.isJsonPrimitive }?.asString?.let { k to it } }.orEmpty()
        val archived = (o["archived"] as? com.google.gson.JsonArray)?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()
        return Meta(titles.toMap(mutableMapOf()), archived.toMutableSet())
    }

    /** Écriture atomique (fichier temporaire puis renommage) : un arrêt ou un second IDE ne laisse jamais un JSON tronqué. */
    private fun saveMeta(m: Meta) {
        val tmp = metaFile.resolveSibling(metaFile.fileName.toString() + ".tmp")
        Files.writeString(tmp, gson.toJson(m))
        try { Files.move(tmp, metaFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(tmp, metaFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
    }

    /** Titres déjà calculés, valables tant que la date de modification du fichier ne change pas. */
    private val titleCache = java.util.concurrent.ConcurrentHashMap<Path, Pair<Long, String>>()

    private fun cachedTitle(f: Path, mtime: Long): String {
        titleCache[f]?.takeIf { it.first == mtime }?.let { return it.second }
        if (titleCache.size > 500) titleCache.clear()
        return scanTitle(f).also { titleCache[f] = mtime to it }
    }

    /** Dossier de données de la CLI (surchargeable pour les tests). */
    var claudeDir: Path = Path.of(System.getProperty("user.home"), ".claude")

    fun projectDir(cwd: String): Path = claudeDir.resolve("projects").resolve(cwd.replace(Regex("[^A-Za-z0-9]"), "-"))

    /** Liste paginée (FR-50) : les plus récentes d'abord. */
    fun list(cwd: String, offset: Int = 0, limit: Int = 30, includeArchived: Boolean = false): List<SessionInfo> {
        val dir = projectDir(cwd)
        if (!Files.isDirectory(dir)) return emptyList()
        val m = meta()
        // une seule lecture de la date par fichier (le tri la rappelait à chaque comparaison)
        val files = Files.list(dir).use { st ->
            st.filter { it.toString().endsWith(".jsonl") }.toList()
        }.map { it to runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
            .sortedByDescending { it.second }
        return files.asSequence()
            .map { (f, mtime) -> Triple(f, mtime, f.fileName.toString().removeSuffix(".jsonl")) }
            .filter { includeArchived || it.third !in m.archived }
            .drop(offset).take(limit)
            .map { (f, mtime, id) -> SessionInfo(id, m.titles[id] ?: cachedTitle(f, mtime), mtime, id in m.archived, f) }
            .toList()
    }

    private fun scanTitle(f: Path): String {
        var ai: String? = null
        var first: String? = null
        runCatching {
            Files.newBufferedReader(f).useLines { lines ->
                for (l in lines) {
                    if (l.contains("\"ai-title\"")) {
                        ai = runCatching { JsonParser.parseString(l).asJsonObject["aiTitle"].asString }.getOrNull() ?: ai
                    } else if (first == null && l.startsWith("{") && l.contains("\"type\":\"user\"")) {
                        first = userText(JsonParser.parseString(l).asJsonObject)?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(60)
                    }
                }
            }
        }
        return ai ?: first ?: "Conversation"
    }

    private fun userText(o: JsonObject): String? {
        if (o["isMeta"]?.asBoolean == true) return null
        val c = o["message"]?.asJsonObject?.get("content") ?: return null
        val t = if (c.isJsonPrimitive) c.asString
        else c.asJsonArray.mapNotNull { b -> b.asJsonObject.takeIf { it["type"]?.asString == "text" }?.get("text")?.asString }
            .joinToString("\n").ifEmpty { return null }
        return t.takeUnless { it.startsWith("<") }
    }

    /** Reconstitue le transcript d'une session de la CLI (reprise, FR-50). */
    fun load(info: SessionInfo): Session {
        val s = Session(info.id, info.title)
        val tools = mutableMapOf<String, Entry>()
        Files.newBufferedReader(info.file).useLines { lines ->
            for (l in lines) {
                if (!l.startsWith("{")) continue
                val o = runCatching { JsonParser.parseString(l).asJsonObject }.getOrNull() ?: continue
                val mdl = o["message"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("model")?.takeIf { it.isJsonPrimitive }?.asString
                when (o["type"]?.asString) {
                    "user" -> {
                        userText(o)?.let { s.entries.add(Entry(Kind.USER, it)) }
                        val c = o["message"]?.asJsonObject?.get("content")
                        if (c != null && c.isJsonArray) c.asJsonArray.forEach { b ->
                            val bo = b.asJsonObject
                            if (bo["type"]?.asString == "tool_result") {
                                tools[bo["tool_use_id"]?.asString]?.let {
                                    it.result = resultText(bo["content"])
                                    it.isError = bo["is_error"]?.asBoolean == true
                                }
                            }
                        }
                    }
                    "assistant" -> o["message"]?.asJsonObject?.get("content")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { b ->
                        val bo = b.asJsonObject
                        when (bo["type"]?.asString) {
                            "text" -> bo["text"].asString.takeIf { it.isNotBlank() }?.let { s.entries.add(Entry(Kind.TEXT, it, model = mdl)) }
                            "thinking" -> bo["thinking"]?.asString?.takeIf { it.isNotBlank() }?.let { s.entries.add(Entry(Kind.THINKING, it, model = mdl)) }
                            "tool_use" -> {
                                val e = Entry(Kind.TOOL, toolId = bo["id"].asString, toolName = bo["name"].asString, input = bo["input"]?.asJsonObject, model = mdl)
                                tools[e.toolId!!] = e
                                s.entries.add(e)
                            }
                        }
                    }
                }
            }
        }
        return s
    }

    fun resultText(c: com.google.gson.JsonElement?): String = when {
        c == null || c.isJsonNull -> ""
        c.isJsonPrimitive -> c.asString
        c.isJsonArray -> c.asJsonArray.joinToString("\n") { it.asJsonObject["text"]?.asString ?: "" }
        else -> c.toString()
    }

    @Synchronized fun rename(id: String, title: String) { val m = meta(); m.titles[id] = title; saveMeta(m) }

    @Synchronized fun setArchived(id: String, archived: Boolean) {
        val m = meta()
        if (archived) m.archived.add(id) else m.archived.remove(id)
        saveMeta(m)
    }

    @Synchronized fun delete(info: SessionInfo) {
        Files.deleteIfExists(info.file)
        titleCache.remove(info.file)
        val m = meta(); m.titles.remove(info.id); m.archived.remove(info.id); saveMeta(m)
    }
}
