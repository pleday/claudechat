package dev.claudechat

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class LinkifyTest {
    private var clock = 0L
    private var calls = 0
    private val present = mutableSetOf("/p/src/A.kt", "/p/img/logo.png")
    private val l = Linkify({ "/p" }, ttlMs = 1000, now = { clock }, exists = { calls++; it in present })

    @Test fun inlineCodePathBecomesLinkWithLine() {
        assertEquals("<a href=\"open:src/A.kt:12\"><code>src/A.kt:12</code></a>", l.files("<code>src/A.kt:12</code>"))
    }

    @Test fun missingFileIsLeftAlone() {
        assertEquals("<code>nope.kt</code>", l.files("<code>nope.kt</code>"))
    }

    @Test fun plainImagePathBecomesLink() {
        assertEquals("<p>voir <a href=\"open:img/logo.png\">img/logo.png</a>.</p>", l.images("<p>voir img/logo.png.</p>"))
    }

    @Test fun imagesInsideCodeBlocksAreIgnoredButNotInToolCards() {
        val code = "<table><tr><td class='cb'><code>load(\"img/logo.png\")</code></td></tr></table>"
        assertEquals(code, l.images(code))
        assertTrue("open:img/logo.png" in l.images("<code>img/logo.png</code>", inCode = true))
    }

    @Test fun existingLinksAreNotNested() {
        val html = "<a href=\"x\">img/logo.png</a>"
        assertEquals(html, l.images(html))
    }

    @Test fun existenceIsCachedUntilTtlOrInvalidation() {
        repeat(5) { l.images("img/logo.png") }
        assertEquals(1, calls)
        clock += 1500
        l.images("img/logo.png")
        assertEquals(2, calls)
        l.invalidate()
        l.images("img/logo.png")
        assertEquals(3, calls)
    }

    @Test fun cacheSeesNewFileAfterTtl() {
        assertEquals("<code>new.kt</code>", l.files("<code>new.kt</code>"))
        present += "/p/new.kt"; clock += 1500
        assertTrue("open:new.kt" in l.files("<code>new.kt</code>"))
    }
}
