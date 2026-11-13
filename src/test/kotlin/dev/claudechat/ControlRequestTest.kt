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
}
