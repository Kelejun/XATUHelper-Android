package com.xatune.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xatune.app.model.*

@Composable
fun CalendarCorrectionEditor(current: SchoolCalendar, original: SchoolCalendar, busy: Boolean, close: () -> Unit, save: (CalendarCorrection) -> Unit) {
    var spans by remember { mutableStateOf(current.semesters) }
    var notes by remember { mutableStateOf(current.notes.orEmpty().joinToString("\n")) }
    val draft = CalendarCorrection(spans, notes.lines())
    val error = draft.error()
    Dialog(onDismissRequest = { if (!busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("calendarEditor")) {
            Column(Modifier.safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = close, enabled = !busy) { Text("取消") }
                    TextButton(onClick = { save(draft) }, enabled = error == null && !busy, modifier = Modifier.testTag("saveCalendar")) { Text("保存") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("手动校正", style = MaterialTheme.typography.headlineSmall)
                    spans.forEachIndexed { index, span ->
                        fun update(value: SemesterSpan) { spans = spans.toMutableList().also { it[index] = value } }
                        OutlinedTextField(span.label, { update(span.copy(label = it)) }, label = { Text("学期名称") }, modifier = Modifier.fillMaxWidth().testTag("calendarLabel$index"))
                        OutlinedTextField(span.start, { update(span.copy(start = it)) }, label = { Text("开学日期（yyyy-MM-dd）") }, modifier = Modifier.fillMaxWidth().testTag("calendarStart$index"))
                        OutlinedTextField(span.end, { update(span.copy(end = it)) }, label = { Text("结课日期（yyyy-MM-dd）") }, modifier = Modifier.fillMaxWidth().testTag("calendarEnd$index"))
                        TextButton(onClick = { spans = spans.filterIndexed { i, _ -> i != index } }) { Text("删除学期") }
                        HorizontalDivider()
                    }
                    TextButton(onClick = { spans = spans + SemesterSpan("", "", "") }, modifier = Modifier.testTag("addCalendarSemester")) { Text("新增学期") }
                    OutlinedTextField(notes, { notes = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth().testTag("calendarNotes"))
                    TextButton(onClick = { spans = original.semesters; notes = original.notes.orEmpty().joinToString("\n") }, modifier = Modifier.testTag("restoreCalendar")) { Text("恢复识别结果") }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("calendarError")) }
                }
            }
        }
    }
}
