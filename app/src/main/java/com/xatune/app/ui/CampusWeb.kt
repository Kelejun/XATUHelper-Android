package com.xatune.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.xatune.app.platform.CampusWebNavigation
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.delay

object CampusLinks {
    fun mayLocate(page: String, origin: String, topUrl: String): Boolean {
        val source = origin.toHttpUrlOrNull() ?: return false
        val top = topUrl.toHttpUrlOrNull() ?: return false
        return page == "checkin" && source.scheme == "https" && source.host == "ehall.xatu.edu.cn" && source.port == 443 && top.scheme == "https" && top.host == source.host && top.port == 443
    }
    val pages = linkedMapOf(
        "planWeb" to ("学业完成情况" to "http://jwgl2018.xatu.edu.cn/eams/myPlanCompl.action"),
        "charge" to ("电费充值" to "https://jfpay.xatu.edu.cn/casLogin/"),
        "netfee" to ("校园网充值" to "https://self.xatu.edu.cn/Self/sso/packageChange"),
        "checkin" to ("签到" to "https://ehall.xatu.edu.cn/xsfw/sys/swmqdzsapp/*default/index.do#/jrqd"),
        "leave" to ("请销假" to "https://ehall.xatu.edu.cn/xsfw/sys/swmxsqjapp/*default/index.do?wxType=1"),
        "eams" to ("教务系统" to "http://jwgl2018.xatu.edu.cn/"),
        "ehall" to ("一网通办" to "https://ywtb.xatu.edu.cn/")
    )
}
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable fun CampusWeb(page: String, onClose: () -> Unit) {
    val (title, url) = CampusLinks.pages.getValue(page)
    val context = LocalContext.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var locationBridge by remember { mutableStateOf<com.xatune.app.platform.CheckinLocationBridge?>(null) }
    val checkinLocation by rememberCheckinLocationSettings()
    var documents by remember { mutableStateOf<com.xatune.app.platform.CampusWebDocuments?>(null) }
    var diagnostics by remember { mutableStateOf<com.xatune.app.platform.WebDiagnostics?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var navigation by remember { mutableIntStateOf(0) }
    LaunchedEffect(loading, navigation) {
        if (loading) {
            delay(30_000)
            diagnostics?.event("load-timeout"); diagnostics?.capture("timeout")
            loading = false
            error = "网页加载超时，请检查网络后点击右上角重试，也可在浏览器中打开。"
        }
    }
    var pendingLocation by remember { mutableStateOf<Pair<String, GeolocationPermissions.Callback>?>(null) }
    var files by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> files?.onReceiveValue(uris.takeIf { it.isNotEmpty() }?.toTypedArray()); files = null }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        pendingLocation?.let { (origin, callback) ->
            val allowed = granted.values.any { it } && !com.xatune.app.platform.CheckinLocationPreferences(context).read().enabled && CampusLinks.mayLocate(page, origin, web?.url.orEmpty())
            callback.invoke(origin, allowed, false)
        }; pendingLocation = null
    }
    val backModifier = Modifier.predictiveBack { if (web?.canGoBack() == true) web?.goBack() else onClose() }
    DisposableEffect(Unit) { onDispose {
        pendingLocation?.let { it.second.invoke(it.first, false, false) }; files?.onReceiveValue(null)
        documents?.close(); documents = null
        diagnostics?.close(); diagnostics = null
        locationBridge?.close(); locationBridge = null
        web?.apply { stopLoading(); webChromeClient = null; webViewClient = WebViewClient(); removeAllViews(); destroy() }; web = null
    } }
    Scaffold(modifier = backModifier, topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "关闭网页") } }, actions = { WebDiagnosticExport { diagnostics?.capture("export") }; IconButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web?.url ?: url))) }.onFailure { error = "没有可打开网页的浏览器" } }) { Icon(Icons.Default.OpenInNew, "在浏览器中打开") }; IconButton(onClick = { error = null; web?.reload() }) { Icon(Icons.Default.Refresh, "重新加载网页") } }) }) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            LoadingProgress(loading)
            if (page == "checkin") Box(Modifier.padding(12.dp)) {
                InfoPanel(if (checkinLocation.enabled) {
                    if (locationBridge?.supportsDocumentStart == true) "签到测试已开启，当前网页使用设置中选择的坐标。"
                    else "当前系统 WebView 版本不支持签到测试，请更新 Android System WebView 后重试；本次不会提供实际定位。"
                } else "定位仅用于当前学校签到网页，需你授权后提供。")
            }
            error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { ctx -> WebView(ctx).apply {
                web = this
                com.xatune.app.platform.configureCampusWebView(this)
                documents = com.xatune.app.platform.CampusWebDocuments(this.context)
                if (page == "checkin") locationBridge = com.xatune.app.platform.CheckinLocationBridge(this).also { it.attach() }
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                settings.allowFileAccess = false; settings.allowContentAccess = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.setGeolocationEnabled(page == "checkin")
                diagnostics = com.xatune.app.platform.WebDiagnostics(this, page).also { it.attach() }
                diagnostics?.event("location-bridge", "attached=${locationBridge != null} documentStart=${locationBridge?.supportsDocumentStart} customEnabled=${checkinLocation.enabled}")
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean = diagnostics?.console(message) ?: false
                    override fun onProgressChanged(view: WebView, progress: Int) { if (progress == 100) diagnostics?.event("progress", "100") }
                    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                        diagnostics?.event("location-prompt", "origin=${com.xatune.app.platform.WebDiagnosticText.url(origin)}")
                        if (page == "checkin" && com.xatune.app.platform.CheckinLocationPreferences(context).read().enabled) {
                            callback.invoke(origin, false, false)
                            if (locationBridge?.supportsDocumentStart != true) error = "请更新 Android System WebView 后使用签到测试。"
                            return
                        }
                        if (!CampusLinks.mayLocate(page, origin, web?.url.orEmpty())) { callback.invoke(origin, false, false); return }
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) callback.invoke(origin, true, false)
                        else { pendingLocation?.let { it.second.invoke(it.first, false, false) }; pendingLocation = origin to callback; permissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) }
                    }
                    override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                        files?.onReceiveValue(null); files = callback
                        filePicker.launch(params.acceptTypes.flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotBlank() }.toTypedArray().ifEmpty { arrayOf("*/*") }); return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? { diagnostics?.request(request); return documents?.intercept(request) }
                    override fun onPageCommitVisible(view: WebView, url: String) { diagnostics?.event("page-visible", com.xatune.app.platform.WebDiagnosticText.url(url)) }
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { diagnostics?.event("renderer-gone", "crashed=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()}"); return false }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (documents?.redirect(view, request) == true) return true
                        val target = request.url.toString()
                        val destination = CampusWebNavigation.destination(page, target, request.hasGesture())
                        diagnostics?.navigation(request, destination != CampusWebNavigation.Destination.BLOCKED)
                        when (destination) {
                            CampusWebNavigation.Destination.WEB -> return false
                            CampusWebNavigation.Destination.APP -> {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addCategory(Intent.CATEGORY_BROWSABLE))
                                }.onFailure { error = "没有可打开此链接的应用，请确认已安装对应应用后重试" }
                            }
                            CampusWebNavigation.Destination.BLOCKED -> error = "已停止打开不受支持的跳转地址"
                        }
                        loading = false
                        return true
                    }
                    override fun onPageStarted(view: WebView, url: String, icon: android.graphics.Bitmap?) { diagnostics?.started(url); navigation++; loading = true; error = null }
                    override fun onPageFinished(view: WebView, url: String) { diagnostics?.finished(url); loading = false; CookieManager.getInstance().flush() }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, e: WebResourceError) { diagnostics?.error(request, e); if (request.isForMainFrame) { loading = false; error = "网页加载失败，请检查网络后重试" } }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) { diagnostics?.http(request, response); if (request.isForMainFrame) { loading = false; error = "学校服务返回 HTTP ${response.statusCode}" } }
                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, sslError: android.net.http.SslError) { diagnostics?.ssl(sslError); handler.cancel(); loading = false; error = "学校网页的安全连接校验失败" }
                }
                loadUrl(url)
            } })
        }
    }
}
