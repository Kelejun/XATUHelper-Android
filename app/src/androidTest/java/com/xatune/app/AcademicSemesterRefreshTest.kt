package com.xatune.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.xatune.app.data.*
import com.xatune.app.model.*
import com.xatune.app.ui.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

class AcademicSemesterRefreshTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun toolbarAndPullRefreshKeepHistoricalPageSemester() {
        wakeQaDevice()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as HelperApplication
        val store = app.store
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setBody(if (request.path.orEmpty().contains("!examTable")) "<table><tr><th>课程名称</th><th>日期</th><th>时间</th></tr><tr><td>历史考试</td><td>2026/12/01</td><td>09:00</td></tr></table>" else "考试安排") }
        server.start()
        val base = server.url("/eams/").toString()
        val login = server.url("/login").toString()
        lateinit var model: HelperViewModel
        try {
            runBlocking {
                store.select("exam-fixture", "101", TableKind.STUDENT)
                store.extra("current:exam-fixture", "101")
                store.semesters(listOf(Semester("101", "当前学期"), Semester("99", "历史学期")))
                for (term in listOf("101", "99")) store.saveAcademic("exam-fixture", AcademicKind.EXAMS, term, AcademicSnapshot())
            }
            compose.runOnUiThread {
                compose.activity.setShowWhenLocked(true); compose.activity.setTurnScreenOn(true)
                val vault = SecretVault(context)
                model = HelperViewModel(TimetableRepository(store, vault, app.cookies, SchoolNetwork(app.cookies, base, login)))
            }
            compose.waitUntil(10000) { !model.state.value.starting }
            compose.setContent { val state by model.state.collectAsState(); HelperTheme { AcademicScreen(AcademicKind.EXAMS, state, model, {}, {}, {}) } }
            compose.onNodeWithText("当前学期").performClick()
            compose.onNodeWithText("历史学期").performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription("刷新考试安排").performClick()
            compose.waitUntil(10000) { model.state.value.academic[academicKey(AcademicKind.EXAMS, "99")]?.data?.exams?.isNotEmpty() == true }
            repeat(2) { assertEquals("99", server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("semester.id")) }
            compose.onNodeWithTag("academicRefresh").performTouchInput { swipeDown(startY = height * .2f, endY = height * .9f, durationMillis = 600) }
            compose.waitUntil(10000) { server.requestCount >= 4 }
            repeat(2) { assertEquals("99", server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("semester.id")) }
            assertEquals("101", model.state.value.selectedSemester)
        } finally { server.shutdown(); runBlocking { store.clear() } }
    }
}
