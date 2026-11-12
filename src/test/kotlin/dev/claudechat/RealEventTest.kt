package dev.claudechat

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RealEventTest {
    @Test fun parsesRealCliEvent() {
        val line = """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed","resetsAt":1794489000,"rateLimitType":"five_hour","overageStatus":"rejected","overageDisabledReason":"out_of_credits","isUsingOverage":false,"unifiedWindows":{"five_hour":{"utilization":0.35,"resetsAt":1794489000,"status":"allowed"},"seven_day":{"utilization":0.35,"resetsAt":1794632400,"status":"allowed"}}},"uuid":"x","session_id":"y"}"""
        val r = parseRateLimits(JsonParser.parseString(line).asJsonObject)
        assertNotNull(r)
        assertEquals(0.35, r!!.fiveHour!!.utilization, 1e-9)
        assertEquals(35, Quota.percent(r.fiveHour, 1794000000))
        assertEquals(65, Quota.remainingPercent(r.fiveHour, 1794000000))
    }
}
