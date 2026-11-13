package dev.claudechat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Test d'intégration avec la vraie CLI (consomme un peu de quota) : CLAUDE_REAL=1 ./gradlew test --tests '*RealCliIT*' */
@EnabledIfEnvironmentVariable(named = "CLAUDE_REAL", matches = "1")
class RealCliIT {
    @Test fun realTurnReportsQuotasAndUsage() {
        val events = CopyOnWriteArrayList<String>()
        var limits: RateLimits? = null
        val done = CountDownLatch(1)
        val l = object : TurnListener {
            override fun onInit(sessionId: String, model: String?) { events += "init:$model" }
            override fun onText(delta: String) { events += "text" }
            override fun onLiveUsage(contextTokens: Int, outputTokens: Int) { events += "live:$contextTokens/$outputTokens" }
            override fun onRateLimits(limits0: RateLimits) { limits = limits0; events += "limits:$limits0" }
            override fun onDone(isError: Boolean, message: String?) { events += "done:$isError:$message"; done.countDown() }
        }
        val content = JsonArray().apply { add(JsonObject().apply { addProperty("type", "text"); addProperty("text", "réponds seulement : ok") }) }
        val cli = ClaudeSettings.defaultCli()
        CliTurn(TurnOptions(cli, System.getProperty("java.io.tmpdir"), "claude-haiku-5-5", "auto", "manual", null, "", emptyList()), content, l).start()
        assertTrue(done.await(90, TimeUnit.SECONDS), events.toString())
        println("EVENTS: $events")
        assertNotNull(limits, "aucun rate_limit_event reçu : $events")
        assertNotNull(limits!!.fiveHour)
    }

    @Test fun realProbeReturnsQuotas() {
        val r = CliTurn.probeQuota(ClaudeSettings.defaultCli(), System.getProperty("java.io.tmpdir"))
        println("PROBE: $r")
        assertNull(r.error)
        assertNotNull(r.limits!!.fiveHour)
    }

    @Test fun realRefineIsInjectedWithoutInterruption() {
        val dir = java.nio.file.Files.createTempDirectory("ccg-refine")
        for (c in "abcdef") java.nio.file.Files.writeString(dir.resolve("$c.txt"), (1..400).joinToString("\n") { "$c ligne $it" })
        val text = StringBuilder()
        val lost = CopyOnWriteArrayList<String>()
        val results = java.util.concurrent.atomic.AtomicInteger()
        val done = CountDownLatch(1)
        lateinit var turn: CliTurn
        val l = object : TurnListener {
            override fun onText(delta: String) { text.append(delta) }
            override fun onRefineLost(texts: List<String>) { lost += texts }
            override fun onPermission(req: PermissionRequest) { turn.allow(req.requestId, req.input) }
            override fun onDone(isError: Boolean, message: String?) { results.incrementAndGet(); done.countDown() }
        }
        val content = JsonArray().apply { add(JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", "Lis les fichiers a.txt, b.txt, c.txt, d.txt, e.txt, f.txt UN PAR UN (un appel Read par fichier), puis conclus en une phrase en français.")
        }) }
        turn = CliTurn(TurnOptions(ClaudeSettings.defaultCli(), dir.toString(), "claude-haiku-5-5", "auto", "manual", null, "", emptyList()), content, l)
        turn.start()
        Thread.sleep(4000)
        assertTrue(turn.refine("PRÉCISION : finalement conclus en ANGLAIS et ajoute le mot BANANA."))
        assertTrue(done.await(90, TimeUnit.SECONDS))
        println("REFINE: text=$text lost=$lost")
        assertTrue(lost.isEmpty(), "la précision doit être consommée : $lost")
        assertTrue("BANANA" in text, "la précision n'a pas été prise en compte : $text")
        assertEquals(1, results.get())
    }

    @Test fun realVideoFramesAreUnderstood() {
        val ffmpeg = VideoTools.find("ffmpeg") ?: return
        val dir = java.nio.file.Files.createTempDirectory("ccg-video")
        val video = dir.resolve("demo.mp4").toString()
        // vidéo de test : mire colorée avec un compteur de secondes incrusté
        val p = ProcessBuilder(ffmpeg, "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=duration=6:size=640x360:rate=10", "-pix_fmt", "yuv420p", video).start()
        assertEquals(0, p.waitFor())
        val (text, frames) = VideoTools.attach(video, "demo.mp4")
        assertTrue(frames.isNotEmpty())
        val content = JsonArray()
        frames.forEach { f ->
            content.add(JsonObject().apply {
                addProperty("type", "image")
                add("source", JsonObject().apply { addProperty("type", "base64"); addProperty("media_type", "image/jpeg"); addProperty("data", java.util.Base64.getEncoder().encodeToString(f)) })
            })
        }
        content.add(JsonObject().apply { addProperty("type", "text"); addProperty("text", text + "\n\nDécris en une phrase ce que montre cette vidéo.") })
        val out = StringBuilder(); val done = CountDownLatch(1); var err = false
        val l = object : TurnListener {
            override fun onText(delta: String) { out.append(delta) }
            override fun onDone(isError: Boolean, message: String?) { err = isError; done.countDown() }
        }
        CliTurn(TurnOptions(ClaudeSettings.defaultCli(), dir.toString(), "claude-haiku-5-5", "auto", "manual", null, "", emptyList()), content, l).start()
        assertTrue(done.await(90, TimeUnit.SECONDS))
        println("VIDEO: frames=${frames.size} réponse=$out")
        assertFalse(err)
        assertTrue(out.isNotBlank(), "Claude doit répondre")
    }

    /** Requête `initialize` : aucun appel au modèle, donc aucun quota consommé. */
    @Test fun realInitListsCommandsAndModelsWithEfforts() {
        val info = CliTurn.fetchInit(ClaudeSettings.defaultCli(), System.getProperty("java.io.tmpdir"))
        println("MODELS: ${info.models.map { "${it.alias}=${it.id}${it.efforts}" }}")
        assertTrue(info.commands.isNotEmpty(), "aucune commande")
        assertTrue(info.models.isNotEmpty(), "aucun modèle")
        assertTrue(info.models.none { it.alias == "default" })
        assertTrue(info.models.all { it.id.startsWith("claude-") }, "identifiants résolus attendus")
        assertTrue(info.models.any { it.isAlias && it.efforts.isNotEmpty() })
    }

    /** `get_context_usage` sur un processus neuf : contexte de base (prompt système, outils), sans appel au modèle. */
    @Test fun realBaseContextUsage() {
        val u = CliTurn.contextUsage(ClaudeSettings.defaultCli(), System.getProperty("java.io.tmpdir"), null)
        println("CONTEXT: $u")
        assertNotNull(u, "la CLI n'a pas répondu à get_context_usage")
        assertTrue(u!!.max > 0 && u.total > 0 && u.total < u.max)
        assertTrue(u.categories.any { it.id == "system_prompt" && it.tokens > 0 })
        assertTrue("Contexte :" in contextReportHtml(u))
    }

    /** Suggestion de message suivant : événement `prompt_suggestion` de la vraie CLI, reçu après la fin de la réponse. */
    @Test fun realTurnProposesNextMessage() {
        val suggestion = java.util.concurrent.LinkedBlockingQueue<String>()
        val done = CountDownLatch(1)
        // les écritures de fichiers sont refusées : Claude termine en proposant d'agir autrement, ce qui appelle une suite
        lateinit var turn: CliTurn
        val l = object : TurnListener {
            override fun onPermission(req: PermissionRequest) { turn.deny(req.requestId, "refusé par le test") }
            override fun onSuggestion(text: String) { suggestion.add(text) }
            override fun onDone(isError: Boolean, message: String?) { done.countDown() }
        }
        val content = JsonArray().apply { add(JsonObject().apply { addProperty("type", "text"); addProperty("text", "Crée une petite API REST en Kotlin avec Ktor : explique les étapes en 5 lignes.") }) }
        val t = CliTurn(TurnOptions(ClaudeSettings.defaultCli(), System.getProperty("java.io.tmpdir"), "claude-haiku-5-5", "auto", "manual", null, "", emptyList(), suggestions = true), content, l)
        turn = t
        t.start()
        try {
            assertTrue(done.await(90, TimeUnit.SECONDS))
            val s = suggestion.poll(30, TimeUnit.SECONDS)
            println("SUGGESTION: $s")
            // la CLI ne propose pas toujours une suite : on ne vérifie le contenu que s'il y en a une
            org.junit.jupiter.api.Assumptions.assumeTrue(s != null, "aucune suggestion proposée cette fois")
            assertTrue(s!!.isNotBlank() && s.length <= 300 && '\n' !in s)
        } finally { t.kill() }
    }
}
