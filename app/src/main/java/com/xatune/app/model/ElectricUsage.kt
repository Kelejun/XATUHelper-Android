package com.xatune.app.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class ElectricUsage(val day: LocalDate, val amount: BigDecimal?, val recharge: Boolean, val estimated: Boolean = false, val intervalDays: Long = 1)
fun electricUsage(records: List<DailyBalance>, today: LocalDate = schoolToday()): List<ElectricUsage> {
    val start = today.minusDays(29)
    val balances = records.mapNotNull { record ->
        runCatching { LocalDate.parse(record.day) }.getOrNull()?.takeIf { it <= today }?.let { day ->
            record.balance.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }?.let { day to it }
        }
    }.toMap().toSortedMap().entries.toList()
    val points = balances.zipWithNext().flatMap { (previous, current) ->
        val days = ChronoUnit.DAYS.between(previous.key, current.key)
        val recharge = current.value > previous.value
        val amount = if (recharge) null else (previous.value - current.value).divide(days.toBigDecimal(), 12, RoundingMode.HALF_UP)
        val first = maxOf(previous.key.plusDays(1), start)
        if (first > current.key) emptyList() else (0L..ChronoUnit.DAYS.between(first, current.key)).map { offset ->
            val day = first.plusDays(offset)
            ElectricUsage(day, amount, recharge && day == current.key, estimated = recharge, intervalDays = days)
        }
    }
    val average = averageUsage(points)
    return points.map { if (it.estimated) it.copy(amount = average) else it }
}
fun averageUsage(points: List<ElectricUsage>): BigDecimal? = points.filter { !it.estimated && it.amount != null }.takeIf { it.isNotEmpty() }?.let {
    it.sumOf { point -> point.amount!! }.divide(it.size.toBigDecimal(), 2, RoundingMode.HALF_UP)
}
