package dev.claudechat

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color

class TranscriptRendererTest {
    private val theme = TranscriptRenderer.Theme("Sans", 13, "Mono", Color.BLACK, Color.BLUE, Color(0xE8F0FE), Color(0xF3F3F3), Color.BLUE, Color.ORANGE)
    private val opts = TranscriptRenderer.Options(showThinking = false, showTools = true, activeModel = "claude-sonnet-5-5", streaming = false)
    private val r = TranscriptRenderer { "/p" }

    private fun tool(name: String, input: JsonObject, result: String? = null) = Entry(Kind.TOOL, toolId = "t", toolName = name, input = input, result = result)
    private fun json(k: String, v: String) = JsonObject().apply { addProperty(k, v) }

    @Test fun rendersUserAndAssistantInOneResponseBlock() {
        val out = r.build(listOf(Entry(Kind.USER, "Salut"), Entry(Kind.TEXT, "Bonjour"), tool("Read", json("file_path", "/p/A.kt"), "ok")), opts, theme).html
        assertTrue("Salut" in out && "Bonjour" in out)
        assertEquals(1, Regex("id='resp\\d+'").findAll(out).count(), "texte et outil dans la même réponse")
        assertTrue("open:/p/A.kt" in out && ">A.kt</a>" in out, "chemin relatif au projet")
    }

    @Test fun toolsAndThinkingFollowOptions() {
        val entries = listOf(Entry(Kind.USER, "q"), Entry(Kind.THINKING, "hmm"), tool("Bash", json("command", "ls"), "a\nb"))
        val hidden = r.build(entries, opts, theme).html
        assertFalse("hmm" in hidden); assertTrue("Bash" in hidden)
        val shown = r.build(entries, TranscriptRenderer.Options(true, false, null, false), theme).html
        assertTrue("hmm" in shown); assertFalse("Bash" in shown)
    }

    @Test fun codeTextsAreIndexedLikeTheHtmlIds() {
        val entries = listOf(Entry(Kind.TEXT, "```\none\n```"), Entry(Kind.TEXT, "```\ntwo\n```"))
        repeat(2) {   // 2e passage : entrées servies par le cache, mêmes ids
            val out = r.build(entries, opts, theme)
            assertEquals(listOf("one", "two"), out.codeTexts)
            assertTrue("id='code0'" in out.html && "id='code1'" in out.html)
        }
    }

    @Test fun streamingShowsPlaceholderOnlyAfterAUserMessage() {
        val s = TranscriptRenderer.Options(false, true, null, true)
        assertTrue("<p>…</p>" in r.build(listOf(Entry(Kind.USER, "q")), s, theme).html)
        assertFalse("<p>…</p>" in r.build(listOf(Entry(Kind.USER, "q"), Entry(Kind.TEXT, "r")), s, theme).html)
    }

    @Test fun errorsAndNoticesAreEscapedAndOutsideResponses() {
        val out = r.build(listOf(Entry(Kind.ERROR, "<boom>"), Entry(Kind.NOTICE, "info")), opts, theme).html
        assertTrue("&lt;boom&gt;" in out)
        assertFalse("id='resp" in out)
    }

    @Test fun updatedEntryIsRerendered() {
        val e = Entry(Kind.TEXT, "début")
        assertTrue("début" in r.build(listOf(e), opts, theme).html)
        e.text += " et suite"
        assertTrue("début et suite" in r.build(listOf(e), opts, theme).html)
    }
}
