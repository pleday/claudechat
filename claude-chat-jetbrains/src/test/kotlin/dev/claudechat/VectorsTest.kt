package dev.claudechat

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Vecteurs de test communs avec l'extension VS Code (`shared/test-vectors` (fichiers JSON), entrée -> sortie attendue) : les deux
 * implémentations doivent se comporter à l'identique. Les sorties sont générées côté TypeScript (`UPDATE_VECTORS=1`).
 */
class VectorsTest {
    @TempDir lateinit var tmp: Path

    private fun load(name: String): JsonObject =
        JsonParser.parseReader(VectorsTest::class.java.getResourceAsStream("/vectors/$name.json")!!.bufferedReader(Charsets.UTF_8)).asJsonObject

    /** Forme canonique : null et propriétés absentes sont équivalents. */
    private fun canon(e: JsonElement?): JsonElement = when {
        e == null || e.isJsonNull -> JsonNull.INSTANCE
        e.isJsonArray -> JsonArray().also { a -> e.asJsonArray.forEach { a.add(canon(it)) } }
        e.isJsonObject -> JsonObject().also { o -> e.asJsonObject.entrySet().forEach { (k, v) -> if (!v.isJsonNull) o.add(k, canon(v)) } }
        else -> e
    }

    private val gson = Gson()
    private fun j(x: Any?): JsonElement = if (x == null) JsonNull.INSTANCE else gson.toJsonTree(x)
    private fun JsonElement?.str() = this?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.opt(k: String): JsonElement? = get(k)?.takeIf { !it.isJsonNull }
    private fun JsonArray.strings() = map { it.asString }
    private fun win(o: JsonElement?): QuotaWindow? = o?.takeIf { it.isJsonObject }?.asJsonObject?.let { QuotaWindow(it["utilization"].asDouble, it["resetsAt"].asLong) }
    private fun limits(o: JsonElement?): RateLimits? = o?.takeIf { it.isJsonObject }?.asJsonObject?.let {
        RateLimits(win(it.opt("fiveHour")), win(it.opt("sevenDay")), it.opt("status").str(), it.opt("limitType").str(), it.opt("resetsAt")?.asLong)
    }

    private fun window(i: JsonObject) = win(i.opt("window"))

    private val handlers: Map<String, Map<String, (JsonElement) -> Any?>> = mapOf(
        "sticky" to mapOf(
            "isResume" to { i -> StickyPrompt.isResume(i.asString) },
            "pick" to { i -> StickyPrompt.pick(i.asJsonArray.strings())?.let { mapOf("prompt" to it.prompt, "resumedWith" to it.resumedWith) } },
        ),
        "text-tools" to mapOf(
            "fuzzySearch" to { i -> i.asJsonObject.let { Fuzzy.search(it["query"].asString, it["paths"].asJsonArray.strings()) } },
            "mentionsFind" to { i -> Mentions.find(i.asString) },
            "mentionsActive" to { i -> i.asJsonObject.let { o -> Mentions.activeToken(o["text"].asString, o["caret"].asInt)?.let { mapOf("start" to it.first, "query" to it.second) } } },
            "slashActive" to { i -> i.asJsonObject.let { SlashToken.activeQuery(it["text"].asString, it["caret"].asInt) } },
            "slashFilter" to { i -> i.asJsonObject.let { SlashToken.filter(it["query"].asString, it["commands"].asJsonArray.strings()) } },
            "toolSummary" to { i -> i.asJsonObject.let { toolSummary(it["name"].asString, it["input"].asJsonObject) } },
            "describeTool" to { i -> i.asJsonObject.let { describeTool(it["name"].asString, it["input"].asJsonObject) } },
            "formatDuration" to { i -> formatDuration(i.asLong) },
            "quotaRemaining" to { i -> i.asJsonObject.let { Quota.remaining(it["resetsAt"].asLong, it["now"].asLong) } },
            "quotaPercent" to { i -> i.asJsonObject.let { Quota.percent(window(it), it["now"].asLong) } },
            "quotaGuard" to { i -> i.asJsonObject.let { QuotaGuard.blockedUntil(limits(it.opt("limits")), it.opt("message").str(), it["now"].asLong, it["delay"].asLong) } },
            "queuePreview" to { i -> i.asJsonObject.let { queuePreview(it["queue"].asJsonArray.strings(), it["max"].asInt) } },
            "busyWarning" to { i -> i.asJsonObject.let { busyWarning(it["streaming"].asBoolean, it["queued"].asInt, it["consequence"].asString) } },
            "chunkText" to { i -> i.asJsonObject.let { chunkText(it["text"].asString, it["max"].asInt) } },
            "fmtTokens" to { i -> fmtTokens(i.asInt) },
            "modelLabel" to { i -> ModelCatalog.reset(); ModelCatalog.label(i.asString) },
            "modelResolve" to { i -> ModelCatalog.reset(); ModelCatalog.resolve(i.asString) },
            "lineCount" to { i -> LineDiff.lineCount(i.asString) },
        ),
        "cli-parsing" to mapOf(
            "rateLimits" to { i ->
                parseRateLimits(i.asJsonObject)?.let {
                    mapOf("fiveHour" to it.fiveHour, "sevenDay" to it.sevenDay, "status" to it.status, "limitType" to it.limitType, "resetsAt" to it.resetsAt)
                }
            },
            "models" to { i -> parseModels(i.asJsonObject).map { mapOf("id" to it.id, "alias" to it.alias, "name" to it.name, "description" to it.description, "efforts" to it.efforts) } },
            "commands" to { i -> CliTurn.parseCommands(i.asJsonObject).map { mapOf("name" to it.name, "description" to it.description) } },
            "contextUsage" to { i ->
                parseContextUsage(i.asJsonObject)?.let { u ->
                    mapOf(
                        "total" to u.total, "max" to u.max, "model" to u.model, "percent" to u.percent, "autoCompactAt" to u.autoCompactAt, "approximate" to u.approximate,
                        "categories" to u.categories.map { mapOf("id" to it.id, "name" to it.name, "tokens" to it.tokens, "kind" to it.kind) },
                        "toolCalls" to u.toolCalls, "toolResults" to u.toolResults, "attachments" to u.attachments, "assistant" to u.assistant, "user" to u.user,
                    )
                }
            },
            "cleanSuggestion" to { i -> CliTurn.cleanSuggestion(i.str()) },
        ),
        "mcp" to mapOf(
            "parseList" to { i -> McpServers.parseList(i.asString).map { mapOf("name" to it.name, "target" to it.target, "type" to it.type, "status" to it.status.name.lowercase()) } },
            "statusOf" to { i -> McpServers.statusOf(i.asString).name.lowercase() },
            "mask" to { i -> McpServers.mask(i.asString) },
            "normalize" to { i ->
                McpServers.normalize(i.asJsonObject["text"].asString, i.asJsonObject["name"].asString).let { n ->
                    mapOf("servers" to n.servers.map { listOf(it.first, JsonParser.parseString(it.second)) }, "error" to n.error)
                }
            },
        ),
    )

    @TestFactory fun sharedVectors(): List<DynamicTest> {
        val tests = mutableListOf<DynamicTest>()
        for ((file, sections) in handlers) {
            val data = load(file)
            for ((section, h) in sections) for ((n, c) in data[section].asJsonArray.withIndex()) {
                val o = c.asJsonObject
                tests += DynamicTest.dynamicTest("$file/$section#$n ${o["in"].toString().take(60)}") {
                    assertEquals(canon(o["out"]), canon(j(h(o["in"]))), "$file/$section(${o["in"]})")
                }
            }
        }
        return tests
    }

    @TestFactory fun inputHistory(): List<DynamicTest> = load("input-history")["scenarios"].asJsonArray.mapIndexed { n, s ->
        DynamicTest.dynamicTest("input-history#$n") {
            val h = InputHistory(s.asJsonObject["max"].asInt)
            for (op in s.asJsonObject["ops"].asJsonArray.map { it.asJsonObject }) {
                when (op["op"].asString) {
                    "add" -> h.add(op["text"].asString)
                    "previous" -> assertEquals(canon(op["out"]), canon(j(h.previous(op["current"].asString))), op.toString())
                    "next" -> assertEquals(canon(op["out"]), canon(j(h.next(op["current"].asString))), op.toString())
                }
            }
        }
    }

    @TestFactory fun slashCatalog(): List<DynamicTest> = load("slash-catalog")["cases"].asJsonArray.mapIndexed { n, c ->
        DynamicTest.dynamicTest("slash-catalog#$n") {
            val o = c.asJsonObject
            val cli = o["cli"].asJsonArray.map { CliCommand(it.asJsonObject["name"].asString, it.asJsonObject["description"].asString) }
            val all = SlashCatalog.build(cli)
            assertEquals(canon(o["build"]), canon(j(all.map { mapOf("name" to it.name, "category" to it.category) })))
            val groups = o["queries"].asJsonArray.map { q -> SlashCatalog.group(q.asString, all).map { (cat, l) -> listOf(cat, l.map { it.name }) } }
            assertEquals(canon(o["group"]), canon(j(groups)))
        }
    }

    @TestFactory fun sessionTranscript(): List<DynamicTest> = load("session-transcript")["cases"].asJsonArray.mapIndexed { n, c ->
        DynamicTest.dynamicTest("session-transcript#$n") {
            val home = Files.createTempDirectory(tmp, "v")
            SessionStore.claudeDir = home.resolve(".claude")
            DataHome.override = home.resolve("data")
            val cwd = "/work/vecteur"
            Files.createDirectories(SessionStore.projectDir(cwd))
            Files.writeString(SessionStore.projectDir(cwd).resolve("s.jsonl"), c.asJsonObject["lines"].asJsonArray.joinToString("\n", postfix = "\n") { it.toString() })
            val info = SessionStore.list(cwd).single()
            val s = SessionStore.load(info)
            val entries = s.entries.map {
                mapOf("kind" to it.kind.name.lowercase(), "text" to it.text, "toolName" to it.toolName, "result" to it.result, "isError" to if (it.kind == Kind.TOOL) it.isError else null, "model" to it.model)
            }
            assertEquals(c.asJsonObject["title"].asString, info.title)
            assertEquals(canon(c.asJsonObject["entries"]), canon(j(entries)))
        }
    }
}
