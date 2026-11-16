package com.xatune.app

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.xatune.app.data.*
import com.xatune.app.model.TableKind
import com.xatune.app.ui.*
import kotlinx.coroutines.*
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WebRecoveryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun content(block: @Composable () -> Unit) { compose.runOnUiThread { compose.activity.setContent(content = block) }; compose.waitForIdle() }
    private val app get() = compose.activity.application as HelperApplication
    private val account = "web-ui-fixture"
    private val auth = "https://authserver.xatu.edu.cn/"
    private fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup) (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
    private fun browser(): WebView { var result: WebView? = null; compose.waitUntil(10000) { compose.runOnUiThread { result = find(compose.activity.window.decorView) }; result != null }; return result!! }
    private fun nativeCookie(value: String) { app.cookies.saveFromResponse(auth.toHttpUrl(), listOf(Cookie.Builder().name("session_fixture").value(value).hostOnlyDomain("authserver.xatu.edu.cn").path("/").secure().build())) }
    private fun cookies(web: WebView): String { var result = ""; compose.runOnUiThread { result = android.webkit.CookieManager.getInstance().getCookie(auth).orEmpty() }; return result }
    private fun authPage(web: WebView) { compose.runOnUiThread { web.loadDataWithBaseURL(auth + "authserver/login", "<html><body>认证表单</body></html>", "text/html", "UTF-8", null) } }
    @Before fun prepare() {
        check(BuildConfig.APPLICATION_ID.endsWith(".qa")); wakeQaDevice()
        compose.runOnUiThread { compose.activity.setShowWhenLocked(true); compose.activity.setTurnScreenOn(true); compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        runBlocking { app.repository.logout(); app.store.select(account, "101", TableKind.STUDENT); withContext(Dispatchers.IO) { nativeCookie("old") } }
    }
    @After fun cleanup() { runBlocking { app.repository.logout() } }
    @Test fun finalAuthenticationRestoresOriginalEntryOnceAndManualLoginStaysIsolated() {
        val entries = AtomicInteger(); val renewals = AtomicInteger()
        val manualCookies = java.util.concurrent.atomic.AtomicReference<String>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/login" -> { renewals.incrementAndGet(); nativeCookie("new"); MockResponse().setResponseCode(302).setHeader("Location", "/eams/ready") }
                    "/business" -> { val count = entries.incrementAndGet(); if (count == 3) manualCookies.set(request.getHeader("Cookie").orEmpty()); MockResponse().setBody("<body>业务入口</body>").apply { if (count == 3) setHeader("Set-Cookie", "manual_fixture=isolated; Path=/") } }
                    else -> MockResponse().setBody("已登录")
                }
            }; server.start()
            val original = CampusLinks.pages.getValue("charge")
            CampusLinks.pages["charge"] = "电费充值" to server.url("/business").toString()
            try {
                val repository = TimetableRepository(app.store, SecretVault(app), app.cookies, SchoolNetwork(app.cookies, server.url("/eams/").toString(), server.url("/login").toString()))
                content { HelperTheme { CampusWeb("charge", {}, repository) } }
                val web = browser(); compose.waitUntil(15000) { entries.get() == 1 && cookies(web).contains("session_fixture=old") }
                authPage(web)
                try { compose.waitUntil(15000) { entries.get() == 2 && cookies(web).contains("session_fixture=new") } }
                catch (e: Throwable) { var at = ""; compose.runOnUiThread { at = web.url.orEmpty() }; throw AssertionError("entries=${entries.get()} renewals=${renewals.get()} cookie=${cookies(web)} url=$at tree=${compose.onRoot().printToString()}", e) }
                assertEquals(1, renewals.get())
                authPage(web)
                compose.waitUntil(10000) { compose.onAllNodesWithText("重新登录网页").fetchSemanticsNodes().isNotEmpty() }
                val cookieReady = java.util.concurrent.CountDownLatch(1)
                compose.runOnUiThread { android.webkit.CookieManager.getInstance().setCookie(server.url("/").toString(), "native_fixture=present; Path=/") { cookieReady.countDown() } }
                assertTrue(cookieReady.await(5, TimeUnit.SECONDS))
                compose.onNodeWithText("重新登录网页").performClick()
                compose.waitUntil(15000) { entries.get() == 3 }
                assertEquals(1, renewals.get())
                assertFalse(manualCookies.get().orEmpty().contains("native_fixture"))
                val nativeLocal = android.webkit.CookieManager.getInstance().getCookie(server.url("/").toString()).orEmpty()
                assertTrue(nativeLocal.contains("native_fixture=present")); assertFalse(nativeLocal.contains("manual_fixture"))
                assertTrue(android.webkit.CookieManager.getInstance().getCookie(auth).orEmpty().contains("session_fixture=new"))
            } finally {
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
                content { Text("完成") }; CampusLinks.pages["charge"] = original
            }
        }
    }
    @Test fun closingPageCancelsPendingRecoveryBeforeReload() {
        val entries = AtomicInteger()
        val requested = java.util.concurrent.atomic.AtomicBoolean()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when (request.requestUrl!!.encodedPath) {
                    "/login" -> { requested.set(true); MockResponse().setBody("已登录").setBodyDelay(1500, TimeUnit.MILLISECONDS) }
                    "/business" -> { entries.incrementAndGet(); MockResponse().setBody("业务入口") }
                    else -> MockResponse().setBody("")
                }
            }; server.start()
            val original = CampusLinks.pages.getValue("leave"); CampusLinks.pages["leave"] = "请销假" to server.url("/business").toString()
            try {
                val repository = TimetableRepository(app.store, SecretVault(app), app.cookies, SchoolNetwork(app.cookies, server.url("/eams/").toString(), server.url("/login").toString()))
                content { HelperTheme { CampusWeb("leave", {}, repository) } }
                val web = browser(); compose.waitUntil(10000) { entries.get() == 1 }; authPage(web)
                compose.waitUntil(10000) { requested.get() }
                content { Text("网页已关闭") }
                Thread.sleep(1800)
                assertEquals(1, entries.get()); assertEquals(0L, repository.recoveryVersion())
            } finally { content { Text("完成") }; CampusLinks.pages["leave"] = original }
        }
    }
}
