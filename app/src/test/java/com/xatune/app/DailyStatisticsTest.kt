package com.xatune.app

import com.xatune.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DailyStatisticsTest {
    private class Store : StatisticsStore {
        override var enabled = true
        override var sent = ""
        override fun secret() = ByteArray(32) { it.toByte() }
    }
    @Test fun tokensRotateByShanghaiDayAndSecret() {
        val secret = ByteArray(32)
        assertEquals(dailyStatisticsToken(secret, "2026-11-16"), dailyStatisticsToken(secret, "2026-11-16"))
        assertNotEquals(dailyStatisticsToken(secret, "2026-11-16"), dailyStatisticsToken(secret, "2026-11-17"))
        assertNotEquals(dailyStatisticsToken(secret, "2026-11-16"), dailyStatisticsToken(ByteArray(32) { 1 }, "2026-11-16"))
        assertTrue(dailyStatisticsToken(secret, "2026-11-16").matches(Regex("[0-9a-f]{64}")))
        assertEquals("2026-11-17", statisticsDay(Instant.parse("2026-11-16T16:00:00Z").toEpochMilli()))
    }
    @Test fun dedupUpgradeNewDayOptOutAndTestBuild() = runBlocking {
        val store = Store()
        var now = Instant.parse("2026-11-16T10:00:00Z").toEpochMilli()
        val calls = mutableListOf<Triple<String, String, String>>()
        fun reporter(commit: String, eligible: Boolean = true) = DailyStatisticsReporter(store, commit, eligible, { now }) { day, token, build ->
            calls += Triple(day, token, build)
            StatisticsReply(200)
        }
        val first = reporter("1111111")
        first.tick(); first.tick()
        assertEquals(1, calls.size)
        reporter("2222222").tick()
        assertEquals(calls[0].second, calls[1].second)
        now += 86_400_000
        first.tick()
        assertNotEquals(calls[0].second, calls[2].second)
        store.enabled = false
        reporter("3333333").tick()
        store.enabled = true
        reporter("3333333", false).tick()
        assertEquals(3, calls.size)
    }
    @Test fun retriesAreBoundedAndClockCorrectionRecomputesToken() = runBlocking {
        val store = Store()
        var now = Instant.parse("2026-11-16T10:00:00Z").toEpochMilli()
        var calls = 0
        val failed = DailyStatisticsReporter(store, "1111111", true, { now }) { _, _, _ ->
            calls++; throw java.io.IOException()
        }
        failed.tick(); failed.tick()
        assertEquals(1, calls)
        repeat(5) { now += 120_000; failed.tick() }
        assertEquals(3, calls)
        assertEquals("", store.sent)
        val days = mutableListOf<String>()
        val corrected = DailyStatisticsReporter(store, "2222222", true, { now }) { day, _, _ ->
            days += day
            if (days.size == 1) StatisticsReply(409, now + 86_400_000) else StatisticsReply(200)
        }
        corrected.tick(); corrected.tick()
        assertEquals(listOf("2026-11-16", "2026-11-17"), days)
        assertEquals("2026-11-17:2222222", store.sent)
    }
}
