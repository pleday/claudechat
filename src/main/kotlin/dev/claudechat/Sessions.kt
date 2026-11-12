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

    private fun meta(): Meta =
        runCatching { gson.fromJson(Files.readString(metaFile), Meta::class.java) }.getOrNull() ?: Meta()

    private fun saveMeta(m: Meta) = Files.writeString(metaFile, gson.toJson(m))

    /** Dossier de données de la CLI (surchargeable pour les tests). */
    var claudeDir: Path = Path.of(System.getProperty("user.home"), ".claude")

    fun projectDir(cwd: String): Path = claudeDir.resolve("projects").resolve(cwd.replace(Regex("[^A-Za-z0-9]"), "-"))

    /** Liste paginée (FR-50) : les plus récentes d'abord. */
    fun list(cwd: String, offset: Int = 0, limit: Int = 30, includeArchived: Boolean = false): List<SessionInfo> {
        val dir = projectDir(cwd)
        if (!Files.isDirectory(dir)) return emptyList()
        val m = meta()
        val files = Files.list(dir).use { st ->
            st.filter { it.toString().endsWith(".jsonl") }.toList()
        }.sortedByDescending { Files.getLastModifiedTime(it).toMillis() }
        return files.asSequence()
            .map { f ->
                val id = f.fileName.toString().removeSuffix(".jsonl")
                Triple(f, id, id in m.archived)
            }
            .filter { includeArchived || !it.third }
            .drop(offset).take(limit)
            .map { (f, id, arch) ->
                SessionInfo(id, m.titles[id] ?: scanTitle(f), Files.getLastModifiedTime(f).toMillis(), arch, f)
            }.toList()
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
                            "text" -> bo["text"].asString.takeIf { it.isNotBlank() }?.let { s.entries.add(Entry(Kind.TEXT, it)) }
                            "thinking" -> bo["thinking"]?.asString?.takeIf { it.isNotBlank() }?.let { s.entries.add(Entry(Kind.THINKING, it)) }
                            "tool_use" -> {
                                val e = Entry(Kind.TOOL, toolId = bo["id"].asString, toolName = bo["name"].asString, input = bo["input"]?.asJsonObject)
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

    fun rename(id: String, title: String) { val m = meta(); m.titles[id] = title; saveMeta(m) }

    fun setArchived(id: String, archived: Boolean) {
        val m = meta()
        if (archived) m.archived.add(id) else m.archived.remove(id)
        saveMeta(m)
    }

    fun delete(info: SessionInfo) {
        Files.deleteIfExists(info.file)
        val m = meta(); m.titles.remove(info.id); m.archived.remove(info.id); saveMeta(m)
    }
}
