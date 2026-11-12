package dev.claudechat

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TextToolsTest {
    private val files = listOf("src/main/kotlin/dev/claudechat/ChatPanel.kt", "src/main/kotlin/dev/claudechat/Sessions.kt", "README.md", "build.gradle.kts")

    @Test fun fuzzyRejectsNonSubsequence() = assertNull(Fuzzy.score("zzz", "README.md"))

    @Test fun fuzzyPrefersFilenameMatch() {
        assertEquals("src/main/kotlin/dev/claudechat/ChatPanel.kt", Fuzzy.search("chatpanel", files).first())
    }

    @Test fun fuzzyMatchesInitials() = assertTrue("src/main/kotlin/dev/claudechat/Sessions.kt" in Fuzzy.search("sess", files))

    @Test fun fuzzyEmptyQueryReturnsAllLimited() = assertEquals(2, Fuzzy.search("", files, 2).size)

    @Test fun mentionsFindsPathsOnce() =
        assertEquals(listOf("src/A.kt", "README.md"), Mentions.find("regarde @src/A.kt et @README.md puis @src/A.kt"))

    @Test fun mentionsIgnoresEmails() = assertTrue(Mentions.find("écris à jean@exemple.com").isEmpty())

    @Test fun activeTokenBeforeCaret() {
        assertEquals(5 to "src/A", Mentions.activeToken("voir @src/A", 11))
        assertNull(Mentions.activeToken("voir @src/A bien", 16))
    }

    @Test fun highlightLeavesTagsIntact() {
        val out = Highlight.apply("<p class=\"x\">un X et x</p>", "x")
        assertTrue(out.startsWith("<p class=\"x\">"))
        assertEquals(2, Regex("<span").findAll(out).count())
    }

    @Test fun highlightCountIsCaseInsensitive() = assertEquals(3, Highlight.count(listOf("Abc", "abc ABC"), "abc"))

    @Test fun toolSummaryPerTool() {
        fun o(s: String) = JsonParser.parseString(s).asJsonObject
        assertEquals("ls -la", toolSummary("Bash", o("""{"command":"ls -la"}""")))
        assertEquals("/a/b.kt", toolSummary("Edit", o("""{"file_path":"/a/b.kt","old_string":"x"}""")))
        assertEquals("TODO in src", toolSummary("Grep", o("""{"pattern":"TODO","path":"src"}""")))
        assertEquals("""{"k":1}""", toolSummary("Inconnu", o("""{"k":1}""")))
    }
}

class ModelAndQuotaTest {
    @Test fun aliasResolvesToVersionedId() = assertEquals("claude-sonnet-5-5", ModelCatalog.resolve("Sonnet"))

    @Test fun labelsShowVersionNumbers() {
        assertEquals("Opus 5.5", ModelCatalog.label("claude-opus-5-5"))
        assertEquals("Haiku 4.1", ModelCatalog.label("claude-haiku-4-1-20250514"))
        assertEquals("Sonnet 6", ModelCatalog.label("claude-sonnet-6"))
        assertEquals("modele-perso", ModelCatalog.label("modele-perso"))
        assertEquals("Fable 5.2  (claude-fable-5-2)", ModelCatalog.display("claude-fable-5-2"))
    }

    @Test fun parsesRateLimitEventFromCli() {
        val j = com.google.gson.JsonParser.parseString("""{"type":"rate_limit_event","rate_limit_info":{"status":"allowed","unifiedWindows":{"five_hour":{"utilization":0.05,"resetsAt":1794489000,"status":"allowed"},"seven_day":{"utilization":0.3,"resetsAt":1794632400}}}}""").asJsonObject
        val r = parseRateLimits(j)!!
        assertEquals(0.05, r.fiveHour!!.utilization, 1e-9)
        assertEquals(1794632400L, r.sevenDay!!.resetsAt)
        assertNull(parseRateLimits(com.google.gson.JsonParser.parseString("""{"rate_limit_info":{}}""").asJsonObject))
    }

    @Test fun quotaPercentAndExpiry() {
        val w = QuotaWindow(0.537, 1000)
        assertEquals(53, Quota.percent(w, 500))
        assertEquals(0, Quota.percent(w, 2000), "fenêtre expirée = 0 %")
        assertNull(Quota.percent(null, 0))
        assertEquals(100, Quota.percent(QuotaWindow(1.4, 0), 5))
    }

    @Test fun remainingFormats() {
        assertEquals("2 h 10", Quota.remaining(7800, 0))
        assertEquals("3 j 4 h", Quota.remaining(3 * 86400 + 4 * 3600 + 5, 0))
        assertEquals("45 min", Quota.remaining(2700, 0))
    }
}

class BatteryTest {
    @Test fun levelsFollowThresholds() {
        assertEquals(BatteryLevel.GREEN, batteryLevel(100))
        assertEquals(BatteryLevel.GREEN, batteryLevel(50))
        assertEquals(BatteryLevel.ORANGE, batteryLevel(49))
        assertEquals(BatteryLevel.ORANGE, batteryLevel(20))
        assertEquals(BatteryLevel.RED, batteryLevel(19))
        assertEquals(BatteryLevel.RED, batteryLevel(0))
        assertEquals(BatteryLevel.UNKNOWN, batteryLevel(null))
    }

    @Test fun remainingIsComplementOfUsed() {
        assertEquals(63, Quota.remainingPercent(QuotaWindow(0.37, 1000), 10))
        assertEquals(100, Quota.remainingPercent(QuotaWindow(0.9, 5), 10), "fenêtre expirée = pleine")
        assertNull(Quota.remainingPercent(null, 0))
    }
}

class QuotaGuardTest {
    private val now = 1_000L
    private fun limits(status: String?, type: String? = null, resets: Long? = null, five: QuotaWindow? = null, week: QuotaWindow? = null) =
        RateLimits(five, week, status, type, resets)

    @Test fun rejectedUsesResetPlusMargin() =
        assertEquals(5_000L + 120, QuotaGuard.blockedUntil(limits("rejected", "five_hour", 5_000), "x", now))

    @Test fun rejectedFallsBackToWindowOfType() =
        assertEquals(7_000L + 120, QuotaGuard.blockedUntil(limits("rejected", "seven_day", null, week = QuotaWindow(1.0, 7_000)), null, now))

    @Test fun messageOnlyWithFullWindowTakesLatestFullReset() {
        val l = limits("allowed", five = QuotaWindow(1.0, 4_000), week = QuotaWindow(1.0, 9_000))
        assertEquals(9_000L + 120, QuotaGuard.blockedUntil(l, "5-hour limit reached", now))
    }

    @Test fun quotaMessageWithoutDataRetriesLater() =
        assertEquals(now + 1800, QuotaGuard.blockedUntil(null, "Usage limit reached", now))

    @Test fun delayIsConfigurable() =
        assertEquals(5_000L + 600, QuotaGuard.blockedUntil(limits("rejected", "five_hour", 5_000), "x", now, delaySec = 600))

    @Test fun unrelatedErrorIsNotAQuota() {
        assertNull(QuotaGuard.blockedUntil(limits("allowed"), "Erreur réseau", now))
        assertNull(QuotaGuard.blockedUntil(null, null, now))
    }

    @Test fun pastResetFallsBackInsteadOfSpinning() =
        assertEquals(now + 1800, QuotaGuard.blockedUntil(limits("rejected", "five_hour", 500), "x", now))

    @Test fun parsesRejectedStatusEvenWithoutWindows() {
        val j = com.google.gson.JsonParser.parseString("""{"rate_limit_info":{"status":"rejected","rateLimitType":"five_hour","resetsAt":123}}""").asJsonObject
        val r = parseRateLimits(j)!!
        assertEquals("rejected", r.status); assertEquals("five_hour", r.limitType); assertEquals(123L, r.resetsAt)
    }
}

class ChunkTextTest {
    @Test fun shortTextIsOneChunk() = assertEquals(listOf("abc"), chunkText("abc", 10))

    @Test fun splitsOnNewlinesAndKeepsEverything() {
        val text = (1..50).joinToString("\n") { "ligne $it" }
        val parts = chunkText(text, 100)
        assertTrue(parts.size > 1 && parts.all { it.length <= 100 })
        assertEquals(text.filter { !it.isWhitespace() }, parts.joinToString("").filter { !it.isWhitespace() })
    }

    @Test fun hardCutsWhenNoSeparator() = assertEquals(listOf("aaaa", "aaaa", "aa"), chunkText("a".repeat(10), 4))
}
