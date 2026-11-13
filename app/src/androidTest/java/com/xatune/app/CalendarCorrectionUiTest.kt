package com.xatune.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xatune.app.model.*
import com.xatune.app.ui.*
import com.xatune.app.data.LocalStore
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarCorrectionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HelperApplication
    private val raw = SchoolCalendar("原始标题", "page", "fixture://calendar", "", "OCR", listOf(SemesterSpan("第一学期", "2026-09-01", "2027-01-20")), 42)
    @Before fun setup() = runBlocking {
        wakeQaDevice()
        compose.runOnUiThread {
            compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            compose.activity.setShowWhenLocked(true); compose.activity.setTurnScreenOn(true)
        }
        app.store.extra("schoolCalendar", raw)
        app.store.extra("schoolCalendar.corrections:${raw.imageUrl}", null)
    }
    @After fun cleanup() = runBlocking {
        app.store.extra("schoolCalendar", null)
        app.store.extra("schoolCalendar.corrections:${raw.imageUrl}", null)
    }
    @Test fun editsValidateSaveAndPersistWithoutReplacingOriginal() {
        var saved = false
        compose.setContent { HelperTheme { CalendarCorrectionEditor(raw, raw, false, {}) { draft ->
            runBlocking { app.campusRepository.saveCalendarCorrection(raw.imageUrl, draft) }; saved = true
        } } }
        compose.onNodeWithTag("calendarStart0").performTextReplacement("2026-02-30")
        compose.onNodeWithTag("saveCalendar").assertIsNotEnabled()
        compose.onNodeWithTag("calendarStart0").performTextReplacement("2026-09-02")
        compose.onNodeWithTag("calendarLabel0").performTextReplacement(" 校正学期 ")
        compose.onNodeWithTag("calendarNotes").performScrollTo().performTextInput(" 备注 ")
        compose.onNodeWithTag("saveCalendar").performClick()
        assertTrue(saved)
        runBlocking {
            val restored = LocalStore(compose.activity).extra("schoolCalendar.corrections:${raw.imageUrl}", CalendarCorrection::class.java)!!
            assertEquals("校正学期", restored.semesters.single().label)
            assertEquals(listOf("备注"), restored.notes)
            assertEquals(raw, app.campusRepository.originalCalendar())
            assertEquals("OCR", app.campusRepository.calendar()!!.rawText)
            assertEquals("2026-09-02", app.campusRepository.calendar()!!.semesters.single().start)
            assertEquals(raw.copy(imageUrl = "fixture://new"), app.campusRepository.applyCorrection(raw.copy(imageUrl = "fixture://new")))
            app.store.extra("schoolCalendar", raw.copy(fetchedAt = 43))
            assertEquals("校正学期", app.campusRepository.calendar()!!.semesters.single().label)
            app.campusRepository.saveCalendarCorrection(raw.imageUrl, CalendarCorrection.original(raw))
            assertNull(app.store.extra("schoolCalendar.corrections:${raw.imageUrl}", CalendarCorrection::class.java))
        }
    }
    @Test fun restoreIsOnlyADraftAndEmptyOcrCanBeCompleted() {
        var cancelled = false
        var saved: CalendarCorrection? = null
        val empty = raw.copy(semesters = emptyList())
        compose.setContent { HelperTheme { CalendarCorrectionEditor(raw, empty, false, { cancelled = true }) { saved = it } } }
        compose.onNodeWithTag("restoreCalendar").performScrollTo().performClick()
        compose.onNodeWithTag("saveCalendar").assertIsNotEnabled()
        compose.onNodeWithTag("addCalendarSemester").performScrollTo().performClick()
        compose.onNodeWithTag("calendarLabel0").performScrollTo().performTextInput("补全")
        compose.onNodeWithTag("calendarStart0").performScrollTo().performTextInput("2026-09-01")
        compose.onNodeWithTag("calendarEnd0").performScrollTo().performTextInput("2027-01-20")
        compose.onNodeWithTag("saveCalendar").assertIsEnabled()
        compose.onNodeWithText("取消").performClick()
        assertTrue(cancelled); assertNull(saved)
        runBlocking {
            app.store.extra("schoolCalendar", empty)
            app.campusRepository.saveCalendarCorrection(empty.imageUrl, CalendarCorrection(raw.semesters))
            assertEquals(raw.semesters, app.campusRepository.calendar()!!.semesters)
        }
    }
}
