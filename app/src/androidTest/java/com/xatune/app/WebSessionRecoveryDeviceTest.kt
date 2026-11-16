package com.xatune.app

import androidx.test.platform.app.InstrumentationRegistry
import com.xatune.app.data.*
import com.xatune.app.model.TableKind
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WebSessionRecoveryDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HelperApplication
    private val account = "web-recovery-fixture"
    @Before fun seed() = runBlocking { check(BuildConfig.APPLICATION_ID.endsWith(".qa")); app.repository.logout(); app.store.select(account, "101", TableKind.STUDENT) }
    @After fun cleanup() = runBlocking { app.repository.logout() }
    private fun repository(server: MockWebServer) = TimetableRepository(app.store, SecretVault(app), app.cookies, SchoolNetwork(app.cookies, server.url("/eams/").toString(), server.url("/login").toString()))
    @Test fun concurrentExpiredPagesShareOneSilentRecovery() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/eams/ready"))
            server.enqueue(MockResponse().setBody("已登录").setBodyDelay(300, TimeUnit.MILLISECONDS)); server.start()
            val repository = repository(server); val version = repository.sessionVersion(); val observed = repository.recoveryVersion()
            val results = (1..3).map { async(Dispatchers.IO) { repository.recoverWebSession(account, version, observed) } }.awaitAll()
            assertEquals(1, results.distinct().size); assertEquals(observed + 1, results.first()); assertEquals(2, server.requestCount)
        }
    }
    @Test fun passwordFallbackRunsOnlyAfterSilentRenewalFails() = runBlocking {
        val posts = AtomicInteger(); val gets = AtomicInteger()
        val login = "<form><input name='password'><input id='pwdEncryptSalt' value='1234567890abcdef'><input id='execution' name='execution' value='fixture'></form>"
        SecretVault(app).save(Secrets(Credentials(account, "fixture-password")))
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/login" && request.method == "POST" -> { posts.incrementAndGet(); MockResponse().setResponseCode(302).setHeader("Location", "/eams/ready") }
                    request.path == "/login" -> { gets.incrementAndGet(); MockResponse().setBody(login) }
                    else -> MockResponse().setBody("已登录")
                }
            }; server.start()
            val repository = repository(server)
            repository.recoverWebSession(account, repository.sessionVersion(), repository.recoveryVersion())
            assertEquals(2, gets.get()); assertEquals(1, posts.get()); assertEquals(account, SecretVault(app).load().credentials!!.account)
        }
    }
    @Test fun cancellationAndAccountInvalidationRejectRecoveryAndBrowserPersistence() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("已登录").setBodyDelay(800, TimeUnit.MILLISECONDS)); server.start()
            val repository = repository(server); val version = repository.sessionVersion(); val observed = repository.recoveryVersion()
            val task = async(Dispatchers.IO) { repository.recoverWebSession(account, version, observed) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)); repository.invalidateSession()
            try { task.await(); fail("Old recovery must be cancelled") } catch (_: CancellationException) { }
            assertEquals(observed, repository.recoveryVersion())
            try { repository.saveBrowserCookies(version, observed); fail("Old browser must not write cookies") } catch (_: CancellationException) { }
            assertFalse(android.webkit.CookieManager.getInstance().getCookie("https://authserver.xatu.edu.cn/").orEmpty().contains("stale=old"))
        }
    }
}
