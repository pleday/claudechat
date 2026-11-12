package dev.claudechat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class McpServersTest {
    @TempDir lateinit var tmp: Path

    private val sample = """
Checking MCP server health…

claude.ai Claude Data: https://api.anthropic.com/api/dashboards/mcp - ✔ Connected
claude.ai Strava: https://mcp.strava.com/mcp - ! Needs authentication
idea: http://127.0.0.1:64342/stream (HTTP) - ✔ Connected
agentchrome: https://agentchrome.dev/mcp (HTTP) - ! Needs authentication
local-tool: npx -y my-mcp-server - ✗ Failed to connect
proj: https://x.dev/mcp (SSE) - ⏸ Pending approval
""".trimIndent()

    @Test fun parsesRealListOutput() {
        val l = McpServers.parseList(sample)
        assertEquals(6, l.size, "la ligne « Checking… » est ignorée")
        assertEquals(ListedServer("claude.ai Claude Data", "https://api.anthropic.com/api/dashboards/mcp", null, McpStatus.CONNECTED), l[0])
        assertEquals(McpStatus.NEEDS_AUTH, l[1].status)
        assertEquals(ListedServer("idea", "http://127.0.0.1:64342/stream", "http", McpStatus.CONNECTED), l[2])
        assertEquals("npx -y my-mcp-server", l[4].target)
        assertEquals(McpStatus.FAILED, l[4].status)
        assertEquals(McpStatus.PENDING, l[5].status)
    }

    @Test fun readsThreeScopesFromConfigFiles() {
        val cwd = "/work/proj"
        val claude = tmp.resolve("claude.json")
        Files.writeString(claude, """{"mcpServers":{"idea":{"type":"http","url":"http://x"},"sonar":{"type":"http","url":"https://s","headers":{"Authorization":"Bearer secret"}}},
            "projects":{"$cwd":{"mcpServers":{"mine":{"command":"npx","args":["-y","pkg"],"env":{"K":"v"}}}},"/autre":{"mcpServers":{"no":{"url":"u"}}}}}""")
        val proj = tmp.resolve(".mcp.json")
        Files.writeString(proj, """{"mcpServers":{"shared":{"url":"https://p"}}}""")
        val all = McpServers.readConfigs(cwd, claude, proj)
        assertEquals(listOf("idea" to McpScope.USER, "sonar" to McpScope.USER, "shared" to McpScope.PROJECT, "mine" to McpScope.LOCAL), all.map { it.name to it.scope })
        assertEquals("npx -y pkg", all.last().target); assertEquals("stdio", all.last().type)
        assertEquals("Bearer secret", all[1].headers["Authorization"])
        assertTrue(all.none { it.name == "no" }, "les autres projets sont ignorés")
        assertTrue(McpServers.readConfigs(cwd, tmp.resolve("absent.json"), tmp.resolve("absent2.json")).isEmpty())
    }

    @Test fun mergeAppliesStatusesAndSortsOutExtras() {
        val configs = listOf(McpServer("idea", McpScope.USER, "http://x", "http"), McpServer("ghost", McpScope.USER, "u", "http"))
        val merged = McpServers.merge(configs, McpServers.parseList(sample))
        assertEquals(McpStatus.CONNECTED, merged.first { it.name == "idea" }.status)
        assertEquals(McpStatus.FAILED, merged.first { it.name == "ghost" }.status, "configuré mais absent de la liste")
        assertEquals(McpScope.CLAUDE_AI, merged.first { it.name == "claude.ai Strava" }.scope)
        assertEquals(McpScope.OTHER, merged.first { it.name == "agentchrome" }.scope)
    }

    @Test fun masksSecrets() {
        assertEquals("Bearer ••••••", McpServers.mask("Bearer squ_abc123"))
        assertEquals("••••••", McpServers.mask("supersecret"))
        assertFalse("squ_" in McpServers.mask("Bearer squ_abc123"))
    }

    @Test fun normalizeAcceptsWrappedAndBareConfigurations() {
        val wrapped = McpServers.normalize("""{"mcpServers":{"a":{"command":"npx"},"b":{"url":"https://x"}}}""", "")
        assertNull(wrapped.error); assertEquals(listOf("a", "b"), wrapped.servers.map { it.first })
        val map = McpServers.normalize("""{"a":{"command":"npx"}}""", "")
        assertEquals(listOf("a"), map.servers.map { it.first })
        val single = McpServers.normalize("""{"type":"http","url":"https://x"}""", "mon-serveur")
        assertNull(single.error); assertEquals("mon-serveur", single.servers.single().first)
        assertTrue("\"url\"" in single.servers.single().second)
    }

    @Test fun normalizeExplainsProblems() {
        assertTrue(McpServers.normalize("{pas du json", "x").error!!.startsWith("JSON invalide"))
        assertTrue("objet" in McpServers.normalize("[1,2]", "x").error!!)
        assertTrue("nom" in McpServers.normalize("""{"command":"npx"}""", "  ").error!!.lowercase())
        assertTrue("invalide" in McpServers.normalize("""{"command":"npx"}""", "mon serveur").error!!)
        assertTrue("vide" in McpServers.normalize("""{"mcpServers":{}}""", "").error!!)
        assertTrue("n'a ni" in McpServers.normalize("""{"mcpServers":{"a":{"foo":1}}}""", "").error!!)
        assertTrue("Aucun serveur" in McpServers.normalize("""{"foo":1}""", "x").error!!)
    }

    private fun fakeCli(body: String): String {
        org.junit.jupiter.api.Assumptions.assumeTrue(!System.getProperty("os.name").lowercase().contains("win"))
        val f = tmp.resolve("claude"); Files.writeString(f, "#!/bin/bash\n$body\n"); f.toFile().setExecutable(true); return f.toString()
    }

    @Test fun addAndRemoveUseTheRightCliArguments() {
        val log = tmp.resolve("args.txt")
        val cli = fakeCli("""echo "${'$'}@" >> $log; exit 0""")
        assertTrue(McpServers.add(cli, tmp.toString(), "srv", """{"url":"https://x"}""", McpScope.PROJECT).first)
        assertTrue(McpServers.remove(cli, tmp.toString(), "srv", McpScope.USER).first)
        val lines = Files.readAllLines(log)
        assertEquals("""mcp add-json -s project srv {"url":"https://x"}""", lines[0])
        assertEquals("mcp remove srv -s user", lines[1])
    }

    @Test fun cliFailureIsReportedWithItsMessage() {
        val cli = fakeCli("""echo "MCP server srv already exists in user config"; exit 1""")
        val r = McpServers.add(cli, tmp.toString(), "srv", "{}", McpScope.USER)
        assertFalse(r.first); assertTrue("already exists" in r.second)
        assertFalse(McpServers.add("/nonexistent/claude", tmp.toString(), "a", "{}", McpScope.USER).first)
    }

    @Test fun listRunsMcpListInTheProjectFolder() {
        val cli = fakeCli("""echo "pwd=${'$'}(pwd)" >&2; echo "idea: http://x (HTTP) - ✔ Connected"""")
        val l = McpServers.list(cli, tmp.toString())
        assertEquals("idea", l.single().name)
    }
}
