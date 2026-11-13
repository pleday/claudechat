package dev.claudechat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Teste le protocole stream-json avec un faux exécutable `claude` (script shell). */
class CliTurnTest {
    @TempDir lateinit var tmp: Path

    private fun fake(body: String): String {
        assumeTrue(!System.getProperty("os.name").lowercase().contains("win"))
        val f = tmp.resolve("claude")
        Files.writeString(f, "#!/bin/bash\n$body\n")
        f.toFile().setExecutable(true)
        return f.toString()
    }

    private fun opts(cli: String, mode: String = "manual", session: String? = null, fork: Boolean = false) =
        TurnOptions(cli, tmp.toString(), "sonnet", "auto", mode, session, "", emptyList(), fork)

    private val prompt = JsonArray().apply { add(JsonObject().apply { addProperty("type", "text"); addProperty("text", "salut") }) }

    private class Rec : TurnListener {
        val events = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(1)
        lateinit var turn: CliTurn
        var perm: ((PermissionRequest) -> Unit)? = null
        override fun onInit(sessionId: String, model: String?) { events += "init:$sessionId" }
        override fun onText(delta: String) { events += "text:$delta" }
        override fun onThinking(delta: String) { events += "think:$delta" }
        override fun onToolUse(id: String, name: String, input: JsonObject) { events += "tool:$name" }
        override fun onToolResult(id: String, text: String, isError: Boolean) { events += "result:$text:$isError" }
        override fun onPermission(req: PermissionRequest) { events += "perm:${req.tool}"; perm?.invoke(req) }
        override fun onUsage(input: Int, output: Int, costUsd: Double) { events += "usage:$input/$output/$costUsd" }
        override fun onDone(isError: Boolean, message: String?) { events += "done:$isError:${message ?: ""}"; done.countDown() }
    }

    private fun run(cli: String, o: TurnOptions = opts(cli), setup: (Rec) -> Unit = {}): Pair<Rec, CliTurn> {
        val rec = Rec(); setup(rec)
        val t = CliTurn(o, prompt, rec); rec.turn = t; t.start()
        assertTrue(rec.done.await(15, TimeUnit.SECONDS), "timeout, événements : ${rec.events}")
        return rec to t
    }

    @Test fun streamsTextThinkingToolsAndUsage() {
        val cli = fake("""
read -r line
echo '{"type":"system","subtype":"init","session_id":"sid-1","model":"m"}'
echo '{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"hum"}}}'
echo '{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"Bon"}}}'
echo '{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"jour"}}}'
echo '{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"/x"}}]}}'
echo '{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}'
echo '{"type":"result","is_error":false,"total_cost_usd":0.5,"usage":{"input_tokens":3,"output_tokens":4}}'
""")
        val (rec, _) = run(cli)
        assertEquals(
            listOf("init:sid-1", "think:hum", "text:Bon", "text:jour", "tool:Read", "result:ok:false", "usage:3/4/0.5", "done:false:"),
            rec.events.toList(),
        )
    }

    @Test fun keepsInputOpenAndResumesAfterResult() {
        val out = tmp.resolve("after.txt")
        // 1er result, puis la CLI reprend (tâche de fond) et demande une permission ; la réponse doit lui parvenir
        val cli = fake("""
read -r line
echo '{"type":"result","is_error":false}'
sleep 1
echo '{"type":"control_request","request_id":"r9","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{}}}'
read -r answer
echo "${'$'}answer" > $out
echo '{"type":"result","is_error":false}'
""")
        val rec = object : TurnListener {
            val events = CopyOnWriteArrayList<String>()
            val second = CountDownLatch(2)
            lateinit var turn: CliTurn
            override fun onResumed() { events += "resumed" }
            override fun onPermission(req: PermissionRequest) { events += "perm:${req.tool}"; turn.allow(req.requestId, req.input) }
            override fun onDone(isError: Boolean, message: String?) { events += "done:$isError"; second.countDown() }
        }
        val t = CliTurn(opts(cli), prompt, rec); rec.turn = t; t.start()
        assertTrue(rec.second.await(15, TimeUnit.SECONDS), "événements : ${rec.events}")
        assertEquals(listOf("done:false", "resumed", "perm:Bash", "done:false"), rec.events.toList())
        assertTrue(Files.readString(out).contains("\"behavior\":\"allow\""))
    }

    @Test fun waitsForMcpBeforeSendingAndReportsFailures() {
        val out = tmp.resolve("order.txt")
        // 1re réponse : sonarqube « pending » ; 2e : « failed » ; le message utilisateur ne doit arriver qu'ensuite
        val cli = fake("""
read -r a; echo "${'$'}a" >> $out
echo '{"type":"control_response","response":{"subtype":"success","request_id":"mcp-wait-0","response":{"mcpServers":[{"name":"sonarqube","status":"pending"},{"name":"idea","status":"connected"}]}}}'
read -r b; echo "${'$'}b" >> $out
echo '{"type":"control_response","response":{"subtype":"success","request_id":"mcp-wait-1","response":{"mcpServers":[{"name":"sonarqube","status":"failed"},{"name":"idea","status":"connected"}]}}}'
read -r c; echo "${'$'}c" >> $out
echo '{"type":"result","is_error":false}'
""")
        val problems = CopyOnWriteArrayList<String>()
        val rec = Rec()
        val t = CliTurn(TurnOptions(cli, tmp.toString(), "sonnet", "auto", "manual", null, "", emptyList(), false, waitForMcp = true), prompt,
            object : TurnListener by rec { override fun onMcpProblem(servers: List<String>) { problems += servers } })
        t.start()
        assertTrue(rec.done.await(15, TimeUnit.SECONDS), "événements : ${rec.events}")
        val lines = Files.readAllLines(out).map { com.google.gson.JsonParser.parseString(it).asJsonObject }
        assertEquals(listOf("control_request", "control_request", "user"), lines.map { it["type"].asString })
        assertEquals(listOf("sonarqube (failed)"), problems.toList())
    }

    @Test fun sendsUserMessageAsStreamJson() {
        val out = tmp.resolve("stdin.txt")
        val cli = fake("""read -r line; echo "${'$'}line" > $out; echo '{"type":"result","is_error":false}'""")
        run(cli)
        val sent = com.google.gson.JsonParser.parseString(Files.readString(out)).asJsonObject
        assertEquals("user", sent["type"].asString)
        assertEquals("salut", sent["message"].asJsonObject["content"].asJsonArray[0].asJsonObject["text"].asString)
    }

    @Test fun permissionAllowRoundTrip() {
        val out = tmp.resolve("reply.txt")
        val cli = fake("""
read -r line
echo '{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"ls"},"description":"ls"}}'
read -r reply
echo "${'$'}reply" > $out
echo '{"type":"result","is_error":false}'
""")
        run(cli) { r -> r.perm = { req -> /* répond depuis un autre thread */ Thread { Thread.sleep(100); r.turn.allow(req.requestId, req.input) }.start() } }
        val reply = com.google.gson.JsonParser.parseString(Files.readString(out)).asJsonObject
        assertEquals("control_response", reply["type"].asString)
        val resp = reply["response"].asJsonObject
        assertEquals("r1", resp["request_id"].asString)
        assertEquals("allow", resp["response"].asJsonObject["behavior"].asString)
        assertEquals("ls", resp["response"].asJsonObject["updatedInput"].asJsonObject["command"].asString)
    }

    @Test fun permissionDenyCarriesMessage() {
        val out = tmp.resolve("reply.txt")
        val cli = fake("""
read -r line
echo '{"type":"control_request","request_id":"r2","request":{"subtype":"can_use_tool","tool_name":"Edit","input":{}}}'
read -r reply
echo "${'$'}reply" > $out
echo '{"type":"result","is_error":false}'
""")
        run(cli) { r -> r.perm = { req -> Thread { Thread.sleep(100); r.turn.deny(req.requestId, "non merci") }.start() } }
        val body = com.google.gson.JsonParser.parseString(Files.readString(out)).asJsonObject["response"].asJsonObject["response"].asJsonObject
        assertEquals("deny", body["behavior"].asString)
        assertEquals("non merci", body["message"].asString)
    }

    @Test fun passesFlagsAndResumeAndFork() {
        val out = tmp.resolve("args.txt")
        val cli = fake("""echo "${'$'}@" > $out; read -r line; echo '{"type":"result","is_error":false}'""")
        run(cli, opts(cli, mode = "plan", session = "abc", fork = true))
        val args = Files.readString(out)
        assertTrue("--permission-mode plan" in args, args)
        assertTrue("--resume abc" in args, args)
        assertTrue("--fork-session" in args, args)
        assertTrue("--model sonnet" in args, args)
        assertFalse("--effort" in args, "effort=auto ne doit pas être transmis")
    }

    @Test fun reportsErrorResult() {
        val cli = fake("""read -r line; echo '{"type":"result","is_error":true,"result":"limite atteinte"}'""")
        val (rec, _) = run(cli)
        assertTrue("done:true:limite atteinte" in rec.events)
    }

    @Test fun reportsStderrWhenProcessDiesWithoutResult() {
        val cli = fake("""echo 'non connecté' >&2; exit 1""")
        val (rec, _) = run(cli)
        assertTrue(rec.events.last().startsWith("done:true:") && "non connecté" in rec.events.last(), rec.events.toString())
    }

    @Test fun reportsMissingExecutable() {
        val (rec, _) = run("/nonexistent/claude")
        assertTrue(rec.events.single().startsWith("done:true:Impossible de lancer"))
    }

    @Test fun mcpServersGoToMcpConfig() {
        val out = tmp.resolve("args.txt")
        val cli = fake("""echo "${'$'}@" > $out; read -r line; echo '{"type":"result","is_error":false}'""")
        run(cli, TurnOptions(cli, tmp.toString(), "", "high", "manual", null, "", listOf(McpEntry("srv", "https://x/mcp", "tok")), false))
        val args = Files.readString(out)
        assertTrue("--mcp-config" in args && "Bearer tok" in args && "--effort high" in args, args)
    }

    @Test fun oneShotReturnsOutput() {
        val cli = fake("""cat >/dev/null; echo "feat: ok"""")
        assertEquals("feat: ok", CliTurn.oneShot(cli, tmp.toString(), "", "diff") { false }.trim())
    }
}

class CliTurnLiveUsageTest {
    @TempDir lateinit var tmp: Path

    @Test fun emitsLiveUsageAndRateLimits() {
        org.junit.jupiter.api.Assumptions.assumeTrue(!System.getProperty("os.name").lowercase().contains("win"))
        val f = tmp.resolve("claude")
        Files.writeString(f, """#!/bin/bash
read -r line
echo '{"type":"stream_event","event":{"type":"message_start","message":{"usage":{"input_tokens":2,"cache_creation_input_tokens":10,"cache_read_input_tokens":100}}}}'
echo '{"type":"stream_event","event":{"type":"message_delta","usage":{"output_tokens":7}}}'
echo '{"type":"stream_event","event":{"type":"message_stop"}}'
echo '{"type":"stream_event","event":{"type":"message_start","message":{"usage":{"input_tokens":3,"cache_creation_input_tokens":0,"cache_read_input_tokens":120}}}}'
echo '{"type":"stream_event","event":{"type":"message_delta","usage":{"output_tokens":5}}}'
echo '{"type":"rate_limit_event","rate_limit_info":{"unifiedWindows":{"five_hour":{"utilization":0.42,"resetsAt":1}}}}'
echo '{"type":"result","is_error":false}'
""")
        f.toFile().setExecutable(true)
        val live = CopyOnWriteArrayList<Pair<Int, Int>>()
        var limits: RateLimits? = null
        val done = CountDownLatch(1)
        val l = object : TurnListener {
            override fun onLiveUsage(contextTokens: Int, outputTokens: Int) { live += contextTokens to outputTokens }
            override fun onRateLimits(limits0: RateLimits) { limits = limits0 }
            override fun onDone(isError: Boolean, message: String?) = done.countDown()
        }
        val content = JsonArray().apply { add(JsonObject().apply { addProperty("type", "text"); addProperty("text", "x") }) }
        CliTurn(TurnOptions(f.toString(), tmp.toString(), "", "auto", "manual", null, "", emptyList()), content, l).start()
        assertTrue(done.await(15, TimeUnit.SECONDS))
        assertEquals(listOf(112 to 0, 112 to 7, 123 to 7, 123 to 12), live.toList())
        assertEquals(0.42, limits!!.fiveHour!!.utilization, 1e-9)
    }
}

class ProbeQuotaTest {
    @TempDir lateinit var tmp: Path

    private fun fake(body: String): String {
        org.junit.jupiter.api.Assumptions.assumeTrue(!System.getProperty("os.name").lowercase().contains("win"))
        val f = tmp.resolve("claude"); Files.writeString(f, "#!/bin/bash\n$body\n"); f.toFile().setExecutable(true); return f.toString()
    }

    @Test fun readsRateLimitsFromProbe() {
        val args = tmp.resolve("args.txt")
        val cli = fake("""echo "${'$'}@" > $args; cat >/dev/null
echo '{"type":"system","subtype":"init"}'
echo '{"type":"rate_limit_event","rate_limit_info":{"status":"allowed","unifiedWindows":{"five_hour":{"utilization":0.2,"resetsAt":99}}}}'
echo '{"type":"result","is_error":false}'""")
        val r = CliTurn.probeQuota(cli, tmp.toString())
        assertNull(r.error)
        assertEquals(0.2, r.limits!!.fiveHour!!.utilization, 1e-9)
        assertTrue("--no-session-persistence" in Files.readString(args) && "claude-haiku-5-5" in Files.readString(args))
    }

    @Test fun explainsWhenNoQuotaIsProvided() {
        val cli = fake("""cat >/dev/null; echo '{"type":"result","is_error":false}'""")
        val r = CliTurn.probeQuota(cli, tmp.toString())
        assertNull(r.limits)
        assertTrue("aucune information de quota" in r.error!!)
    }

    @Test fun reportsStderrOnFailure() {
        val cli = fake("""cat >/dev/null; echo 'Not logged in' >&2; exit 1""")
        assertEquals("Not logged in", CliTurn.probeQuota(cli, tmp.toString()).error)
    }

    @Test fun reportsMissingCli() = assertTrue(CliTurn.probeQuota("/nonexistent/claude", tmp.toString()).error!!.startsWith("impossible de lancer"))
}

class RefineTest {
    @TempDir lateinit var tmp: Path

    private fun fake(body: String): String {
        org.junit.jupiter.api.Assumptions.assumeTrue(!System.getProperty("os.name").lowercase().contains("win"))
        val f = tmp.resolve("claude"); Files.writeString(f, "#!/bin/bash\n$body\n"); f.toFile().setExecutable(true); return f.toString()
    }

    private class Rec : TurnListener {
        val lost = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(1)
        override fun onRefineLost(texts: List<String>) { lost += texts }
        override fun onDone(isError: Boolean, message: String?) = done.countDown()
    }

    private val content = JsonArray().apply { add(JsonObject().apply { addProperty("type", "text"); addProperty("text", "go") }) }
    private fun opts(cli: String) = TurnOptions(cli, tmp.toString(), "", "auto", "manual", null, "", emptyList())

    @Test fun refineSendsNextPriorityMessageAndIsConsumedWhenReplayed() {
        val got = tmp.resolve("second.txt")
        val cli = fake("""read -r first
read -r -t 5 second; echo "${'$'}second" > $got
echo '{"type":"user","message":{"role":"user","content":[{"type":"text","text":"plus court"}]}}'
echo '{"type":"result","is_error":false}'""")
        val rec = Rec(); val t = CliTurn(opts(cli), content, rec); t.start()
        assertTrue(t.refine("plus court"))
        assertTrue(rec.done.await(15, TimeUnit.SECONDS))
        val sent = com.google.gson.JsonParser.parseString(Files.readString(got)).asJsonObject
        assertEquals("next", sent["priority"].asString)
        assertEquals("plus court", sent["message"].asJsonObject["content"].asJsonArray[0].asJsonObject["text"].asString)
        assertTrue(rec.lost.isEmpty(), "précision consommée : rien à renvoyer")
    }

    @Test fun refineNotConsumedBeforeResultIsReportedLost() {
        val cli = fake("""read -r first
read -r -t 5 second
echo '{"type":"result","is_error":false}'""")
        val rec = Rec(); val t = CliTurn(opts(cli), content, rec); t.start()
        assertTrue(t.refine("trop tard"))
        assertTrue(rec.done.await(15, TimeUnit.SECONDS))
        assertEquals(listOf("trop tard"), rec.lost.toList())
    }

    @Test fun refineAfterTurnEndedReturnsFalse() {
        val cli = fake("""read -r first; echo '{"type":"result","is_error":false}'""")
        val rec = Rec(); val t = CliTurn(opts(cli), content, rec); t.start()
        assertTrue(rec.done.await(15, TimeUnit.SECONDS))
        assertFalse(t.refine("après"))
    }
}

class ContextWindowTest {
    @TempDir lateinit var tmp: Path

    @Test fun reportsContextWindowFromResult() {
        org.junit.jupiter.api.Assumptions.assumeTrue(!System.getProperty("os.name").lowercase().contains("win"))
        val f = tmp.resolve("claude")
        Files.writeString(f, """#!/bin/bash
read -r line
echo '{"type":"result","is_error":false,"modelUsage":{"claude-haiku-5-5":{"contextWindow":1000000,"inputTokens":3}}}'
""")
        f.toFile().setExecutable(true)
        val got = CopyOnWriteArrayList<Pair<String, Int>>()
        val done = CountDownLatch(1)
        val l = object : TurnListener {
            override fun onContextWindow(model: String, tokens: Int) { got += model to tokens }
            override fun onDone(isError: Boolean, message: String?) = done.countDown()
        }
        val content = JsonArray().apply { add(JsonObject().apply { addProperty("type", "text"); addProperty("text", "x") }) }
        CliTurn(TurnOptions(f.toString(), tmp.toString(), "", "auto", "manual", null, "", emptyList()), content, l).start()
        assertTrue(done.await(15, TimeUnit.SECONDS))
        assertEquals(listOf("claude-haiku-5-5" to 1_000_000), got.toList())
    }
}
