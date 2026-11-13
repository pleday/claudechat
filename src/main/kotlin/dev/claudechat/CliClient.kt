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

data class ProbeResult(val limits: RateLimits?, val error: String?)

/** Commande slash exposée par la CLI (natives, personnalisées, skills, plugins). */
data class CliCommand(val name: String, val description: String)

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
    /** Attendre que les serveurs MCP aient fini de se connecter avant d'envoyer le message (sinon leurs outils manquent). */
    val waitForMcp: Boolean = false,
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
    /** Taille de la fenêtre de contexte du modèle (fournie avec le résultat). */
    fun onContextWindow(model: String, tokens: Int) {}
    /** Précisions envoyées mais non consommées avant la fin du tour : à renvoyer comme nouveau message. */
    fun onRefineLost(texts: List<String>) {}
    fun onDone(isError: Boolean, message: String?) {}
    /** La CLI a repris du travail après un `result` (tâche de fond, message empilé…) : le tour est de nouveau en cours. */
    fun onResumed() {}
    /** Serveurs MCP non connectés (« nom (statut) ») à l'envoi du message : leurs outils sont indisponibles pour ce tour. */
    fun onMcpProblem(servers: List<String>) {}
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
    @Volatile private var ended = false
    private val refinements = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val stderr = StringBuffer()
    @Volatile private var lastActivity = System.nanoTime()
    /** Dernier statut MCP reçu (nom, statut) en réponse à `mcp_status`. */
    @Volatile private var mcpStatus: List<Pair<String, String>>? = null

    fun start() {
        val cmd = mutableListOf(
            o.cli, "-p", "--input-format", "stream-json", "--output-format", "stream-json",
            "--verbose", "--include-partial-messages", "--replay-user-messages", "--permission-prompt-tool", "stdio",
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
        val first = JsonObject().apply {
            addProperty("type", "user")
            add("message", JsonObject().apply { addProperty("role", "user"); add("content", content) })
        }
        if (o.waitForMcp) Thread({ awaitMcp(); if (!killed) send(first) }, "claude-mcp-wait").apply { isDaemon = true }.start()
        else send(first)
    }

    /**
     * La CLI démarre ses serveurs MCP en tâche de fond : au premier message, ceux qui sont encore « pending » n'ont
     * pas d'outils (le modèle les croit absents ou déconnectés). On attend donc leur connexion (15 s max) avant d'envoyer.
     */
    private fun awaitMcp() {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MCP_WAIT_MS)
        var i = 0
        while (!killed && proc?.isAlive == true && System.nanoTime() < deadline) {
            mcpStatus = null
            send(JsonObject().apply {
                addProperty("type", "control_request"); addProperty("request_id", "mcp-wait-${i++}")
                add("request", JsonObject().apply { addProperty("subtype", "mcp_status") })
            })
            var w = 0
            while (mcpStatus == null && w++ < 40 && !killed) Thread.sleep(50)
            val st = mcpStatus ?: break // la CLI ne répond pas à mcp_status : on n'attend pas
            if (st.none { it.second == "pending" }) break
            Thread.sleep(300)
        }
        val bad = mcpStatus?.filter { it.second == "failed" || it.second == "pending" }.orEmpty()
        if (bad.isNotEmpty()) {
            LOG.warn("Serveurs MCP non prêts à l'envoi : $bad")
            l.onMcpProblem(bad.map { "${it.first} (${it.second})" })
        }
    }

    private fun read(p: Process) {
        var finished = false
        try {
            // un événement illisible est ignoré et journalisé : il ne doit pas interrompre toute la réponse
            fun handle(j: JsonObject) {
                if (ended && j["type"]?.asString in RESUME_EVENTS) {
                    // la CLI continue après un `result` : on garde l'entrée ouverte et on repasse en « en cours »
                    LOG.info("Activité « ${j["type"]?.asString} » après un result : tour repris")
                    ended = false; finished = false
                    l.onResumed()
                }
                when (j["type"]?.asString) {
                    "system" -> if (j["subtype"]?.asString == "init")
                        l.onInit(j["session_id"].asString, j["model"]?.asString)
                    "stream_event" -> stream(j["event"].asJsonObject)
                    "assistant" -> j["message"]?.asJsonObject?.get("content")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { b ->
                        val bo = b.asJsonObject
                        if (bo["type"]?.asString == "tool_use") l.onToolUse(bo["id"].asString, bo["name"].asString, bo["input"]?.asJsonObject ?: JsonObject())
                    }
                    "user" -> {
                        val c = j["message"]?.asJsonObject?.get("content")
                        if (c != null && c.isJsonPrimitive) refinements.remove(c.asString.trim())
                        else if (c != null && c.isJsonArray) c.asJsonArray.forEach { b ->
                            val bo = b.asJsonObject
                            when (bo["type"]?.asString) {
                                "tool_result" -> l.onToolResult(bo["tool_use_id"].asString, SessionStore.resultText(bo["content"]), bo["is_error"]?.asBoolean == true)
                                // la CLI rejoue le message quand elle le prend en compte : la précision est consommée
                                "text" -> refinements.remove(bo["text"]?.asString?.trim())
                            }
                        }
                    }
                    "control_response" -> j["response"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { resp ->
                        resp["request_id"]?.asString?.let { rid ->
                            pendingContext.remove(rid)?.complete(if (resp["subtype"]?.asString == "success") controlPayload(j) else null)
                        }
                        if (resp["request_id"]?.asString?.startsWith("mcp-wait") == true)
                            mcpStatus = resp["response"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("mcpServers")?.takeIf { it.isJsonArray }
                                ?.asJsonArray?.mapNotNull { e -> e.asJsonObject.let { o -> o["name"]?.asString?.let { n -> n to (o["status"]?.asString ?: "") } } }
                                ?: emptyList()
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
                        finished = true; ended = true
                        refinements.toList().takeIf { it.isNotEmpty() }?.let { refinements.clear(); l.onRefineLost(it) }
                        j["modelUsage"]?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (m, v) ->
                            v.takeIf { it.isJsonObject }?.asJsonObject?.get("contextWindow")?.takeIf { it.isJsonPrimitive }?.asInt?.let { l.onContextWindow(m, it) }
                        }
                        (j["usage"]?.asJsonObject ?: JsonObject()).let { u ->
                            l.onUsage(u["input_tokens"]?.asInt ?: 0, u["output_tokens"]?.asInt ?: 0, j["total_cost_usd"]?.asDouble ?: 0.0)
                        }
                        val err = j["is_error"]?.asBoolean == true
                        l.onDone(err, if (err) j["result"]?.asString ?: "Erreur" else null)
                        closeInputWhenQuiet()
                    }
                }
            }
            p.inputStream.bufferedReader().forEachLine { line ->
                lastActivity = System.nanoTime()
                if (!line.startsWith("{")) return@forEachLine
                val j = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return@forEachLine
                try { handle(j) } catch (e: Exception) { LOG.warn("Événement de la CLI ignoré (${e.javaClass.simpleName}: ${e.message}) : ${line.take(200)}") }
            }
        } catch (_: Exception) {}
        p.waitFor(5, TimeUnit.SECONDS)
        if (!finished && !killed) {
            val msg = stderr.toString().trim().ifEmpty { "Le processus claude s'est arrêté (code ${runCatching { p.exitValue() }.getOrDefault(-1)})." }
            l.onDone(true, msg)
        } else if (!finished) l.onDone(false, null)
    }

    /**
     * Ferme l'entrée (le processus se termine) seulement quand la CLI est restée silencieuse un long moment après
     * le `result`. La fermer tout de suite faisait échouer les permissions d'un travail qui reprend (« Stream closed »).
     * Si l'activité reprend, [ended] repasse à false et ce guetteur s'arrête ; le `result` suivant en relance un autre.
     */
    private fun closeInputWhenQuiet() {
        Thread({
            while (ended && !killed && proc?.isAlive == true) {
                Thread.sleep(2000)
                if (ended && System.nanoTime() - lastActivity > QUIET_BEFORE_CLOSE_NS) { runCatching { out?.close() }; return@Thread }
            }
        }, "claude-close").apply { isDaemon = true }.start()
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

    @Synchronized private fun send(o: JsonObject) { trySend(o) }

    /** Écrit un message sur l'entrée de la CLI ; false si elle est fermée (le processus se termine) ou illisible. */
    @Synchronized private fun trySend(o: JsonObject): Boolean =
        runCatching { out!!.apply { write(o.toString()); newLine(); flush() } }.isSuccess

    private val pendingContext = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<JsonObject?>>()
    private val contextSeq = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Contexte de cette conversation, mesuré par le processus qui la porte (exact, serveurs MCP compris). Bloquant ;
     * null si le processus est terminé, son entrée fermée, ou s'il ne répond pas dans [timeoutMs].
     */
    fun contextUsage(timeoutMs: Long = 8_000): ContextUsage? {
        if (killed || proc?.isAlive != true) return null
        val id = "ctx-${contextSeq.incrementAndGet()}"
        val f = java.util.concurrent.CompletableFuture<JsonObject?>()
        pendingContext[id] = f
        val sent = trySend(controlRequest(id, "get_context_usage"))
        if (!sent) { pendingContext.remove(id); return null }
        return try { f.get(timeoutMs, TimeUnit.MILLISECONDS)?.let(::parseContextUsage) }
        catch (_: Exception) { null }
        finally { pendingContext.remove(id) }
    }

    /**
     * Précise la consigne pendant que Claude travaille : le message est injecté au prochain point d'arrêt
     * (priority "next"), sans interrompre. Renvoie false si le tour est déjà terminé.
     */
    fun refine(text: String): Boolean {
        if (ended || killed || proc?.isAlive != true) return false
        refinements += text.trim()
        send(JsonObject().apply {
            addProperty("type", "user")
            add("message", JsonObject().apply {
                addProperty("role", "user")
                add("content", JsonArray().apply { add(JsonObject().apply { addProperty("type", "text"); addProperty("text", text) }) })
            })
            addProperty("priority", "next")
        })
        return true
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
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(CliTurn::class.java)
        private val RESUME_EVENTS = setOf("stream_event", "assistant", "control_request")
        /** Silence après un `result` avant de fermer l'entrée de la CLI. */
        /** Processus utilitaires (commandes, quota) : inutile de démarrer les serveurs MCP de l'utilisateur à chaque fois. */
        private val NO_MCP = listOf("--strict-mcp-config", "--mcp-config", """{"mcpServers":{}}""")
        private const val MCP_WAIT_MS = 15_000L
        var QUIET_BEFORE_CLOSE_NS = TimeUnit.MINUTES.toNanos(3)

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

        /**
         * Mesure les quotas sans conversation : une requête minimale (Haiku, sans session ni outil) dont on lit
         * l'événement `rate_limit_event`. Coût : quelques millièmes de dollar. Bloquant.
         */
        fun probeQuota(cli: String, cwd: String, timeoutSec: Long = 60): ProbeResult = try {
            val cmd = listOf(cli, "-p", "--output-format", "stream-json", "--verbose", "--no-session-persistence",
                "--permission-mode", "dontAsk", "--permission-prompts", "none", "--model", "claude-haiku-5-5") + NO_MCP
            val p = ProcessBuilder(cmd).directory(java.io.File(cwd)).start()
            p.outputStream.bufferedWriter().use { it.write("ok") }
            val err = StringBuffer()
            Thread { p.errorStream.bufferedReader().forEachLine { err.append(it).append('\n') } }.apply { isDaemon = true }.start()
            var limits: RateLimits? = null
            val reader = Thread {
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (line.startsWith("{") && line.contains("rate_limit_event"))
                        runCatching { parseRateLimits(JsonParser.parseString(line).asJsonObject) }.getOrNull()?.let { limits = it }
                }
            }.apply { isDaemon = true; start() }
            reader.join(timeoutSec * 1000)
            if (reader.isAlive) { p.destroy(); ProbeResult(limits, "délai dépassé") }
            else limits?.let { ProbeResult(it, null) }
                ?: ProbeResult(null, err.toString().trim().ifEmpty { "la CLI n'a fourni aucune information de quota (compte API, proxy ou version sans cette donnée ?)" })
        } catch (e: Exception) { ProbeResult(null, "impossible de lancer la CLI : ${e.message}") }

        private fun controlRequest(id: String, subtype: String) = JsonObject().apply {
            addProperty("type", "control_request"); addProperty("request_id", id)
            add("request", JsonObject().apply { addProperty("subtype", subtype) })
        }

        /**
         * Lance un processus `claude` à part, lui envoie une seule requête de contrôle (aucun message : aucun appel au
         * modèle) et renvoie sa réponse complète ; null en cas d'échec ou de délai dépassé. Bloquant.
         */
        private fun controlOnce(args: List<String>, cwd: String, id: String, subtype: String, timeoutSec: Long): JsonObject? = try {
            val p = ProcessBuilder(args).directory(java.io.File(cwd)).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val w = p.outputStream.bufferedWriter()
            w.write(controlRequest(id, subtype).toString()); w.newLine(); w.flush()
            var res: JsonObject? = null
            val reader = Thread {
                p.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!line.startsWith("{") || !line.contains("\"$id\"")) continue
                        res = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
                        break
                    }
                }
            }.apply { isDaemon = true; start() }
            reader.join(timeoutSec * 1000)
            runCatching { w.close() }
            p.destroy()
            res
        } catch (_: Exception) { null }

        private fun baseArgs(cli: String) =
            listOf(cli, "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose", "--no-session-persistence")

        /**
         * Commandes slash et modèles annoncés par la CLI (requête `initialize`, sans appel au modèle). Bloquant ;
         * listes vides en cas d'échec.
         */
        fun fetchInit(cli: String, cwd: String, timeoutSec: Long = 20): CliInit {
            val j = controlOnce(baseArgs(cli) + NO_MCP, cwd, "init-cmds", "initialize", timeoutSec) ?: return CliInit(emptyList(), emptyList())
            return CliInit(parseCommands(j), parseModels(j))
        }

        fun listCommands(cli: String, cwd: String, timeoutSec: Long = 20): List<CliCommand> = fetchInit(cli, cwd, timeoutSec).commands

        /**
         * Contexte d'une conversation mesuré hors de son processus : la session est rechargée (`--resume`) sans rien
         * envoyer ni écrire. Les serveurs MCP ne sont pas démarrés : leurs outils ne sont pas comptés ([ContextUsage.approximate]).
         * Sans [sessionId], seul le contexte de base (prompt système, outils) est mesuré. Bloquant ; null en cas d'échec.
         */
        fun contextUsage(cli: String, cwd: String, sessionId: String?, timeoutSec: Long = 30): ContextUsage? {
            val args = baseArgs(cli) + (if (sessionId != null) listOf("--resume", sessionId) else emptyList()) + NO_MCP
            val j = controlOnce(args, cwd, "ctx-once", "get_context_usage", timeoutSec) ?: return null
            if (j["response"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("subtype")?.asString != "success") return null
            return controlPayload(j)?.let(::parseContextUsage)?.copy(approximate = true)
        }

        /** Extrait les commandes d'une réponse `initialize` ; vide si le format est inattendu. */
        fun parseCommands(j: JsonObject): List<CliCommand> {
            val arr = j["response"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("response")?.takeIf { it.isJsonObject }
                ?.asJsonObject?.get("commands")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
            return arr.mapNotNull { e ->
                val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val name = o["name"]?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                CliCommand(name, o["description"]?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().replace(Regex("\\s+"), " "))
            }
        }

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
