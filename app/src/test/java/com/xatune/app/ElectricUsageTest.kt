package com.xatune.app

import com.xatune.app.model.*
import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.*

class ElectricUsageTest {
    private val today = LocalDate.parse("2026-11-13")
    private fun usage(vararg rows: Pair<String, String>) = electricUsage(rows.map { DailyBalance(it.first, it.second) }, today)
    @Test fun thirtyNaturalDaysUsePriorBaselineAndIncludeToday() {
        val points = electricUsage((0L..30L).map { DailyBalance(today.minusDays(30 - it).toString(), (100 - it).toString()) }, today)
        assertEquals(30, points.size); assertEquals(today.minusDays(29), points.first().day); assertEquals(today, points.last().day)
        assertEquals("1.00", averageUsage(points).toString())
    }
    @Test fun gapsAreSpreadAndRechargeUsesNonRechargeAverageIncludingZero() {
        val points = usage("2026-11-08" to "10", "2026-11-10" to "5", "2026-11-11" to "15", "2026-11-12" to "15", "2026-11-13" to "13.50")
        assertEquals(5, points.size)
        assertEquals("2.500000000000", points[0].amount.toString()); assertEquals(2L, points[0].intervalDays)
        assertTrue(points[2].recharge); assertTrue(points[2].estimated); assertEquals("1.63", points[2].amount.toString())
        assertFalse(points[3].recharge); assertEquals(0, points[3].amount!!.signum()); assertEquals("1.63", averageUsage(points).toString())
    }
    @Test fun clippedIntervalsUseOutsideBaselineButAverageOnlyVisibleDays() {
        val points = usage("2026-10-10" to "100", "2026-10-20" to "80", "2026-11-13" to "128")
        assertEquals(30, points.size); assertEquals("2.00", averageUsage(points).toString())
        assertEquals(24, points.count { it.estimated }); assertEquals(1, points.count { it.recharge })
        assertTrue(points.last().recharge); assertEquals("2.00", points.last().amount.toString())
    }
    @Test fun multipleRechargesShareAverageAndNoBaselineDoesNotInventZero() {
        val points = usage("2026-11-07" to "10", "2026-11-09" to "20", "2026-11-10" to "18", "2026-11-13" to "30")
        assertEquals(2, points.count { it.recharge }); assertTrue(points.filter { it.estimated }.all { it.amount.toString() == "2.00" })
        val unknown = usage("2026-11-10" to "10", "2026-11-13" to "20")
        assertEquals(3, unknown.size); assertTrue(unknown.all { it.amount == null }); assertTrue(unknown.last().recharge); assertNull(averageUsage(unknown))
        assertTrue(usage().isEmpty()); assertTrue(usage("2026-11-13" to "1").isEmpty())
    }
    @Test fun invalidFutureAndOldRecordsCannotBiasAverage() {
        val points = usage("invalid" to "1", "2026-09-01" to "999", "2026-10-10" to "10", "2026-11-13" to "10", "2026-11-14" to "0")
        assertEquals(30, points.size); assertEquals("0.00", averageUsage(points).toString()); assertFalse(points.any { it.estimated })
    }
    @Test fun overdrawnBalancesRemainValidConsumptionAndRechargeReferences() {
        val points = usage("2026-11-10" to "1", "2026-11-11" to "-1", "2026-11-12" to "-3", "2026-11-13" to "7")
        assertEquals(3, points.size)
        assertEquals("2.00", averageUsage(points).toString())
        assertTrue(points.last().recharge); assertTrue(points.last().estimated)
        assertEquals("2.00", points.last().amount.toString())
    }

}
