package com.xatune.app

import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.xatune.app.data.*
import com.xatune.app.model.*
import com.xatune.app.ui.HelperViewModel
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class InformationRefreshDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as HelperApplication
    private val today = LocalDate.now()
    private val start = today.minusWeeks(15)
    private val account = "information-fixture"
    private val vmStore = ViewModelStore()
    private lateinit var model: HelperViewModel
    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(45000) { while (!condition()) delay(50) }
    private fun model(repository: TimetableRepository, campus: CampusRepository) {
        instrumentation.runOnMainSync { model = HelperViewModel(repository, campus); vmStore.put("fixture", model) }
    }
    private suspend fun seed() {
        check(BuildConfig.APPLICATION_ID.endsWith(".qa"))
        app.store.clear()
        app.store.select(account, "101", TableKind.STUDENT)
        app.store.extra("current:$account", "101")
        val semester = Semester("101", "当前学期", start.toString())
        app.store.semesters(listOf(semester))
        app.store.save(Timetable(account, semester, TableKind.STUDENT, emptyList(), 12, start.toString(), System.currentTimeMillis() - 86_400_000))
        app.store.extra("schoolCalendar", SchoolCalendar("fixture", "", "fixture://calendar", "", "", listOf(SemesterSpan("当前", start.toString(), today.plusDays(30).toString()), SemesterSpan("下学期", today.plusDays(60).toString(), today.plusDays(160).toString())), 1, recognitionVersion = 1))
    }
    @After fun cleanup() { instrumentation.runOnMainSync { vmStore.clear() }; runBlocking { app.store.clear() } }
    @Test fun foregroundRefreshesAllKindsInOrderThenRetriesFailedPlanOnceOnNextResume() = runBlocking {
        seed()
        val calls = Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
        val plans = AtomicInteger()
        val catalog = AtomicInteger()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    calls.add(path to System.currentTimeMillis())
                    val body = when {
                        path.endsWith("courseTableForStd.action") -> """semesterCalendar({value:"101"}); add("ids","111"); add("ids","222");"""
                        path.endsWith("dataQuery.action") -> if (request.body.readUtf8().contains("semesterCalendar")) """{semesters:{y:[{id:101,schoolYear:"2026-2027",name:"1",startDate:"$start"}]}}""" else "1"
                        path.endsWith("courseTableForStd!courseTable.action") -> """var table0 = new CourseTable(2026,84); var unitCount = 12; new TaskActivity(a,b,"1(001)","课程(001)","1","教1","0111",null); index=0*unitCount+0;"""
                        path.endsWith("person.action") -> """semesterCalendar({value:"101"})"""
                        path.endsWith("person!search.action") -> "<table><tr><th>课程名称</th><th>学分</th><th>总评成绩</th></tr><tr><td>课程</td><td>3</td><td>88</td></tr></table>"
                        path.endsWith("stdExamTable.action") -> "考试安排"
                        path.endsWith("stdExamTable!examTable.action") -> "<table><tr><th>课程名称</th><th>日期</th><th>时间</th></tr></table>"
                        path.endsWith("myPlanCompl.action") -> {
                            if (plans.incrementAndGet() == 1) return MockResponse().setResponseCode(503)
                            "<table><tr class='darkColumn'><td>基础课程</td><td>160</td><td>96</td></tr></table>"
                        }
                        path.endsWith("myPlan.action") -> { catalog.incrementAndGet(); "<table><tr><td>MATH01</td><td class='course'>1 数学</td>" + listOf("5", "80", "", "", "", "", "", "3", "", "是").joinToString("") { "<td>$it</td>" } + "</tr></table>" }
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setBody(body)
                }
            }
            server.start()
            val repository = TimetableRepository(app.store, SecretVault(app), app.cookies, SchoolNetwork(app.cookies, server.url("/eams/").toString(), server.url("/login").toString()))
            model(repository, CampusRepository(app, app.store, repository, PayNetwork(app.cookies)))
            await { model.state.value.academic["PLAN:all"]?.error != null }
            assertEquals(1, plans.get())
            assertEquals(true, app.store.extra("academic.pendingPlan:$account", Boolean::class.javaObjectType))
            assertTrue(app.store.academic(account, AcademicKind.EXAMS, "101")!!.exams.isEmpty())
            assertFalse(InformationRefreshPolicy.needsDailyRefresh(app.store.academic(account, AcademicKind.EXAMS, "101")!!.fetchedAt))
            val order = calls.toList()
            val table = order.first { it.first.endsWith("!courseTable.action") }.second
            val grades = order.first { it.first.endsWith("person.action") }.second
            val exams = order.first { it.first.endsWith("stdExamTable.action") }.second
            val plan = order.first { it.first.endsWith("myPlanCompl.action") }.second
            assertTrue(grades - table >= 2900); assertTrue(exams - grades >= 2900); assertTrue(plan - exams >= 2900)
            val before = calls.size
            instrumentation.runOnMainSync { model.resume() }
            await { plans.get() == 2 && app.store.extra("academic.pendingPlan:$account", Boolean::class.javaObjectType) != true }
            assertEquals(1, catalog.get())
            assertEquals(before + 2, calls.size)
            // A new foreground check on the same day reuses all successful snapshots.
            instrumentation.runOnMainSync { model.resume() }
            delay(3500)
            assertEquals(before + 2, calls.size)
        }
    }
    @Test fun reducedModeOnlyRefreshesTheMostOverdueKind() = runBlocking {
        seed()
        app.store.extra("reduceAcademicRefreshFrequency", true)
        val table = app.store.baseTable(account, "101", TableKind.STUDENT)!!
        app.store.save(table.copy(fetchedAt = System.currentTimeMillis()))
        app.store.saveAcademic(account, AcademicKind.GRADES, "101", AcademicSnapshot(grades = GradePage(emptyList(), GradeSummary())))
        app.store.saveAcademic(account, AcademicKind.EXAMS, "101", AcademicSnapshot())
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<table><tr class='darkColumn'><td>基础课程</td><td>160</td><td>96</td></tr></table>"))
            server.enqueue(MockResponse().setBody("<table><tr><td>MATH01</td><td class='course'>1 数学</td>" + listOf("5", "80", "", "", "", "", "", "3", "", "是").joinToString("") { "<td>$it</td>" } + "</tr></table>"))
            server.start()
            val repository = TimetableRepository(app.store, SecretVault(app), app.cookies, SchoolNetwork(app.cookies, server.url("/eams/").toString(), server.url("/login").toString()))
            model(repository, CampusRepository(app, app.store, repository, PayNetwork(app.cookies)))
            await { app.store.academic(account, AcademicKind.PLAN, "all") != null }
            delay(3500)
            assertEquals(2, server.requestCount)
            assertTrue(server.takeRequest().path!!.endsWith("myPlanCompl.action"))
            assertTrue(server.takeRequest().path!!.endsWith("myPlan.action"))
        }
    }
    @Test fun cancelledPlanKeepsPendingFlagAndDoesNotFetchCatalog() = runBlocking {
        seed()
        app.store.extra("academic.pendingPlan:$account", true)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBodyDelay(1000, java.util.concurrent.TimeUnit.MILLISECONDS).setBody("<table><tr class='darkColumn'><td>基础课程</td><td>160</td><td>96</td></tr></table>"))
            server.start()
            val repository = TimetableRepository(app.store, SecretVault(app), app.cookies, SchoolNetwork(app.cookies, server.url("/eams/").toString(), server.url("/login").toString()))
            val task = launch(Dispatchers.Default) { repository.refreshAcademic(account, AcademicKind.PLAN, "all") }
            assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS))
            task.cancelAndJoin()
            assertEquals(true, app.store.extra("academic.pendingPlan:$account", Boolean::class.javaObjectType))
            assertEquals(1, server.requestCount)
            assertNull(app.store.academic(account, AcademicKind.PLAN, "all"))
        }
    }
    @Test fun leavingForegroundCancelsQueuedRequests() = runBlocking {
        seed()
        MockWebServer().use { server ->
            server.start()
            val repository = TimetableRepository(app.store, SecretVault(app), app.cookies, SchoolNetwork(app.cookies, server.url("/eams/").toString(), server.url("/login").toString()))
            model(repository, CampusRepository(app, app.store, repository, PayNetwork(app.cookies)))
            await { !model.state.value.starting }
            instrumentation.runOnMainSync { model.pauseAutomaticUpdates() }
            delay(3500)
            assertEquals(0, server.requestCount)
            assertTrue(model.state.value.academic.values.none { it.loading })
        }
    }
    @Test fun calendarReusesImageRetainsOldDatesAndContinuesIncompleteOcrAttempts() = runBlocking {
        seed()
        var imageRequests = 0
        var image = "one.jpg"
        val bytes = instrumentation.context.assets.open("calendar-2026.jpg").use { it.readBytes() }
        val repository = CampusRepository(app, app.store, app.repository, PayNetwork(app.cookies)) { url ->
            when {
                url == CalendarParser.index -> "<a href='/info/1/calendar.htm'>校历</a>".toByteArray()
                url.endsWith("calendar.htm") -> "<title>测试校历</title><img src='/$image'>".toByteArray()
                else -> { imageRequests++; bytes }
            }
        }
        val first = repository.refreshCalendar()
        assertTrue(first.semesters.size >= 2)
        val target = first.semesters.minOf { it.start }.take(4).toInt() + 1
        app.store.extra("academic.calendar.targetYear", target)
        repository.automaticCalendar()
        assertEquals(1, imageRequests)
        assertFalse(repository.shouldRefreshCalendar())
        app.store.extra("academic.calendar.lastAttempt", 1L)
        app.store.extra("schoolCalendar", first.copy(semesters = emptyList()))
        repository.automaticCalendar()
        assertEquals(2, imageRequests)
        app.store.extra("academic.calendar.lastAttempt", 1L)
        image = "two.jpg"
        repository.automaticCalendar()
        assertEquals(3, imageRequests)
        assertTrue(repository.scheduleCalendars().any { it.imageUrl == first.imageUrl })
        val nextYear = first.copy(imageUrl = "fixture://new-year", semesters = first.semesters.map { it.copy(start = LocalDate.parse(it.start).plusYears(1).toString(), end = LocalDate.parse(it.end).plusYears(1).toString()) })
        app.store.extra("schoolCalendar", nextYear)
        assertFalse(repository.shouldRefreshCalendar())
        assertNull(app.store.extra("academic.calendar.targetYear", Int::class.javaObjectType))
    }
}
