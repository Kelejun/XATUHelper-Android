package com.xatune.app

import android.graphics.Bitmap
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.xatune.app.model.*
import com.xatune.app.platform.*
import com.xatune.app.ui.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class CheckinLocationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var preferences: CheckinLocationPreferences
    private var backup = emptyMap<String, Any?>()
    private var browser: WebView? = null
    private var bridge: CheckinLocationBridge? = null

    @Before fun prepareQaOnly() {
        check(BuildConfig.APPLICATION_ID.endsWith(".qa"))
        preferences = CheckinLocationPreferences(compose.activity)
        backup = preferences.preferences.all.toMap()
        preferences.preferences.edit().clear().commit()
        compose.runOnUiThread {
            compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (android.os.Build.VERSION.SDK_INT >= 27) { compose.activity.setShowWhenLocked(true); compose.activity.setTurnScreenOn(true) }
        }
        // The first activity can still be waiting for MIUI's screen-on transition.
        // Wait for the real window before replacing its Compose content.
        compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
        compose.waitForIdle()
    }
    @After fun restoreQaSettings() {
        compose.runOnUiThread { bridge?.close(); browser?.apply { stopLoading(); destroy() } }
        preferences.preferences.edit().clear().apply {
            backup.forEach { (key, value) -> when (value) { is Boolean -> putBoolean(key, value); is String -> putString(key, value) } }
        }.commit()
    }

    @Test fun hiddenEntryCoordinatesValidationAndPersistentToggleMatchIos() {
        var picker by mutableStateOf(false)
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalMapNetworkEnabled provides false) { HelperTheme {
                if (picker) CheckinLocationPicker { picker = false }
                else SettingsScreen(UiState(demo = true), {}, {}, {}, {}, onCheckinLocation = { picker = true })
            } }
        } }
        compose.onNodeWithTag("checkinLocationEnabled").assertDoesNotExist()
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("appVersion"))
        repeat(4) { compose.onNodeWithTag("appVersion").performClick() }
        assertFalse(preferences.read().revealed)
        compose.onNodeWithTag("appVersion").performClick()
        assertTrue(preferences.read().revealed)
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("checkinLocationEnabled"))
        compose.onNodeWithTag("checkinLocationEnabled").assertIsNotEnabled()
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("checkinLocationPicker"))
        compose.onNodeWithTag("checkinLocationPicker").performClick()
        compose.onNodeWithTag("checkinLatitude").performScrollTo().performTextReplacement("91")
        compose.onNodeWithTag("saveCheckinLocation").assertIsNotEnabled()
        compose.onNodeWithTag("checkinLatitude").performTextReplacement("34.383123")
        compose.onNodeWithTag("checkinLongitude").performScrollTo().performTextReplacement("108.981456")
        compose.onNodeWithTag("coordinateSystem").performScrollTo().performClick()
        compose.onNodeWithText("GPS（WGS84）").performClick()
        shot("location-picker.png")
        compose.onNodeWithTag("saveCheckinLocation").performClick()
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("checkinLocationEnabled"))
        compose.onNodeWithTag("checkinLocationEnabled").assertIsEnabled().performClick()
        assertTrue(preferences.read().enabled)
        val saved = CheckinLocationPreferences(compose.activity).read().coordinate
        assertEquals(CheckinCoordinate(34.383123, 108.981456, CoordinateSystem.WGS84), saved)
        shot("location-settings.png")
        compose.onNodeWithTag("checkinLocationEnabled").performClick()
        assertFalse(preferences.read().enabled)
        assertEquals(saved, preferences.read().coordinate)
    }

    @Test fun currentLocationFillsGpsCoordinatesAndSavesOnlyAfterConfirmation() {
        val expected = CheckinCoordinate(34.385123, 108.975456, CoordinateSystem.WGS84)
        var calls = 0
        var finished = false
        val source = object : CurrentLocationSource {
            override val hasPermission = true
            override suspend fun locate(): LocatedPoint { calls++; return LocatedPoint(expected, 8f) }
        }
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalMapNetworkEnabled provides false) { HelperTheme { CheckinLocationPicker(source) { finished = true } } }
        } }
        compose.onNodeWithTag("useCurrentLocation").performScrollTo().performClick()
        compose.waitUntil(5000) { calls == 1 }
        compose.onNodeWithTag("checkinLatitude").performScrollTo().assertTextContains("34.385123", substring = true)
        compose.onNodeWithTag("checkinLongitude").performScrollTo().assertTextContains("108.975456", substring = true)
        compose.onNodeWithTag("coordinateSystem").performScrollTo().assertTextEquals("GPS（WGS84）")
        assertNull("Fetching location must not silently save or enable testing", preferences.read().coordinate)
        assertFalse(preferences.read().enabled)
        shot("location-current-filled.png")
        compose.onNodeWithTag("saveCheckinLocation").performClick()
        assertTrue(finished)
        assertEquals(expected, preferences.read().coordinate)
        assertFalse(preferences.read().enabled)
    }

    @Test fun failedLocationKeepsEnteredCoordinatesAndOffersRetry() {
        val source = object : CurrentLocationSource {
            override val hasPermission = true
            override suspend fun locate(): LocatedPoint = throw LocationRequestException("系统定位未开启，请在系统设置中开启定位。")
        }
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalMapNetworkEnabled provides false) { HelperTheme { CheckinLocationPicker(source) {} } }
        } }
        compose.onNodeWithTag("useCurrentLocation").performScrollTo().performClick()
        compose.onNodeWithText("系统定位未开启，请在系统设置中开启定位。").assertIsDisplayed()
        compose.onNodeWithTag("useCurrentLocation").assertIsEnabled()
        compose.onNodeWithTag("checkinLatitude").performScrollTo().assertTextContains("34.383000", substring = true)
        assertNull(preferences.read().coordinate)
    }

    @Test fun leavingPickerCancelsPendingCurrentLocationRequest() {
        var displayed by mutableStateOf(true)
        var started = false
        var cancelled = false
        val source = object : CurrentLocationSource {
            override val hasPermission = true
            override suspend fun locate(): LocatedPoint {
                started = true
                try { kotlinx.coroutines.awaitCancellation() } finally { cancelled = true }
            }
        }
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalMapNetworkEnabled provides false) { HelperTheme { if (displayed) CheckinLocationPicker(source) {} } }
        } }
        compose.onNodeWithTag("useCurrentLocation").performScrollTo().performClick()
        compose.waitUntil(5000) { started }
        compose.onNodeWithTag("saveCheckinLocation").assertIsNotEnabled()
        compose.runOnIdle { displayed = false }
        compose.waitUntil(5000) { cancelled }
        assertNull(preferences.read().coordinate)
    }

    @Test fun realWebViewRepliesToEachFrameAndRechecksLocationSource() {
        Assume.assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        preferences.save(CheckinCoordinate(34.38, 108.97, CoordinateSystem.WGS84)); preferences.enable(true)
        val results = CopyOnWriteArrayList<String>()
        var topUrl = "https://ehall.xatu.edu.cn/xsfw/sys/swmqdzsapp/index.html"
        compose.runOnUiThread {
            val web = WebView(compose.activity).apply { settings.javaScriptEnabled = true }; browser = web
            val native = CheckinLocationBridge(web) { topUrl }; bridge = native
            // The connected legacy WebView lacks document-start injection. Place the same
            // production script at document start in each fixture frame, then exercise its
            // real native message routing. No location permission or live school request.
            native.attach(injectAtDocumentStart = false)
            val status = "documentStart=${native.supportsDocumentStart}\nwebView=${WebViewCompat.getCurrentWebViewPackage(compose.activity)?.versionName}\n"
            File(compose.activity.getExternalFilesDir(null), "checkin-webview-capabilities.txt").writeText(status)
            WebViewCompat.addWebMessageListener(web, "TestResults", setOf("*")) { _, message, _, _, _ -> results.add(message.data.orEmpty()) }
            val fake = "navigator.geolocation.getCurrentPosition=function(success){if(window.holdNative)window.pendingNative=success;else success({coords:{latitude:6,longitude:7},timestamp:Date.now()});};"
            fun request(label: String) = "navigator.geolocation.getCurrentPosition(function(p){TestResults.postMessage('$label:'+p.coords.latitude+','+p.coords.longitude);});"
            val child = "<script>$fake${native.source}${request("child")}var watch=navigator.geolocation.watchPosition(function(p){TestResults.postMessage('watch:'+p.coords.latitude+','+p.coords.longitude);navigator.geolocation.clearWatch(watch);});</script>"
            val quoted = JSONObject.quote(child).replace("<", "\\u003c")
            val html = "<html><body><script>$fake${native.source}${request("top")}var frame=document.createElement('iframe');frame.sandbox='allow-scripts';frame.srcdoc=$quoted;document.body.appendChild(frame);</script></body></html>"
            compose.activity.setContent { AndroidView(factory = { web }) }
            web.loadDataWithBaseURL(topUrl, html, "text/html", "UTF-8", null)
        }
        compose.waitUntil(15000) { results.containsAll(listOf("top:34.38,108.97", "child:34.38,108.97", "watch:34.38,108.97")) }
        preferences.enable(false)
        evaluate("navigator.geolocation.getCurrentPosition(function(p){TestResults.postMessage('native:'+p.coords.latitude);});")
        compose.waitUntil(5000) { "native:6" in results }
        evaluate("window.holdNative=true;navigator.geolocation.getCurrentPosition(function(p){TestResults.postMessage('pending:'+p.coords.latitude);});")
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        compose.waitUntil(5000) { compose.runOnUiThread { browser?.evaluateJavascript("!!window.pendingNative") { ready.set(it == "true") } }; ready.get() }
        preferences.enable(true)
        evaluate("pendingNative({coords:{latitude:6,longitude:7}});")
        compose.waitUntil(5000) { "pending:34.38" in results }
        preferences.preferences.edit().remove("coordinate").commit()
        evaluate("navigator.geolocation.getCurrentPosition(function(){TestResults.postMessage('unexpected');},function(e){TestResults.postMessage('error:'+e.code);});")
        compose.waitUntil(5000) { "error:2" in results }
        assertFalse("Invalid custom coordinates must never silently return actual GPS", "unexpected" in results)
        preferences.save(CheckinCoordinate(34.38, 108.97, CoordinateSystem.WGS84))
        topUrl = "https://example.com/xsfw/sys/swmqdzsapp/"
        evaluate("window.holdNative=false;navigator.geolocation.getCurrentPosition(function(p){TestResults.postMessage('outside:'+p.coords.latitude);});")
        compose.waitUntil(5000) { "outside:6" in results }
        Thread.sleep(3200)
        assertEquals("Clearing a frame watch stops future callbacks", 1, results.count { it.startsWith("watch:") })
    }

    @Test fun launcherVectorsPreserveCapAndTassel() {
        val context = compose.activity
        val cap = context.getDrawable(R.drawable.ic_launcher_cap)!!
        val target = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        cap.setBounds(0, 0, 300, 300)
        cap.draw(android.graphics.Canvas(target))
        for ((x, y) in listOf(150 to 130, 150 to 190, 218 to 200)) {
            assertTrue("Cap, base and tassel must remain opaque", android.graphics.Color.alpha(target.getPixel(x, y)) > 240)
        }
        assertEquals("Space around the cap remains transparent", 0, android.graphics.Color.alpha(target.getPixel(90, 200)))
        target.recycle()
        val normal = context.packageManager.getApplicationIcon(context.packageName)
        assertEquals(R.mipmap.ic_launcher, context.applicationInfo.icon)
        val background = (normal as android.graphics.drawable.AdaptiveIconDrawable).background as android.graphics.drawable.ColorDrawable
        assertEquals("Launcher retains its original background palette", context.getColor(R.color.ic_launcher_background), background.color)
        val preview = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        normal.setBounds(0, 0, 432, 432); normal.draw(android.graphics.Canvas(preview))
        File(context.getExternalFilesDir(null), "launcher-icon-preview.png").outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
        preview.recycle()
    }
    private fun evaluate(script: String) { compose.runOnUiThread { browser?.evaluateJavascript(script, null) } }
    private fun shot(name: String) {
        compose.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        image.recycle()
    }
}
