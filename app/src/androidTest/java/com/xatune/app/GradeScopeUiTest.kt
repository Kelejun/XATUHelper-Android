package com.xatune.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

class GradeScopeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HelperApplication
    private lateinit var server: MockWebServer
    private lateinit var model: HelperViewModel
    @Before fun prepare() {
        wakeQaDevice()
        runBlocking { app.store.clear(); app.store.select("grade-fixture", "101", TableKind.STUDENT); app.store.semesters(listOf(Semester("101", "当前学期", java.time.LocalDate.now().minusWeeks(15).toString()), Semester("99", "历史学期"))) }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val all = request.path.orEmpty().contains("historyCourseGrade")
                val course = if (all) "全部范围课程" else "当前范围课程"
                return MockResponse().setBodyDelay(1000, TimeUnit.MILLISECONDS).setBody("<table><tr><th>课程名称</th><th>学分</th><th>总评成绩</th></tr><tr><td>$course</td><td>3</td><td>88</td></tr></table>")
            }
        }
        server.start()
        val base = server.url("/eams/").toString(); val login = server.url("/login").toString()
        compose.runOnUiThread {
            compose.activity.setShowWhenLocked(true); compose.activity.setTurnScreenOn(true)
            compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            model = HelperViewModel(TimetableRepository(app.store, SecretVault(compose.activity), app.cookies, SchoolNetwork(app.cookies, base, login)))
        }
        compose.waitUntil(10000) { !model.state.value.starting }
    }
    private fun page() { compose.setContent { val state by model.state.collectAsState(); HelperTheme { AcademicScreen(AcademicKind.GRADES, state, model, {}, {}, {}) } } }
    private fun ready(scope: String) { compose.waitUntil(10000) { model.state.value.academic[academicKey(AcademicKind.GRADES, scope)]?.let { !it.loading && it.data?.grades != null } == true } }
    @After fun cleanup() { server.shutdown(); runBlocking { app.store.clear() } }
    @Test fun loadingBlocksUiAndCallbacksThenRangeRequestsReplaceOldCourses() {
        page()
        compose.waitUntil(5000) { model.state.value.academic[academicKey(AcademicKind.GRADES, "101")]?.loading == true }
        compose.onNodeWithTag("allGradesScope").assertIsNotEnabled()
        compose.onNodeWithText("当前学期").assertIsNotEnabled()
        compose.runOnUiThread { model.gradeScope(true) }
        assertFalse(model.state.value.allGrades)
        ready("101")
        compose.onNodeWithText("当前范围课程").assertIsDisplayed()
        compose.onNodeWithTag("allGradesScope").performClick()
        compose.waitUntil(5000) { model.state.value.allGrades && model.state.value.academic[academicKey(AcademicKind.GRADES, "all")]?.loading == true }
        compose.onNodeWithText("当前范围课程").assertDoesNotExist()
        ready("all")
        compose.onNodeWithText("全部范围课程").assertIsDisplayed()
        assertEquals(3, server.requestCount)
    }
    @Test fun demoKeepsSampleGradesAcrossRangeAndSemesterWithoutNetwork() {
        compose.runOnUiThread { model.demo() }
        compose.waitUntil(10000) { model.state.value.demo && !model.state.value.busy }
        page(); ready("demo")
        val sample = model.state.value.academic[academicKey(AcademicKind.GRADES, "demo")]!!.data!!.grades
        compose.onNodeWithTag("allGradesScope").performClick(); ready("all")
        assertEquals(sample, model.state.value.academic[academicKey(AcademicKind.GRADES, "all")]!!.data!!.grades)
        compose.onNodeWithTag("allGradesScope").performClick(); ready("demo")
        compose.onNodeWithText("示例学期").performClick()
        compose.onAllNodesWithText("示例学期").onLast().performClick(); ready("demo")
        assertEquals(sample, model.state.value.academic[academicKey(AcademicKind.GRADES, "demo")]!!.data!!.grades)
        assertEquals(0, server.requestCount)
    }
}
