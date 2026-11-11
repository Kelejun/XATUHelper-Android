package com.xatune.app

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.xatune.app.ui.CampusLinks
import com.xatune.app.ui.CampusWeb
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class CampusPaymentNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun findWeb(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWeb(view.getChildAt(it)) }
        else -> null
    }

    @Test fun legacyPaymentHttpIsLimitedToTheExactSchoolHost() {
        val policy = android.security.NetworkSecurityPolicy.getInstance()
        assertTrue(policy.isCleartextTrafficPermitted("jfpay.xatu.edu.cn"))
        assertFalse(policy.isCleartextTrafficPermitted("sub.jfpay.xatu.edu.cn"))
        assertFalse(policy.isCleartextTrafficPermitted("jfpay.xatu.edu.cn.evil.example"))
        assertFalse(policy.isCleartextTrafficPermitted("example.com"))
    }

    @Test fun renewalFollowsCrossHostRedirectAndPreservesPostAndCookies() {
        check(BuildConfig.APPLICATION_ID.endsWith(".qa"))
        val server = MockWebServer()
        val submitted = AtomicReference<RecordedRequest>()
        server.start()
        val cashier = server.url("/cashier").newBuilder().host("localhost").build()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/renew" -> MockResponse().setHeader("Content-Type", "text/html").setBody("""
                    <script>setTimeout(function(){location.href='$cashier'},100)</script>
                """.trimIndent())
                "/cashier" -> MockResponse().setResponseCode(302).setHeader("Location", "/form")
                    .setHeader("Set-Cookie", "payment_fixture=session; Path=/; HttpOnly")
                "/form" -> MockResponse().setHeader("Content-Type", "text/html").setBody("""
                    <form method="post" action="/submitted"><input name="order" value="fixture"></form>
                    <script>document.forms[0].submit()</script>
                """.trimIndent())
                "/submitted" -> {
                    submitted.set(request)
                    MockResponse().setHeader("Content-Type", "text/html").setBody("<title>cashier-ready</title><p>Payment fixture ready</p>")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val original = CampusLinks.pages.getValue("netfee")
        CampusLinks.pages["netfee"] = "校园网充值" to server.url("/renew").newBuilder().host("127.0.0.1").build().toString()
        try {
            compose.runOnUiThread {
                compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                compose.activity.setShowWhenLocked(true)
                compose.activity.setTurnScreenOn(true)
                compose.activity.setContent { CampusWeb("netfee", {}) }
            }
            compose.waitUntil(20_000) { submitted.get() != null }
            val request = submitted.get()
            assertEquals("POST", request.method)
            assertEquals("order=fixture", request.body.readUtf8())
            assertTrue(request.getHeader("Cookie").orEmpty().contains("payment_fixture=session"))
            compose.waitUntil(10_000) {
                var ready = false
                compose.runOnUiThread { ready = findWeb(compose.activity.window.decorView)?.title == "cashier-ready" }
                ready
            }
            compose.runOnUiThread {
                val web = findWeb(compose.activity.window.decorView)!!
                assertTrue(web.url.orEmpty().endsWith("/submitted"))
            }
        } finally {
            CampusLinks.pages["netfee"] = original
            compose.runOnUiThread { compose.activity.setContent {} }
            server.shutdown()
        }
    }
}
