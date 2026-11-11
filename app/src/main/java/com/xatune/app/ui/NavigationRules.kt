package com.xatune.app.ui

import com.xatune.app.model.*
import java.time.LocalDate

fun parentPage(page: String?): String? = when (page) {
    "notifications", "widgets", "checkinLocation", "announcementHistory" -> "settings"
    "charge" -> "electricity"
    "planWeb" -> "plan"
    else -> null
}
fun examAnchor(state: UiState): LocalDate = state.semesters.firstOrNull { it.id == state.currentSemester }
    ?.startDate?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: schoolToday()
