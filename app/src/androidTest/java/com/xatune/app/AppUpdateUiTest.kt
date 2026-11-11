package com.xatune.app

import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.graphics.ColorUtils
import androidx.test.platform.app.InstrumentationRegistry
import com.xatune.app.model.*
import java.io.File
import android.graphics.Bitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xatune.app.data.*
import com.xatune.app.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUpdateUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun notificationRequiresConfirmationBeforeOpeningOfficialRelease() {
        var opened: String? = null
        compose.setContent {
            var offer by remember { mutableStateOf(AppRelease("v1.2.0", "改进与修复\n\n附件：\n不应显示的附件说明", "https://github.com/Kelejun/XATUHelper-Android/releases/tag/v1.2.0") as AppRelease?) }
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened = uri }
            }) {
                HelperTheme { UpdateNotification(UpdateUi(offer = offer), { offer = null }) }
            }
        }
        compose.onNodeWithText("发现新版本 v1.2.0").assertIsDisplayed()
        compose.onNodeWithText("查看更新").performClick()
        compose.onNodeWithText("改进与修复").assertIsDisplayed()
        compose.onNodeWithText("附件：", substring = true).assertDoesNotExist()
        compose.onNodeWithText("不应显示的附件说明", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertNull(opened) }
        compose.onNodeWithText("前往 GitHub 下载").performClick()
        compose.runOnIdle { assertEquals("https://github.com/Kelejun/XATUHelper-Android/releases/tag/v1.2.0", opened) }
        compose.onNodeWithText("更新至 v1.2.0").assertDoesNotExist()
    }

    @Test fun bannerColorsRemainReadableInLightAndDarkThemes() {
        var dark by mutableStateOf(false)
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                UpdateNotification(UpdateUi(offer = AppRelease("v1.2.0", "更新说明", "https://github.com/Kelejun/XATUHelper-Android/releases/tag/v1.2.0")), {})
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night }
            val colors = if (night) darkColorScheme() else lightColorScheme()
            val captured = compose.onNodeWithTag("inAppNotice").captureToImage()
            val pixels = captured.toPixelMap()
            var matchingBackground = 0
            for (x in 0 until pixels.width step 4) for (y in 0 until pixels.height step 4) {
                if (pixels[x, y].toArgb() == colors.surfaceContainerHigh.toArgb()) matchingBackground++
            }
            assertTrue("Banner uses the current theme surface", matchingBackground > 30)
            assertTrue(ColorUtils.calculateContrast(colors.onSurface.toArgb(), colors.surfaceContainerHigh.toArgb()) >= 4.5)
            assertTrue(ColorUtils.calculateContrast(colors.primary.toArgb(), colors.surfaceContainerHigh.toArgb()) >= 4.5)
            val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
            File(directory, "update-notification-${if (night) "dark" else "light"}.png").outputStream().use {
                captured.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithText("查看更新").assertIsDisplayed().assertHasClickAction()
        }
    }

    @Test fun academicBannerShowsDetailsAndCanBeOpenedOrDismissed() {
        var opened = 0
        var dismissed = 0
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            AcademicNotification(AcademicNotice(AcademicKind.GRADES, "all", "高等数学 90"), { opened++ }, { dismissed++ })
        } }
        compose.onNodeWithText("有新的成绩公布").assertIsDisplayed()
        compose.onNodeWithText("高等数学 90").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
        compose.onNodeWithContentDescription("关闭通知").performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun academicNoticeDestinationKeepsItsSemesterScope() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HelperApplication
        lateinit var model: HelperViewModel
        compose.runOnUiThread {
            model = ViewModelProvider(compose.activity, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = HelperViewModel(application.repository) as T
            })[HelperViewModel::class.java]
        }
        compose.setContent { HelperTheme { HelperApp(model) } }
        compose.waitUntil(10000) { !model.state.value.starting }
        compose.runOnIdle { model.demo() }
        compose.waitUntil(10000) { model.state.value.demo && model.state.value.table != null && !model.state.value.busy }
        compose.runOnIdle { model.openDestination("grades:notice-semester") }
        compose.waitUntil(10000) { model.state.value.academic[academicKey(AcademicKind.GRADES, "notice-semester")]?.data != null }
        compose.runOnIdle { model.openDestination("grades:another-semester") }
        compose.waitUntil(10000) { model.state.value.academic[academicKey(AcademicKind.GRADES, "another-semester")]?.data != null }
        compose.onNodeWithTag("gradeSearch").assertIsDisplayed()
    }

    @Test fun settingsShowsSharedLogsAndRunsManualCheck() {
        var checks = 0
        val logs = listOf(
            UpdateLog(1_000_000, true, UpdateChannel.RELEASE, false, "GitHub HTTP 503"),
            UpdateLog(900_000, false, UpdateChannel.PRERELEASE, true, "当前已是最新版本")
        )
        compose.setContent { HelperTheme {
            SettingsScreen(UiState(), {}, {}, {}, {}, update = UpdateUi(logs = logs), onCheckUpdates = { checks++ })
        } }
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("checkUpdates"))
        compose.onNodeWithTag("checkUpdates").performClick()
        compose.runOnIdle { assertEquals(1, checks) }
        compose.onNodeWithTag("updateLog0").performScrollTo().assertTextContains("自动检测更新失败", substring = true)
        compose.onNodeWithTag("updateLog1").performScrollTo().assertTextContains("手动检测更新成功", substring = true)
    }

    @Test fun updateOptionsSelectChannelServerAndAutomaticChecks() {
        var settings by mutableStateOf(UpdateSettings(channel = UpdateChannel.PRERELEASE))
        compose.setContent { HelperTheme {
            UpdateSettingsPanel(UpdateUi(settings = settings),
                { settings = settings.copy(channel = it) },
                { settings = settings.copy(server = it) },
                { settings = settings.copy(repositoryUrl = it) },
                { settings = settings.copy(automatic = it) }, {})
        } }
        compose.onNodeWithTag("updateChannel-prerelease").assertIsSelected()
        compose.onNodeWithTag("updateChannel-release").performClick().assertIsSelected()
        compose.onNodeWithText("其他", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("updateRepositoryUrl").performTextInput("https://mirror.example/repo")
        compose.runOnIdle { assertEquals("https://mirror.example/repo", settings.repositoryUrl) }
        compose.onNodeWithTag("automaticUpdates").performClick()
        compose.runOnIdle { assertTrue(settings.automatic) }
    }
}
