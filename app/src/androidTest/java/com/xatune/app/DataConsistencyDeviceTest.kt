package com.xatune.app

import androidx.test.platform.app.InstrumentationRegistry
import com.xatune.app.data.*
import com.xatune.app.model.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

class DataConsistencyDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HelperApplication
    private val account = "consistency-fixture"
    @Before fun seed() = runBlocking {
        check(BuildConfig.APPLICATION_ID.endsWith(".qa"))
        app.repository.logout()
        app.store.select(account, "101", TableKind.STUDENT)
        app.store.extra("current:$account", "101")
    }
    @After fun cleanup() = runBlocking { app.repository.logout() }
    @Test fun lateAcademicResponseCannotPersistOrReloginAfterInvalidation() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<table><tr><th>课程名称</th><th>日期</th><th>时间</th></tr><tr><td>旧考试</td><td>2026/12/01</td><td>09:00</td></tr></table>").setBodyDelay(800, TimeUnit.MILLISECONDS))
            server.start()
            val repository = TimetableRepository(app.store, SecretVault(app), app.cookies, SchoolNetwork(app.cookies, server.url("/eams/").toString(), server.url("/login").toString()))
            val task = async(Dispatchers.IO) { repository.refreshAcademic(account, AcademicKind.EXAMS, "101") }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)); repository.invalidateSession()
            try { task.await(); fail("Old response must be cancelled") } catch (_: CancellationException) { }
            assertNull(app.store.academic(account, AcademicKind.EXAMS, "101")); assertEquals(1, server.requestCount)
        }
    }
    @Test fun lateExpiredResponseDoesNotAttemptRecovery() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<form id='casLoginForm'><input name='username'><input name='password'></form>").setBodyDelay(700, TimeUnit.MILLISECONDS))
            server.start()
            val repository = TimetableRepository(app.store, SecretVault(app), app.cookies, SchoolNetwork(app.cookies, server.url("/eams/").toString(), server.url("/login").toString()))
            val task = async(Dispatchers.IO) { repository.refreshAcademic(account, AcademicKind.EXAMS, "101") }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)); repository.invalidateSession()
            try { task.await(); fail("Old failure must be cancelled") } catch (_: CancellationException) { }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun scopedCachesAndDormHistoriesRemainIndependent() = runBlocking {
        val current = AcademicSnapshot(exams = listOf(Exam("e", "当前考试", "", "", "2026-12-01 09:00", "", "", "")))
        app.store.saveAcademic(account, AcademicKind.EXAMS, "101", current)
        app.store.saveAcademic(account, AcademicKind.EXAMS, "99", AcademicSnapshot())
        assertEquals("当前考试", app.store.academic(account, AcademicKind.EXAMS, "101")!!.exams.single().course)
        assertNull(app.store.academic("other", AcademicKind.EXAMS, "101")); assertNull(app.store.academic(account, AcademicKind.EXAMS, "all"))
        val a = Dorm(CampusOption("p", "P"), CampusOption("a", "A"), CampusOption("b", "B"), CampusOption("1", "1"))
        val b = a.copy(room = CampusOption("2", "2"))
        app.store.record(account, Electricity(a, "10", 1)); app.store.record(account, Electricity(b, "20", 2))
        app.campusRepository.saveDorm(account, a); app.store.extra("electric:$account", Electricity(a, "10", 1))
        app.campusRepository.saveDorm(account, b)
        assertNull(app.campusRepository.electricity(account)); assertEquals("10", app.store.history(account, a.key).single().balance); assertEquals("20", app.store.history(account, b.key).single().balance)
    }
}
