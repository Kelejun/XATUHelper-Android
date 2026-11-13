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

@Composable
internal fun ElectricUsageChart(points: List<ElectricUsage>, today: LocalDate = schoolToday()) {
    var selected by remember(points) { mutableStateOf<ElectricUsage?>(null) }
    if (points.isEmpty()) { InfoPanel("最近 30 天暂无连续两天的用量记录"); return }
    val color = MaterialTheme.colorScheme.primary
    Column {
    val config = LocalViewConfiguration.current
    val longPressConfig = remember(config) { object : ViewConfiguration by config { override val longPressTimeoutMillis = 250L } }
    Box(Modifier.fillMaxWidth().height(80.dp)) { selected?.let { point ->
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(12.dp).testTag("electricUsageReading")) {
                Text("${point.day} · ${point.amount.setScale(2, RoundingMode.HALF_UP)} 度")
                if (point.recharge) SuggestionChip(onClick = {}, label = { Text("有电费充值") })
            }
        }
    }
    }
    CompositionLocalProvider(LocalViewConfiguration provides longPressConfig) {
        Canvas(Modifier.fillMaxWidth().height(160.dp).testTag("electricUsageChart")
            .semantics { contentDescription = selected?.let { "${it.day} ${it.amount.setScale(2, RoundingMode.HALF_UP)} 度${if (it.recharge) " 有电费充值" else ""}" } ?: "最近 30 天每日用电图" }
            .pointerInput(points, today) {
                var x = 0f
                fun select() { val day = today.minusDays(29).plusDays((x.coerceIn(0f, size.width.toFloat()) / size.width.coerceAtLeast(1) * 29).toLong()); selected = points.minByOrNull { abs(ChronoUnit.DAYS.between(day, it.day)) } }
                detectDragGesturesAfterLongPress(onDragStart = { x = it.x; select() }, onDragEnd = { selected = null }, onDragCancel = { selected = null }) { change, drag -> change.consume(); x += drag.x; select() }
            }) {
            val max = points.maxOf { it.amount.toFloat() }.coerceAtLeast(1f)
            val inset = 8.dp.toPx()
            fun offset(point: ElectricUsage) = Offset(inset + ChronoUnit.DAYS.between(today.minusDays(29), point.day).toFloat() / 29 * (size.width - inset * 2), size.height - inset - point.amount.toFloat() / max * (size.height - inset * 2))
            points.zipWithNext().forEach { (a, b) -> if (ChronoUnit.DAYS.between(a.day, b.day) == 1L) drawLine(color, offset(a), offset(b), 3.dp.toPx()) }
            points.forEach { drawCircle(color, 3.dp.toPx(), offset(it)) }
            selected?.let { val at = offset(it); drawLine(color.copy(alpha = .5f), Offset(at.x, 0f), Offset(at.x, size.height), 1.dp.toPx()); drawCircle(color, 6.dp.toPx(), at) }
        }
    }
    Text("${today.minusDays(29)} — $today", style = MaterialTheme.typography.labelSmall)
    averageUsage(points)?.let { Text("有记录的 ${points.size} 天平均用电 $it 度", style = MaterialTheme.typography.bodySmall) }
}
}
