package com.xatune.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.xatune.app.model.*
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToLong

@Composable
internal fun ElectricUsageChart(points: List<ElectricUsage>, today: LocalDate = schoolToday()) {
    var selected by remember(points) { mutableStateOf<ElectricUsage?>(null) }
    if (points.isEmpty()) { InfoPanel("最近 30 天暂无用量记录"); return }
    val color = MaterialTheme.colorScheme.primary
    val estimateColor = MaterialTheme.colorScheme.tertiary
    Column {
    val config = LocalViewConfiguration.current
    val longPressConfig = remember(config) { object : ViewConfiguration by config { override val longPressTimeoutMillis = 250L } }
    Box(Modifier.fillMaxWidth().height(112.dp)) { selected?.let { point ->
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(12.dp).testTag("electricUsageReading")) {
                Text("${point.day} · ${point.amount?.setScale(2, RoundingMode.HALF_UP)?.let { "$it 度" } ?: "暂无用量估算"}")
                if (point.estimated) Text("平均用量估算", style = MaterialTheme.typography.labelSmall)
                else if (point.intervalDays > 1) Text("${point.intervalDays} 天均摊用量", style = MaterialTheme.typography.labelSmall)
                if (point.recharge) SuggestionChip(onClick = {}, label = { Text("有电费充值") })
            }
        }
    }
    }
    CompositionLocalProvider(LocalViewConfiguration provides longPressConfig) {
        Canvas(Modifier.fillMaxWidth().height(160.dp).testTag("electricUsageChart")
            .semantics { contentDescription = selected?.let { "${it.day} ${it.amount?.setScale(2, RoundingMode.HALF_UP)?.let { value -> "$value 度" } ?: "暂无用量估算"}${if (it.estimated) " 平均用量估算" else if (it.intervalDays > 1) " ${it.intervalDays} 天均摊用量" else ""}${if (it.recharge) " 有电费充值" else ""}" } ?: "最近 30 天每日用电图" }
            .pointerInput(points, today) {
                var x = 0f
                fun select() { val day = today.minusDays(29).plusDays((((x - 8.dp.toPx()) / (size.width - 16.dp.toPx()).coerceAtLeast(1f)).coerceIn(0f, 1f) * 29).roundToLong()); selected = points.minByOrNull { abs(ChronoUnit.DAYS.between(day, it.day)) } }
                detectDragGesturesAfterLongPress(onDragStart = { x = it.x; select() }, onDragEnd = { selected = null }, onDragCancel = { selected = null }) { change, drag -> change.consume(); x += drag.x; select() }
            }) {
            val max = (points.mapNotNull { it.amount?.toFloat() }.maxOrNull() ?: 1f).coerceAtLeast(1f)
            val inset = 8.dp.toPx()
            fun offset(point: ElectricUsage) = Offset(inset + ChronoUnit.DAYS.between(today.minusDays(29), point.day).toFloat() / 29 * (size.width - inset * 2), size.height - inset - (point.amount?.toFloat() ?: 0f) / max * (size.height - inset * 2))
            points.zipWithNext().forEach { (a, b) -> if (a.amount != null && b.amount != null && ChronoUnit.DAYS.between(a.day, b.day) == 1L) drawLine(if (a.estimated || b.estimated) estimateColor else color, offset(a), offset(b), 3.dp.toPx()) }
            points.forEach { drawCircle(if (it.estimated) estimateColor else color, 3.dp.toPx(), offset(it), style = if (it.amount == null) androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()) else androidx.compose.ui.graphics.drawscope.Fill) }
            selected?.let { val at = offset(it); drawLine(color.copy(alpha = .5f), Offset(at.x, 0f), Offset(at.x, size.height), 1.dp.toPx()); drawCircle(color, 6.dp.toPx(), at) }
        }
    }
    Text("${today.minusDays(29)} — $today", style = MaterialTheme.typography.labelSmall)
    averageUsage(points)?.let { Text("非充值日 ${points.count { !it.estimated && it.amount != null }} 天平均用电 $it 度", style = MaterialTheme.typography.bodySmall) }
}
}
