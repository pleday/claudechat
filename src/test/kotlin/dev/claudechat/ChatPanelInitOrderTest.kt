package dev.claudechat

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * ChatPanel ne peut pas être instancié sans IDE, mais son bloc `init` appelle render() : tout champ que le rendu
 * utilise doit être déclaré AVANT ce bloc, sinon il vaut encore null et le panneau plante à l'ouverture
 * (« Nothing to show »). Ce test garde cet ordre.
 */
class ChatPanelInitOrderTest {
    @Test fun fieldsUsedByInitAreDeclaredBeforeTheFirstInitBlock() {
        val src = Files.readString(Path.of("src/main/kotlin/dev/claudechat/ChatPanel.kt"))
        val firstInit = src.indexOf("\n    init {")
        assertTrue(firstInit > 0)
        for (field in listOf("renderer", "lastHtml", "codeTexts", "session", "searchQuery", "showThinking", "showTools", "stickyChoice", "stickyOpen", "stickyLabel", "stickyText", "stickyScroll", "stickyBar")) {
            val decl = Regex("""\n    private (?:val|var) $field\b""").find(src)?.range?.first ?: -1
            assertTrue(decl in 1 until firstInit, "« $field » doit être déclaré avant le premier bloc init (position $decl, init à $firstInit)")
        }
    }
}
