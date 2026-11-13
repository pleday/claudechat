package dev.claudechat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InputHistoryTest {
    @Test fun walksBackAndForwardKeepingTheDraft() {
        val h = InputHistory()
        h.add("un"); h.add("deux"); h.add("trois")
        assertEquals("trois", h.previous("brouillon"))
        assertEquals("deux", h.previous("trois"))
        assertEquals("un", h.previous("deux"))
        assertNull(h.previous("un"), "début de l'historique")
        assertEquals("deux", h.next("un"))
        assertEquals("trois", h.next("deux"))
        assertEquals("brouillon", h.next("trois"))
        assertNull(h.next("brouillon"), "déjà hors de l'historique")
    }

    @Test fun ignoresBlankAndConsecutiveDuplicates() {
        val h = InputHistory()
        h.add("  "); h.add("a"); h.add("a "); h.add("b")
        assertEquals("b", h.previous(""))
        assertEquals("a", h.previous("b"))
        assertNull(h.previous("a"))
    }

    @Test fun emptyHistoryReturnsNull() {
        val h = InputHistory()
        assertNull(h.previous("x")); assertNull(h.next("x"))
    }

    @Test fun editingARecalledMessageMakesItTheNewDraft() {
        val h = InputHistory()
        h.add("un"); h.add("deux")
        assertEquals("deux", h.previous(""))
        // texte modifié : mis de côté comme brouillon, ↑ repart du dernier message
        assertEquals("deux", h.previous("deux modifié"))
        assertEquals("deux modifié", h.next("deux"), "le texte modifié est restitué comme brouillon")
    }

    @Test fun sendingResetsPosition() {
        val h = InputHistory()
        h.add("un"); assertEquals("un", h.previous("")); h.add("deux")
        assertEquals("deux", h.previous(""))
    }

    @Test fun historyIsBounded() {
        val h = InputHistory(max = 3)
        (1..5).forEach { h.add("m$it") }
        assertEquals("m5", h.previous("")); assertEquals("m4", h.previous("m5")); assertEquals("m3", h.previous("m4"))
        assertNull(h.previous("m3"))
    }
}
