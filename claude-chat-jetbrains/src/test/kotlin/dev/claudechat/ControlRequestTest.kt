package dev.claudechat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Requêtes de contrôle sur le processus vivant d'une conversation (mode de permission, renommage). */
class ControlRequestTest {
    @TempDir lateinit var tmp: Path

    private fun fake(body: String): String {
        org.junit.jupiter.api.Assumptions.assumeTrue(!System.getProperty("os.name").lowercase().contains("win"))
        val f = tmp.resolve("claude"); Files.writeString(f, "#!/bin/bash\n${body.replace('§', '$')}\n"); f.toFile().setExecutable(true); return f.toString()
    }

    /** Processus qui répond aux requêtes de contrôle et journalise ce qu'il reçoit dans [log]. */
    private fun live(log: Path, listener: TurnListener = object : TurnListener {}): CliTurn {
        val cli = fake("""read -r first
echo '{"type":"system","subtype":"init","session_id":"s1"}'
while read -r line; do
  echo "§line" >> $log
  id=§(echo "§line" | sed -E 's/.*"request_id":"([^"]+)".*/\1/')
  case "§line" in
    *set_permission_mode*acceptEdits*) echo '{"type":"control_response","response":{"subtype":"success","request_id":"'§id'","response":{"mode":"acceptEdits"}}}' ;;
    *set_permission_mode*) echo '{"type":"control_response","response":{"subtype":"error","request_id":"'§id'","error":"Cannot set permission mode to bypassPermissions"}}' ;;
    *rename_session*) echo '{"type":"control_response","response":{"subtype":"success","request_id":"'§id'"}}' ;;
  esac
done""")
        val content = JsonArray().apply { add(JsonObject().apply { addProperty("type", "text"); addProperty("text", "x") }) }
        val inited = CountDownLatch(1)
        val t = CliTurn(TurnOptions(cli, tmp.toString(), "", "auto", "manual", null, "", emptyList()), content, object : TurnListener by listener {
            override fun onInit(sessionId: String, model: String?) { inited.countDown() }
        })
        t.start()
        assertTrue(inited.await(10, TimeUnit.SECONDS))
        return t
    }

    @Test fun permissionModeIsAppliedOnTheLiveProcess() {
        val log = tmp.resolve("log.txt")
        val t = live(log)
        try {
            val r = t.setPermissionMode("acceptEdits")!!
            assertTrue(r.ok); assertEquals("acceptEdits", r.payload!!["mode"].asString)
            assertTrue("\"subtype\":\"set_permission_mode\",\"mode\":\"acceptEdits\"" in Files.readString(log), Files.readString(log))
        } finally { t.kill() }
    }

    @Test fun refusedModeCarriesTheCliErrorMessage() {
        val t = live(tmp.resolve("log.txt"))
        try {
            val r = t.setPermissionMode("bypassPermissions")!!
            assertFalse(r.ok)
            assertEquals("Cannot set permission mode to bypassPermissions", r.error)
        } finally { t.kill() }
    }

    @Test fun renameSessionSendsTheTitle() {
        val log = tmp.resolve("log.txt")
        val t = live(log)
        try {
            assertTrue(t.renameSession("Mon titre")!!.ok)
            assertTrue("\"subtype\":\"rename_session\",\"title\":\"Mon titre\"" in Files.readString(log))
        } finally { t.kill() }
    }

    @Test fun noProcessMeansNothingToTell() {
        val t = live(tmp.resolve("log.txt"))
        t.kill()
        assertNull(t.setPermissionMode("plan"), "processus terminé : null, pas une erreur")
        assertNull(t.renameSession("x"))
    }

    @Test fun unansweredRequestTimesOutWithNull() {
        val t = live(tmp.resolve("log.txt"))
        try { assertNull(t.request("unknown_subtype", timeoutMs = 600)) } finally { t.kill() }
    }

    @Test fun suggestionEventReachesTheListenerAfterTheResult() {
        val cli = fake("""read -r first
echo '{"type":"result","is_error":false}'
echo '{"type":"prompt_suggestion","suggestion":"  crée les fichiers directement \n et plus"}'
echo '{"type":"prompt_suggestion","suggestion":"   "}'
sleep 3""")
        val got = java.util.concurrent.LinkedBlockingQueue<String>()
        val resumed = java.util.concurrent.atomic.AtomicBoolean(false)
        val l = object : TurnListener {
            override fun onSuggestion(text: String) { got.add(text) }
            override fun onResumed() { resumed.set(true) }
        }
        val content = JsonArray().apply { add(JsonObject().apply { addProperty("type", "text"); addProperty("text", "x") }) }
        val t = CliTurn(TurnOptions(cli, tmp.toString(), "", "auto", "manual", null, "", emptyList(), suggestions = true), content, l)
        t.start()
        try {
            assertEquals("crée les fichiers directement", got.poll(10, TimeUnit.SECONDS))
            assertNull(got.poll(500, TimeUnit.MILLISECONDS), "suggestion vide ignorée")
            assertFalse(resumed.get(), "une suggestion n'est pas une reprise d'activité")
        } finally { t.kill() }
    }

    @Test fun suggestionsFlagIsPassedOnlyWhenEnabled() {
        for (enabled in listOf(true, false)) {
            val args = tmp.resolve("args-$enabled.txt")
            val cli = fake("""echo "§@" > $args
read -r first
echo '{"type":"result","is_error":false}'""")
            val done = CountDownLatch(1)
            val content = JsonArray().apply { add(JsonObject().apply { addProperty("type", "text"); addProperty("text", "x") }) }
            val t = CliTurn(TurnOptions(cli, tmp.toString(), "", "auto", "manual", null, "", emptyList(), suggestions = enabled), content,
                object : TurnListener { override fun onDone(isError: Boolean, message: String?) { done.countDown() } })
            t.start()
            assertTrue(done.await(10, TimeUnit.SECONDS)); t.kill()
            assertEquals(enabled, "--prompt-suggestions" in Files.readString(args), Files.readString(args))
        }
    }

    @Test fun cleanSuggestionKeepsOneBoundedLine() {
        assertEquals("a b", CliTurn.cleanSuggestion("\n  a b  \nsuite"))
        assertNull(CliTurn.cleanSuggestion("  \n "))
        assertNull(CliTurn.cleanSuggestion(null))
        assertEquals(300, CliTurn.cleanSuggestion("x".repeat(500))!!.length)
    }

    @Test fun titleChangedEventReachesTheListener() {
        val cli = fake("""read -r first
echo '{"type":"system","subtype":"session_title_changed","title":"Analyse du projet","session_id":"s1"}'
echo '{"type":"system","subtype":"session_title_changed","title":"  ","session_id":"s1"}'
echo '{"type":"system","subtype":"status","status":"requesting"}'
sleep 3""")
        val got = java.util.concurrent.LinkedBlockingQueue<String>()
        val content = JsonArray().apply { add(JsonObject().apply { addProperty("type", "text"); addProperty("text", "x") }) }
        val t = CliTurn(TurnOptions(cli, tmp.toString(), "", "auto", "manual", null, "", emptyList()), content,
            object : TurnListener { override fun onTitle(title: String) { got.add(title) } })
        t.start()
        try {
            assertEquals("Analyse du projet", got.poll(10, TimeUnit.SECONDS))
            assertNull(got.poll(500, TimeUnit.MILLISECONDS), "titre vide et autres événements système ignorés")
        } finally { t.kill() }
    }
}
