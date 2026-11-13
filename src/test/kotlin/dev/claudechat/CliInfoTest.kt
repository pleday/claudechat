package dev.claudechat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

private const val MODELS = """[
 {"value":"default","resolvedModel":"claude-sonnet-5","displayName":"Default (recommended)","description":"Sonnet 5","supportsEffort":true,"supportedEffortLevels":["low","medium","high","xhigh","max"]},
 {"value":"opus","resolvedModel":"claude-opus-5-5","displayName":"Opus 5.5","description":"For complex work","supportsEffort":true,"supportedEffortLevels":["low","medium","high","xhigh","max"]},
 {"value":"sonnet","resolvedModel":"claude-sonnet-5-5","displayName":"Sonnet 5.5","description":"Most efficient","supportsEffort":true,"supportedEffortLevels":["low","medium","high","xhigh","max"]},
 {"value":"claude-haiku-4-5-20251001","resolvedModel":"claude-haiku-4-5-20251001","displayName":"Haiku 4.5","description":"Fastest"},
 {"value":"claude-opus-4-6","resolvedModel":"claude-opus-4-6","displayName":"Opus 4.6","description":"Older","supportsEffort":true,"supportedEffortLevels":["low","medium","high","max"]},
 {"value":"claude-opus-5-5","resolvedModel":"claude-opus-5-5","displayName":"Opus 5.5 bis","description":"doublon","supportsEffort":true,"supportedEffortLevels":["low"]}
]"""

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

}

class CliInfoProcessTest {
    @TempDir lateinit var tmp: Path

    /** Faux `claude` ; « § » dans le script vaut « $ » (évite d'échapper le shell dans les chaînes Kotlin). */
    private fun fake(body: String): String {
        org.junit.jupiter.api.Assumptions.assumeTrue(!System.getProperty("os.name").lowercase().contains("win"))
        val f = tmp.resolve("claude"); Files.writeString(f, "#!/bin/bash\n${body.replace('§', '$')}\n"); f.toFile().setExecutable(true); return f.toString()
    }

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

}
