package com.xatune.app

import com.xatune.app.model.*
import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.*

class ElectricUsageTest {
    private val today = LocalDate.parse("2026-11-13")
    @Test fun thirtyNaturalDaysUsePriorBaselineAndIncludeToday() {
        val records = (0L..30L).map { DailyBalance(today.minusDays(30 - it).toString(), (100 - it).toString()) }
        val points = electricUsage(records, today)
        assertEquals(30, points.size); assertEquals(today.minusDays(29), points.first().day); assertEquals(today, points.last().day)
        assertEquals("1.00", averageUsage(points).toString())
    }
    @Test fun missingDaysNeverInventConsumptionAndRechargeIsZero() {
        val points = electricUsage(listOf(DailyBalance("2026-11-08", "10"), DailyBalance("2026-11-10", "5"), DailyBalance("2026-11-11", "15"), DailyBalance("2026-11-12", "15"), DailyBalance("2026-11-13", "13.50")), today)
        assertEquals(3, points.size); assertTrue(points[0].recharge); assertFalse(points[1].recharge); assertFalse(points[2].recharge)
        assertEquals("0.50", averageUsage(points).toString())
        assertTrue(electricUsage(emptyList(), today).isEmpty()); assertTrue(electricUsage(listOf(DailyBalance(today.toString(), "1")), today).isEmpty())
    }
}
