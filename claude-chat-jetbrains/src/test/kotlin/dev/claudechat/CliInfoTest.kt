package dev.claudechat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val MODELS = """[
 {"value":"default","resolvedModel":"claude-sonnet-5","displayName":"Default (recommended)","description":"Sonnet 5","supportsEffort":true,"supportedEffortLevels":["low","medium","high","xhigh","max"]},
 {"value":"opus","resolvedModel":"claude-opus-5-5","displayName":"Opus 5.5","description":"For complex work","supportsEffort":true,"supportedEffortLevels":["low","medium","high","xhigh","max"]},
 {"value":"sonnet","resolvedModel":"claude-sonnet-5-5","displayName":"Sonnet 5.5","description":"Most efficient","supportsEffort":true,"supportedEffortLevels":["low","medium","high","xhigh","max"]},
 {"value":"claude-haiku-4-5-20251001","resolvedModel":"claude-haiku-4-5-20251001","displayName":"Haiku 4.5","description":"Fastest"},
 {"value":"claude-opus-4-6","resolvedModel":"claude-opus-4-6","displayName":"Opus 4.6","description":"Older","supportsEffort":true,"supportedEffortLevels":["low","medium","high","max"]},
 {"value":"claude-opus-5-5","resolvedModel":"claude-opus-5-5","displayName":"Opus 5.5 bis","description":"doublon","supportsEffort":true,"supportedEffortLevels":["low"]}
]"""

private const val CONTEXT = """{"categories":[
 {"id":"system_prompt","name":"System prompt","tokens":2832,"kind":"used"},
 {"id":"messages","name":"Messages","tokens":120730,"kind":"used"},
 {"id":"skills","name":"Skills","tokens":5351,"kind":"used"},
 {"id":"system_tools_deferred","name":"System tools (deferred)","tokens":16226,"kind":"deferred"},
 {"id":"autocompact_buffer","name":"Autocompact buffer","tokens":33000,"kind":"buffer"},
 {"id":"free_space","name":"Free space","tokens":832381,"kind":"free"}],
 "totalTokens":134619,"maxTokens":1000000,"model":"claude-sonnet-5-5","autoCompactThreshold":967000,"isAutoCompactEnabled":true,
 "messageBreakdown":{"toolCallTokens":13253,"toolResultTokens":21261,"attachmentTokens":56750,"assistantMessageTokens":22209,"userMessageTokens":20162}}"""

private fun wrap(payload: String, id: String = "x") =
    JsonParser.parseString("""{"type":"control_response","response":{"subtype":"success","request_id":"$id","response":$payload}}""").asJsonObject

class CliInfoTest {
    @BeforeEach @AfterEach fun reset() = ModelCatalog.reset()

    @Test fun parsesModelsSkippingDefaultAndDuplicates() {
        val m = parseModels(wrap("""{"models":$MODELS}"""))
        assertEquals(listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-4-5-20251001", "claude-opus-4-6"), m.map { it.id })
        assertEquals("Opus 5.5", m[0].name)       // l'alias court gagne sur le doublon
        assertTrue(m[0].isAlias && !m[2].isAlias)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), m[0].efforts)
        assertTrue(m[2].efforts.isEmpty(), "Haiku 4.5 : pas d'effort")
        assertTrue(parseModels(JsonObject()).isEmpty())
    }

    @Test fun catalogUsesCliModelsAndFallsBackWithoutThem() {
        assertEquals("Sonnet 5.5", ModelCatalog.label("claude-sonnet-5-5"))     // liste de secours
        assertEquals("claude-sonnet-5-5", ModelCatalog.resolve("Sonnet"))
        ModelCatalog.update(parseModels(wrap("""{"models":$MODELS}""")))
        assertEquals("Opus 4.6", ModelCatalog.label("claude-opus-4-6"))
        assertEquals("claude-opus-5-5", ModelCatalog.resolve("OPUS"))
        assertEquals("Fastest", ModelCatalog.description("claude-haiku-4-5-20251001"))
        ModelCatalog.update(emptyList())
        assertEquals("Opus 4.6", ModelCatalog.label("claude-opus-4-6"), "une liste vide ne remplace pas la précédente")
    }

    @Test fun effortChoicesFollowTheModel() {
        val fb = listOf("auto", "low", "max")
        assertEquals(fb, ModelCatalog.effortChoices("claude-opus-4-6", fb), "CLI muette : liste générale")
        ModelCatalog.update(parseModels(wrap("""{"models":$MODELS}""")))
        assertEquals(listOf("auto", "low", "medium", "high", "max"), ModelCatalog.effortChoices("claude-opus-4-6", fb))
        assertEquals(listOf("auto"), ModelCatalog.effortChoices("claude-haiku-4-5-20251001", fb))
        assertEquals(fb, ModelCatalog.effortChoices("modele-perso", fb), "modèle inconnu de la CLI : pas de restriction")
    }

    @Test fun parsesContextUsage() {
        val u = parseContextUsage(controlPayload(wrap(CONTEXT))!!)!!
        assertEquals(134_619, u.total); assertEquals(1_000_000, u.max); assertEquals(13, u.percent)
        assertEquals("Messages", u.categories.first { it.id == "messages" }.name)
        assertEquals("Prompt système", u.categories.first { it.id == "system_prompt" }.name, "libellés en français")
        assertEquals(967_000, u.autoCompactAt)
        assertEquals(56_750, u.attachments)
        assertNull(parseContextUsage(JsonObject()), "sans taille de fenêtre : inexploitable")
    }

    @Test fun contextReportListsLargestFirstAndFootnotes() {
        val u = parseContextUsage(controlPayload(wrap(CONTEXT))!!)!!
        val html = contextReportHtml(u)
        assertTrue("Contexte : 134,6k / 1,0M tokens (13 %)" in html, html)
        assertTrue(html.indexOf("Messages") < html.indexOf("Skills") && html.indexOf("Skills") < html.indexOf("Prompt système"), "tri décroissant")
        assertTrue("outils 34,5k" in html && "pièces jointes 56,8k" in html)
        assertTrue("compaction automatique à 967,0k" in html && "libre 832,4k" in html && "outils différés 16,2k (hors total)" in html)
        assertFalse("serveurs MCP" in html)
        assertTrue("serveurs MCP" in contextReportHtml(u.copy(approximate = true)))
    }

    @Test fun formatsTokens() {
        assertEquals("842", fmtTokens(842)); assertEquals("1,5k", fmtTokens(1500)); assertEquals("1,0M", fmtTokens(1_000_000))
    }
}

class CliInfoProcessTest {
    @TempDir lateinit var tmp: Path

    /** Faux `claude` ; « § » dans le script vaut « $ » (évite d'échapper le shell dans les chaînes Kotlin). */
    private fun fake(body: String): String {
        org.junit.jupiter.api.Assumptions.assumeTrue(!System.getProperty("os.name").lowercase().contains("win"))
        val f = tmp.resolve("claude"); Files.writeString(f, "#!/bin/bash\n${body.replace('§', '$')}\n"); f.toFile().setExecutable(true); return f.toString()
    }

    private val ctxLine = """{"type":"control_response","response":{"subtype":"success","request_id":"ID","response":${CONTEXT.replace("\n", "")}}}"""

    @Test fun fetchInitReturnsCommandsAndModels() {
        val models = MODELS.replace("\n", "")
        val cli = fake("""read -r line
echo '{"type":"control_response","response":{"subtype":"success","request_id":"init-cmds","response":{"commands":[{"name":"compact","description":"Résume"}],"models":$models}}}'
sleep 5""")
        val info = CliTurn.fetchInit(cli, tmp.toString(), timeoutSec = 10)
        assertEquals(listOf("compact"), info.commands.map { it.name })
        assertEquals(4, info.models.size)
    }

    @Test fun fetchInitIsEmptyWhenTheCliFails() {
        val info = CliTurn.fetchInit("/nonexistent/claude", tmp.toString())
        assertTrue(info.commands.isEmpty() && info.models.isEmpty())
    }

    @Test fun contextUsageOutsideTheProcessResumesWithoutPersistence() {
        val args = tmp.resolve("args.txt")
        val cli = fake("""echo "§@" > $args
read -r line
echo '${ctxLine.replace("ID", "ctx-once")}'
sleep 5""")
        val u = CliTurn.contextUsage(cli, tmp.toString(), "sid-123", timeoutSec = 10)!!
        assertEquals(134_619, u.total)
        assertTrue(u.approximate, "serveurs MCP non démarrés : estimation")
        val a = Files.readString(args)
        assertTrue("--resume sid-123" in a && "--no-session-persistence" in a && "--strict-mcp-config" in a, a)
    }

    @Test fun contextUsageOutsideTheProcessIsNullOnErrorResponse() {
        val cli = fake("""read -r line
echo '{"type":"control_response","response":{"subtype":"error","request_id":"ctx-once","error":"non"}}'
sleep 5""")
        assertNull(CliTurn.contextUsage(cli, tmp.toString(), null, timeoutSec = 10))
    }

    @Test fun liveTurnAnswersContextRequestOnItsOwnProcess() {
        val cli = fake("""read -r first
echo '{"type":"system","subtype":"init","session_id":"s1"}'
while read -r line; do
  case "§line" in
    *get_context_usage*) id=§(echo "§line" | sed -E 's/.*"request_id":"([^"]+)".*/\1/'); echo '${ctxLine}' | sed "s/\"ID\"/\"§id\"/;s/request_id\":\"ID/request_id\":\"§id/" ;;
  esac
done""")
        val inited = CountDownLatch(1)
        val l = object : TurnListener { override fun onInit(sessionId: String, model: String?) { inited.countDown() } }
        val content = JsonArray().apply { add(JsonObject().apply { addProperty("type", "text"); addProperty("text", "x") }) }
        val t = CliTurn(TurnOptions(cli, tmp.toString(), "", "auto", "manual", null, "", emptyList()), content, l)
        t.start()
        try {
            assertTrue(inited.await(10, TimeUnit.SECONDS))
            val u = t.contextUsage(timeoutMs = 10_000)
            assertNotNull(u, "réponse routée par request_id")
            assertEquals(134_619, u!!.total)
            assertFalse(u.approximate, "mesure sur le processus de la conversation : exacte")
        } finally { t.kill() }
    }

    @Test fun contextUsageIsNullOnceTheTurnIsKilled() {
        val cli = fake("""read -r first
echo '{"type":"system","subtype":"init","session_id":"s1"}'
sleep 30""")
        val inited = CountDownLatch(1)
        val l = object : TurnListener { override fun onInit(sessionId: String, model: String?) { inited.countDown() } }
        val content = JsonArray().apply { add(JsonObject().apply { addProperty("type", "text"); addProperty("text", "x") }) }
        val t = CliTurn(TurnOptions(cli, tmp.toString(), "", "auto", "manual", null, "", emptyList()), content, l)
        t.start()
        assertTrue(inited.await(10, TimeUnit.SECONDS))
        t.kill()
        assertNull(t.contextUsage(timeoutMs = 2_000))
    }
}
