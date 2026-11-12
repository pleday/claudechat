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
}
