package dev.claudechat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RenderCacheTest {
    private val cache = RenderCache()

    private fun md(owner: Any, text: String, codes: MutableList<String>?) =
        cache.render(owner, text, codes) { c -> Md.toHtml(text, c) }

    @Test fun unchangedKeyIsNotRecomputed() {
        var n = 0
        val e = Any()
        repeat(3) { cache.render(e, "k") { n++; "<p>x</p>" } }
        assertEquals(1, n)
        cache.render(e, "k2") { n++; "<p>y</p>" }
        assertEquals(2, n)
    }

    @Test fun codeIdsAreRenumberedAndMatchUncachedRendering() {
        val a = Any(); val b = Any()
        val ta = "avant\n\n```kt\nval a = 1\n```\n\n> citation"
        val tb = "```\nsecond\n```"
        // premier rendu : remplit le cache
        md(a, ta, mutableListOf()); md(b, tb, mutableListOf())
        // second rendu : b arrive après a, ses ids doivent être décalés ; résultat identique à un rendu direct
        val cached = mutableListOf<String>()
        val h = md(a, ta, cached) + md(b, tb, cached)
        val direct = mutableListOf<String>()
        val expected = Md.toHtml(ta, direct) + Md.toHtml(tb, direct)
        assertEquals(expected, h)
        assertEquals(direct, cached)
        assertTrue("id='code2'" in h)
    }

    @Test fun literalTextLookingLikeAnIdIsNotRenumbered() {
        val a = Any(); val b = Any()
        md(a, "```\nx\n```", mutableListOf())
        val t = "écrit id='code0' dans le texte\n\n```\ny\n```"
        md(b, t, mutableListOf())
        val codes = mutableListOf<String>()
        md(a, "```\nx\n```", codes)
        val h = md(b, t, codes)
        assertTrue("écrit id='code0' dans" in h)
        assertTrue("id='code1'" in h)
    }

    @Test fun retainOnlyDropsRemovedEntries() {
        val a = Any(); val b = Any()
        cache.render(a, 1) { "a" }; cache.render(b, 1) { "b" }
        cache.retainOnly(listOf(a))
        assertEquals(1, cache.size)
    }
}
