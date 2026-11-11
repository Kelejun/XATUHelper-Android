package com.xatune.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xatune.app.data.ReceivedAnnouncement
import com.xatune.app.ui.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AnnouncementUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = MockWebServer()
    private val store = ViewModelStore()
    private val preferencesName = "announcements.ui-test"
    private lateinit var baseUrl: String
    private lateinit var model: AnnouncementViewModel
    private val application get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HelperApplication
    private val first = "12345678-1234-1234-1234-123456789abc"
    private val second = "12345678-1234-1234-1234-123456789abd"
    @Before fun setup() {
        check(BuildConfig.APPLICATION_ID.endsWith(".qa"))
        application.getSharedPreferences(preferencesName, 0).edit().clear().commit()
        server.start()
        baseUrl = server.url("/").toString()
        compose.runOnUiThread {
            compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            model = AnnouncementViewModel(application, baseUrl, preferencesName)
            store.put("announcements", model)
        }
    }
    @After fun cleanup() {
        compose.runOnUiThread { store.clear() }
        server.shutdown()
        application.getSharedPreferences(preferencesName, 0).edit().clear().commit()
    }
    private fun feed(): String {
        val body = "段落 **粗体** *斜体* [链接](https://example.com)\\n\\n".repeat(60)
        return """{"server_time":300,"announcements":[{"id":"$first","title":"第一条公告","body":"# 标题\n\n$body","published_at":100,"expires_at":null,"platforms":["android"]},{"id":"$second","title":"第二条公告","body":"第二条内容","published_at":200,"expires_at":null,"platforms":["android"]}]}"""
    }
    private fun refresh() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(feed()))
        compose.runOnUiThread { model.refresh() }
        compose.waitUntil(10000) { model.history.value.size == 2 }
    }
    @Test fun automaticQueueKeepsConfirmationVisibleForLongContentAndPersistsReadState() {
        compose.setContent { HelperTheme { AnnouncementNotification(model) } }
        refresh()
        compose.onNodeWithText("第一条公告").assertIsDisplayed()
        compose.onNodeWithTag("announcementConfirm").assertIsDisplayed()
        screenshot("announcement-long-body.png")
        compose.onNodeWithTag("announcementConfirm").performClick()
        compose.onNodeWithText("第二条公告").assertIsDisplayed()
        compose.onNodeWithTag("announcementConfirm").performClick()
        compose.onNodeWithTag("announcementDetail").assertDoesNotExist()
        compose.runOnIdle { assertTrue(model.pending.value.isEmpty()); assertEquals(2, model.history.value.size) }
        compose.runOnUiThread {
            model = AnnouncementViewModel(application, baseUrl, preferencesName)
            store.put("announcements", model)
            model.refresh()
        }
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(feed()))
        compose.waitUntil(10000) { server.requestCount == 2 }
        compose.runOnIdle { assertEquals(2, model.history.value.size); assertTrue(model.pending.value.isEmpty()) }
    }
    @Test fun historySupportsSwipeDeleteReReceiptAndConfirmedClear() {
        compose.setContent {
            var selected by remember { mutableStateOf<ReceivedAnnouncement?>(null) }
            HelperTheme {
                AnnouncementHistoryScreen(model, {}, { selected = it })
                selected?.let { record -> AnnouncementDetail(record.announcement, record.receivedAt, model) { model.acknowledge(record); selected = null } }
            }
        }
        refresh()
        compose.onNodeWithText("第二条公告").performClick()
        compose.onNodeWithTag("announcementConfirm").performClick()
        compose.onNodeWithTag("announcementHistoryRow:" + second).performTouchInput { swipeLeft() }
        compose.waitUntil(5000) { model.history.value.size == 1 }
        compose.runOnIdle { assertEquals(first, model.latest!!.announcement.id) }
        refresh()
        compose.waitUntil(5000) { model.pending.value.any { it.id == second } }
        compose.onNodeWithText("清除本地所有公告").performScrollTo().performClick()
        compose.onNodeWithText("暂不清除").performClick()
        compose.runOnIdle { assertEquals(2, model.history.value.size) }
        screenshot("announcement-history.png")
        compose.onNodeWithText("清除本地所有公告").performClick()
        compose.onNodeWithText("清除", useUnmergedTree = true).performClick()
        compose.onNodeWithText("暂无历史公告").assertIsDisplayed()
        compose.onNodeWithText("清除本地所有公告").assertDoesNotExist()
        refresh()
        compose.waitUntil(5000) { model.pending.value.size == 2 }
    }
    @Test fun settingsAndHistoryDelayAutomaticNoticesUntilClosed() {
        lateinit var helper: HelperViewModel
        compose.runOnUiThread {
            helper = HelperViewModel(application.repository)
            store.put("helper", helper)
        }
        compose.setContent { HelperTheme { HelperApp(helper, model) } }
        compose.waitUntil(10000) { !helper.state.value.starting }
        compose.runOnUiThread { helper.demo() }
        compose.waitUntil(10000) { helper.state.value.demo && helper.state.value.table != null && !helper.state.value.busy }
        compose.runOnUiThread { helper.openDestination("settings") }
        compose.onNodeWithTag("settings").assertIsDisplayed()
        refresh()
        compose.onNodeWithTag("announcementDetail").assertDoesNotExist()
        compose.onNodeWithText("最新公告").assertIsDisplayed()
        compose.onNodeWithText("第二条公告").performClick()
        compose.onNodeWithTag("announcementDetail").assertIsDisplayed()
        compose.onNodeWithTag("announcementConfirm").performClick()
        compose.onNodeWithTag("announcementDetail").assertDoesNotExist()
        screenshot("announcement-settings.png")
        compose.onNodeWithText("历史公告").performClick()
        compose.onNodeWithTag("announcementHistory").assertIsDisplayed()
        compose.onNodeWithTag("announcementDetail").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNodeWithContentDescription("返回校园").performClick()
        compose.onNodeWithText("第一条公告").assertIsDisplayed()
        compose.onNodeWithTag("announcementConfirm").performClick()
    }
    @Test fun realCoverPersistsAcrossRestartAndSharedReferencesAreCleaned() {
        val url = server.url("/cover.png").toString()
        val bitmap = android.graphics.Bitmap.createBitmap(80, 40, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        val bytes = java.io.ByteArrayOutputStream().use { output ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(feed().replace("\"platforms\"", "\"image_url\":\"$url\",\"platforms\"")))
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(okio.Buffer().write(bytes)))
        compose.setContent { HelperTheme { AnnouncementNotification(model) } }
        compose.runOnUiThread { model.refresh() }
        compose.waitUntil(10000) { model.cachedImage(url) != null }
        compose.onNodeWithContentDescription("公告封面").assertIsDisplayed()
        screenshot("announcement-cover.png")
        compose.runOnUiThread {
            model = AnnouncementViewModel(application, baseUrl, preferencesName)
            store.put("announcements", model)
        }
        compose.runOnIdle { assertNotNull(model.cachedImage(url)); assertEquals(2, model.history.value.size) }
        compose.runOnUiThread { model.delete(model.history.value.first()) }
        compose.runOnIdle { assertNotNull(model.cachedImage(url)) }
        compose.runOnUiThread { model.clearHistory() }
        compose.runOnIdle { assertNull(model.cachedImage(url)); assertTrue(model.history.value.isEmpty()) }
    }
    @Test fun busyRefreshTriggersAreCoalescedIntoOneFollowUpRequest() {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                started.countDown()
                check(finish.await(5, TimeUnit.SECONDS))
                return MockResponse().setHeader("Content-Type", "application/json").setBody(feed())
            }
        }
        compose.runOnUiThread { model.refresh() }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            compose.runOnUiThread { repeat(5) { model.refresh() } }
        } finally { finish.countDown() }
        compose.waitUntil(10000) { server.requestCount == 2 && model.history.value.size == 2 }
        compose.runOnIdle { assertEquals(2, server.requestCount) }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val tag = when (name) {
            "announcement-settings.png" -> "settings"
            "announcement-history.png" -> "announcementHistory"
            else -> "announcementDetail"
        }
        val image = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        File(application.getExternalFilesDir(null), name).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
