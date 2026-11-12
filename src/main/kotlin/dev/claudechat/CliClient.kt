package dev.claudechat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedWriter
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Fenêtre de quota : part utilisée (0..1) et instant de réinitialisation (secondes epoch). */
data class QuotaWindow(val utilization: Double, val resetsAt: Long)
data class RateLimits(
    val fiveHour: QuotaWindow?, val sevenDay: QuotaWindow?,
    /** « allowed », « rejected »… tel que fourni par la CLI. */
    val status: String? = null,
    /** Fenêtre concernée par `status` (five_hour, seven_day) et sa réinitialisation. */
    val limitType: String? = null, val resetsAt: Long? = null,
)

/** Extrait les quotas d'un événement `rate_limit_event` de la CLI ; null s'il n'en contient pas. */
fun parseRateLimits(j: JsonObject): RateLimits? {
    val info = j["rate_limit_info"]?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val w = info["unifiedWindows"]?.takeIf { it.isJsonObject }?.asJsonObject
    fun win(k: String) = w?.get(k)?.takeIf { it.isJsonObject }?.asJsonObject?.let { o ->
        val u = o["utilization"]?.asDouble ?: return@let null
        QuotaWindow(u, o["resetsAt"]?.asLong ?: 0L)
    }
    val r = RateLimits(
        win("five_hour"), win("seven_day"),
        info["status"]?.takeIf { it.isJsonPrimitive }?.asString,
        info["rateLimitType"]?.takeIf { it.isJsonPrimitive }?.asString,
        info["resetsAt"]?.takeIf { it.isJsonPrimitive }?.asLong,
    )
    return r.takeIf { it.fiveHour != null || it.sevenDay != null || it.status == "rejected" }
}

data class McpEntry(val name: String, val url: String, val token: String?)

class PermissionRequest(
    val requestId: String,
    val tool: String,
    val input: JsonObject,
    val description: String,
)

class TurnOptions(
    val cli: String,
    val cwd: String,
    val model: String,
    val effort: String,
    val permissionMode: String,
    val sessionId: String?,
    val appendSystem: String,
    val mcp: List<McpEntry>,
    val fork: Boolean = false,
)

/** Callbacks appelés depuis le thread de lecture : l'appelant doit rebasculer sur l'EDT. */
interface TurnListener {
    fun onInit(sessionId: String, model: String?) {}
    fun onText(delta: String) {}
    fun onThinking(delta: String) {}
    fun onToolUse(id: String, name: String, input: JsonObject) {}
    fun onToolResult(id: String, text: String, isError: Boolean) {}
    fun onPermission(req: PermissionRequest) {}
    fun onUsage(input: Int, output: Int, costUsd: Double) {}
    /** Tokens en direct : taille du contexte et tokens de sortie cumulés du tour. */
    fun onLiveUsage(contextTokens: Int, outputTokens: Int) {}
    fun onRateLimits(limits: RateLimits) {}
    fun onDone(isError: Boolean, message: String?) {}
}

/**
 * Un tour de conversation = un processus `claude -p` en entrée/sortie stream-json.
 * Un seul processus pilote une session à la fois (RG-1) ; mode, effort et modèle
 * prennent effet au tour suivant (RG-3).
 */
class CliTurn(private val o: TurnOptions, private val content: JsonArray, private val l: TurnListener) {
    private var proc: Process? = null
    private var out: BufferedWriter? = null
    @Volatile private var killed = false
    private val stderr = StringBuffer()

    fun start() {
        val cmd = mutableListOf(
            o.cli, "-p", "--input-format", "stream-json", "--output-format", "stream-json",
            "--verbose", "--include-partial-messages", "--permission-prompt-tool", "stdio",
            "--permission-mode", o.permissionMode,
        )
        if (o.model.isNotBlank()) cmd += listOf("--model", o.model)
        if (o.effort.isNotBlank() && o.effort != "auto") cmd += listOf("--effort", o.effort)
        if (o.sessionId != null) cmd += listOf("--resume", o.sessionId)
        if (o.sessionId != null && o.fork) cmd += "--fork-session"
        if (o.appendSystem.isNotBlank()) cmd += listOf("--append-system-prompt", o.appendSystem)
        if (o.mcp.isNotEmpty()) cmd += listOf("--mcp-config", mcpJson(o.mcp))
        val pb = ProcessBuilder(cmd).directory(java.io.File(o.cwd))
        pb.environment().putIfAbsent("CLAUDE_CODE_ENTRYPOINT", "jetbrains-gui")
        val p = try { pb.start() } catch (e: Exception) {
            l.onDone(true, "Impossible de lancer « ${o.cli} » : ${e.message}")
            return
        }
        proc = p
        out = p.outputStream.bufferedWriter()
        Thread({ p.errorStream.bufferedReader().forEachLine { stderr.append(it).append('\n') } }, "claude-stderr").apply { isDaemon = true }.start()
        Thread({ read(p) }, "claude-stdout").apply { isDaemon = true }.start()
        send(JsonObject().apply {
            addProperty("type", "user")
            add("message", JsonObject().apply { addProperty("role", "user"); add("content", content) })
        })
    }

    private fun read(p: Process) {
        var finished = false
        try {
            p.inputStream.bufferedReader().forEachLine { line ->
                if (!line.startsWith("{")) return@forEachLine
                val j = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return@forEachLine
                when (j["type"]?.asString) {
                    "system" -> if (j["subtype"]?.asString == "init")
                        l.onInit(j["session_id"].asString, j["model"]?.asString)
                    "stream_event" -> stream(j["event"].asJsonObject)
                    "assistant" -> j["message"]?.asJsonObject?.get("content")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { b ->
                        val bo = b.asJsonObject
                        if (bo["type"]?.asString == "tool_use") l.onToolUse(bo["id"].asString, bo["name"].asString, bo["input"]?.asJsonObject ?: JsonObject())
                    }
                    "user" -> j["message"]?.asJsonObject?.get("content")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { b ->
                        val bo = b.asJsonObject
                        if (bo["type"]?.asString == "tool_result")
                            l.onToolResult(bo["tool_use_id"].asString, SessionStore.resultText(bo["content"]), bo["is_error"]?.asBoolean == true)
                    }
                    "rate_limit_event" -> parseRateLimits(j)?.let(l::onRateLimits)
                    "control_request" -> {
                        val r = j["request"].asJsonObject
                        if (r["subtype"]?.asString == "can_use_tool") {
                            l.onPermission(PermissionRequest(
                                j["request_id"].asString, r["tool_name"].asString,
                                r["input"]?.asJsonObject ?: JsonObject(), r["description"]?.asString ?: "",
                            ))
                        }
                    }
                    "result" -> {
                        finished = true
                        (j["usage"]?.asJsonObject ?: JsonObject()).let { u ->
                            l.onUsage(u["input_tokens"]?.asInt ?: 0, u["output_tokens"]?.asInt ?: 0, j["total_cost_usd"]?.asDouble ?: 0.0)
                        }
                        val err = j["is_error"]?.asBoolean == true
                        l.onDone(err, if (err) j["result"]?.asString ?: "Erreur" else null)
                        runCatching { out?.close() } // fin d'entrée : le processus se termine
                    }
                }
            }
        } catch (_: Exception) {}
        p.waitFor(5, TimeUnit.SECONDS)
        if (!finished && !killed) {
            val msg = stderr.toString().trim().ifEmpty { "Le processus claude s'est arrêté (code ${runCatching { p.exitValue() }.getOrDefault(-1)})." }
            l.onDone(true, msg)
        } else if (!finished) l.onDone(false, null)
    }

    private var ctx = 0
    private var doneOut = 0
    private var curOut = 0

    private fun stream(ev: JsonObject) {
        when (ev["type"]?.asString) {
            "message_start" -> ev["message"]?.asJsonObject?.get("usage")?.asJsonObject?.let { u ->
                ctx = listOf("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens").sumOf { u[it]?.takeIf { v -> v.isJsonPrimitive }?.asInt ?: 0 }
                curOut = 0
                l.onLiveUsage(ctx, doneOut)
            }
            "message_delta" -> ev["usage"]?.asJsonObject?.get("output_tokens")?.asInt?.let { curOut = it; l.onLiveUsage(ctx, doneOut + curOut) }
            "message_stop" -> { doneOut += curOut; curOut = 0 }
        }
        if (ev["type"]?.asString == "content_block_delta") {
            val d = ev["delta"].asJsonObject
            when (d["type"]?.asString) {
                "text_delta" -> l.onText(d["text"].asString)
                "thinking_delta" -> l.onThinking(d["thinking"].asString)
            }
        }
    }

    @Synchronized private fun send(o: JsonObject) {
        runCatching { out?.apply { write(o.toString()); newLine(); flush() } }
    }

    fun allow(requestId: String, updatedInput: JsonObject) = respond(requestId, JsonObject().apply {
        addProperty("behavior", "allow"); add("updatedInput", updatedInput)
    })

    fun deny(requestId: String, message: String) = respond(requestId, JsonObject().apply {
        addProperty("behavior", "deny"); addProperty("message", message)
    })

    private fun respond(requestId: String, body: JsonObject) = send(JsonObject().apply {
        addProperty("type", "control_response")
        add("response", JsonObject().apply {
            addProperty("subtype", "success"); addProperty("request_id", requestId); add("response", body)
        })
    })

    /** Interrompt la réponse en cours (FR-02). */
    fun interrupt() {
        send(JsonObject().apply {
            addProperty("type", "control_request"); addProperty("request_id", UUID.randomUUID().toString())
            add("request", JsonObject().apply { addProperty("subtype", "interrupt") })
        })
        Thread {
            if (proc?.waitFor(4, TimeUnit.SECONDS) != true) kill()
        }.apply { isDaemon = true }.start()
    }

    fun kill() { killed = true; proc?.destroy() }

    companion object {
        private fun mcpJson(list: List<McpEntry>) = JsonObject().apply {
            add("mcpServers", JsonObject().also { s ->
                list.forEach { m ->
                    s.add(m.name, JsonObject().apply {
                        addProperty("type", "http"); addProperty("url", m.url)
                        if (!m.token.isNullOrBlank()) add("headers", JsonObject().apply { addProperty("Authorization", "Bearer ${m.token}") })
                    })
                }
            })
        }.toString()

        /** Question ponctuelle sans session ni outil (message de commit). Bloquant. */
        fun oneShot(cli: String, cwd: String, model: String, prompt: String, cancelled: () -> Boolean): String {
            val cmd = mutableListOf(cli, "-p", "--no-session-persistence", "--permission-mode", "dontAsk", "--permission-prompts", "none")
            if (model.isNotBlank()) cmd += listOf("--model", model)
            val p = ProcessBuilder(cmd).directory(java.io.File(cwd)).start()
            p.outputStream.bufferedWriter().use { it.write(prompt) }
            val err = StringBuffer()
            Thread { p.errorStream.bufferedReader().forEachLine { err.append(it).append('\n') } }.apply { isDaemon = true }.start()
            val sb = StringBuilder()
            val rd = Thread { sb.append(p.inputStream.bufferedReader().readText()) }.apply { isDaemon = true; start() }
            while (rd.isAlive) { if (cancelled()) { p.destroy(); break }; rd.join(200) }
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() != 0 && sb.isBlank()) throw java.io.IOException(err.toString().trim())
            return sb.toString()
        }

        /** Exécute `claude <args>` et renvoie la sortie (version, mise à jour). Bloquant. */
        fun run(cli: String, vararg args: String, timeoutSec: Long = 120): String = try {
            val p = ProcessBuilder(listOf(cli) + args).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor(timeoutSec, TimeUnit.SECONDS)
            text.trim()
        } catch (e: Exception) { "Erreur : ${e.message}" }
    }
}
