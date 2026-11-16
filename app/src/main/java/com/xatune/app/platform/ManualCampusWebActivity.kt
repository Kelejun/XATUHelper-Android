package com.xatune.app.platform

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.xatune.app.BuildConfig
import com.xatune.app.data.SchoolUrls
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** This activity's process has its own WebView data directory and never initializes app sessions. */
class ManualCampusWebActivity : ComponentActivity() {
    private var web: WebView? = null
    private var documents: CampusWebDocuments? = null
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entry = intent.getStringExtra("entry").orEmpty()
        val page = intent.getStringExtra("page").orEmpty()
        fun allowed(url: String): Boolean = SchoolUrls.trusted(url) || (BuildConfig.DEMO_ENABLED && url.toHttpUrlOrNull()?.host in setOf("localhost", "127.0.0.1"))
        if (!allowed(entry)) { finish(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = LinearLayout(this)
        val message = TextView(this).apply { setPadding(24, 12, 24, 12) }
        toolbar.addView(Button(this).apply { text = "关闭"; setOnClickListener { finish() } })
        toolbar.addView(TextView(this).apply { text = intent.getStringExtra("title") ?: "校园网页" }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val manager = CookieManager.getInstance().apply { setAcceptCookie(true) }
        val jar = object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> = manager.getCookie(url.toString()).orEmpty().split(';').mapNotNull { Cookie.parse(url, it.trim()) }
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookies.forEach { cookie ->
                    val done = CountDownLatch(1)
                    Handler(Looper.getMainLooper()).post { manager.setCookie(url.toString(), cookie.toString()) { done.countDown() } }
                    check(done.await(10, TimeUnit.SECONDS)) { "网页 Cookie 同步超时" }
                }
            }
        }
        val browser = WebView(this).apply {
            configureCampusWebView(this)
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            documents = CampusWebDocuments(context, jar)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = documents?.intercept(request)
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return when (CampusWebNavigation.destination(page, request.url.toString(), request.hasGesture())) {
                        CampusWebNavigation.Destination.WEB -> documents?.redirect(view, request) == true
                        CampusWebNavigation.Destination.APP -> {
                            runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url).addCategory(android.content.Intent.CATEGORY_BROWSABLE)) }.onFailure { message.text = "没有可打开此链接的应用" }
                            true
                        }
                        CampusWebNavigation.Destination.BLOCKED -> { message.text = "已停止打开不受支持的地址"; true }
                    }
                }
                override fun onPageStarted(view: WebView, url: String, icon: android.graphics.Bitmap?) { message.text = "" }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { if (request.isForMainFrame) message.text = "网页加载失败，请重试" }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) { handler.cancel(); message.text = "学校网页的安全连接校验失败" }
            }
        }
        web = browser
        toolbar.addView(Button(this).apply { text = "重载"; setOnClickListener { browser.reload() } })
        root.addView(toolbar); root.addView(message)
        root.addView(browser, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (browser.canGoBack()) browser.goBack() else finish() }
        })
        manager.removeAllCookies { WebStorage.getInstance().deleteAllData(); browser.loadUrl(entry) }
    }
    override fun onDestroy() {
        documents?.close(); documents = null
        web?.apply { stopLoading(); webViewClient = WebViewClient(); destroy() }; web = null
        super.onDestroy()
    }
}
