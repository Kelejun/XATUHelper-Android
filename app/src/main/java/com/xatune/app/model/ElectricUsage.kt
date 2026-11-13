package com.xatune.app.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

data class ElectricUsage(val day: LocalDate, val amount: BigDecimal, val recharge: Boolean)
fun electricUsage(records: List<DailyBalance>, today: LocalDate = schoolToday()): List<ElectricUsage> {
    val balances = records.mapNotNull { record -> runCatching { LocalDate.parse(record.day) }.getOrNull()?.let { day -> record.balance.toBigDecimalOrNull()?.let { day to it } } }.toMap()
    return (0L..29L).mapNotNull { offset ->
        val day = today.minusDays(29 - offset)
        val current = balances[day] ?: return@mapNotNull null
        val previous = balances[day.minusDays(1)] ?: return@mapNotNull null
        ElectricUsage(day, (previous - current).max(BigDecimal.ZERO), current > previous)
    }
}
fun averageUsage(points: List<ElectricUsage>): BigDecimal? = points.takeIf { it.isNotEmpty() }?.let { it.sumOf(ElectricUsage::amount).divide(it.size.toBigDecimal(), 2, RoundingMode.HALF_UP) }
