package dev.claudechat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SessionLinkTest {
    private val id = "65f47066-1c3d-445a-b2b1-8106dd96a66f"

    @Test fun readsTheSessionIdFromTheTarget() {
        assertEquals(SessionLink(id, null), SessionLinks.parse(id, emptyMap()))
    }

    @Test fun acceptsTheSessionPrefixAndSlashes() {
        assertEquals(SessionLink(id, null), SessionLinks.parse("session/$id/", emptyMap()))
    }

    @Test fun readsTheFolder() {
        assertEquals(SessionLink(id, "/home/me/p"), SessionLinks.parse(id, mapOf("cwd" to " /home/me/p ")))
    }

    @Test fun ignoresAnEmptyFolder() {
        assertEquals(SessionLink(id, null), SessionLinks.parse(id, mapOf("cwd" to " ")))
    }

    @Test fun refusesWhatIsNotASessionId() {
        assertNull(SessionLinks.parse(null, emptyMap()))
        assertNull(SessionLinks.parse("", emptyMap()))
        assertNull(SessionLinks.parse("../../etc/passwd", emptyMap()))
        assertNull(SessionLinks.parse("abc", emptyMap()))
    }
}
