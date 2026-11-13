package dev.claudechat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Container
import java.awt.image.BufferedImage

class McpListPanelTest {
    private val servers = listOf(
        McpServer("agentchrome", McpScope.USER, "https://a", "http", McpStatus.NEEDS_AUTH),
        McpServer("idea", McpScope.USER, "http://127.0.0.1:1", "http", McpStatus.CONNECTED, headers = mapOf("Authorization" to "Bearer squ_SECRET")),
        McpServer("mine", McpScope.LOCAL, "npx -y pkg", "stdio", McpStatus.FAILED),
        McpServer("claude.ai Asana", McpScope.CLAUDE_AI, "https://asana", null, McpStatus.NEEDS_AUTH),
    )

    private fun panel(onRemove: (McpServer) -> Unit = {}) = McpListPanel(onRemove) {}.also { it.setServers(servers) }

    private fun layoutAll(c: Container) { c.doLayout(); c.components.forEach { (it as? Container)?.let(::layoutAll) } }

    @Test fun groupsByScopeInOrderWithCounts() {
        val p = panel()
        assertEquals(listOf("Utilisateur (2)", "Local (1)", "claude.ai (1)"), p.sectionTitles)
        assertEquals(4, p.rowCount)
    }

    @Test fun filterMatchesNameOrTarget() {
        val p = panel()
        p.setFilter("ASANA"); assertEquals(1, p.rowCount)
        p.setFilter("npx"); assertEquals(listOf("Local (1)"), p.sectionTitles)
        p.setFilter("zzz"); assertEquals(0, p.rowCount)
        p.setFilter(""); assertEquals(4, p.rowCount)
    }

    @Test fun badgesFollowStatusAndAreFrench() {
        assertEquals("✓ Connecté", badgeStyle(McpStatus.CONNECTED).text)
        assertTrue("Authentification" in badgeStyle(McpStatus.NEEDS_AUTH).text)
        assertEquals(McpStatus.values().size, McpStatus.values().map { badgeStyle(it).text }.toSet().size, "un texte par statut")
    }

    @Test fun badgeHasSizeAndPaints() {
        val b = Badge(badgeStyle(McpStatus.CONNECTED))
        assertTrue(b.preferredSize.width > 40 && b.preferredSize.height > 10)
        b.setSize(b.preferredSize)
        val img = BufferedImage(b.width, b.height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics(); b.paint(g); g.dispose()
        assertTrue(img.getRGB(b.width / 2, 2) ushr 24 == 255)
    }

    @Test fun detailsNeverShowSecrets() {
        val html = McpServers.detailsHtml(servers[1])
        assertTrue("Authorization" in html && "Bearer ••••••" in html)
        assertFalse("squ_SECRET" in html)
        assertTrue("&lt;" in McpServers.detailsHtml(McpServer("x", McpScope.USER, "a<b", "stdio")))
    }

    @Test fun expandingACardKeepsTheListLayoutValid() {
        val p = panel()
        p.toggle(servers[0])
        p.setSize(520, 900); repeat(2) { layoutAll(p) }
        assertTrue(p.preferredSize.height > 100)
        assertEquals(4, p.rowCount)
        p.toggle(servers[0])
        assertEquals(4, p.rowCount)
    }
}
