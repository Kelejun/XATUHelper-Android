package com.xatune.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.xatune.app.data.DailyStatistics
import com.xatune.app.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DailyStatisticsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun disablingStatisticsPersistsAcrossClientRestart() {
        check(BuildConfig.APPLICATION_ID.endsWith(".qa"))
        wakeQaDevice()
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HelperApplication
        val previous = app.statistics.enabled
        try {
            app.statistics.enabled = true
            compose.runOnUiThread {
                compose.activity.setShowWhenLocked(true)
                compose.activity.setTurnScreenOn(true)
            }
            compose.setContent { HelperTheme { SettingsScreen(UiState(), {}, {}, {}, {}) } }
            compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("dailyStatisticsSetting"))
            compose.onNodeWithTag("dailyStatisticsSetting").assertIsOn().performClick()
            compose.onNodeWithTag("dailyStatisticsSetting").assertIsOff()
            assertFalse(DailyStatistics(app).enabled)
            compose.onNodeWithTag("dailyStatisticsSetting").performClick().assertIsOn()
            assertTrue(DailyStatistics(app).enabled)
        } finally {
            app.statistics.enabled = previous
        }
    }
}
