package com.xatune.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.xatune.app.model.*
import com.xatune.app.ui.*
import org.junit.*
import java.math.BigDecimal
import java.time.LocalDate

class ElectricUsageUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun longPressAt250msReadsRechargeDragChangesDayAndReleaseClears() {
        wakeQaDevice()
        compose.runOnUiThread { compose.activity.setShowWhenLocked(true); compose.activity.setTurnScreenOn(true); compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val today = LocalDate.parse("2026-11-13")
        val points = listOf(ElectricUsage(today.minusDays(29), BigDecimal("1.25"), true, estimated = true), ElectricUsage(today, BigDecimal("1.25"), false))
        compose.setContent { HelperTheme { ElectricUsageChart(points, today) } }
        compose.onNodeWithTag("electricUsageChart").performTouchInput { down(centerLeft); advanceEventTime(275) }
        compose.mainClock.advanceTimeBy(300)
        compose.onRoot().printToLog("ElectricUsageTest")
        compose.onNodeWithText("有电费充值").assertIsDisplayed()
        compose.onNodeWithText("平均用量估算").assertIsDisplayed()
        compose.onNodeWithText("2026-10-15 · 1.25 度").assertIsDisplayed()
        compose.onNodeWithTag("electricUsageChart").performTouchInput { moveTo(centerRight); advanceEventTime(50) }
        compose.onNodeWithText("2026-11-13 · 1.25 度").assertIsDisplayed()
        compose.onNodeWithTag("electricUsageChart").performTouchInput { up() }
        compose.onNodeWithTag("electricUsageReading").assertDoesNotExist()
    }
    @Test fun rechargeWithoutReferenceShowsUnknownInsteadOfZero() {
        wakeQaDevice()
        compose.runOnUiThread { compose.activity.setShowWhenLocked(true); compose.activity.setTurnScreenOn(true); compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val today = LocalDate.parse("2026-11-13")
        val points = electricUsage(listOf(DailyBalance("2026-11-10", "10"), DailyBalance("2026-11-13", "20")), today)
        compose.setContent { HelperTheme { ElectricUsageChart(points, today) } }
        compose.onNodeWithTag("electricUsageChart").performTouchInput { down(centerRight); advanceEventTime(275) }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("2026-11-13 · 暂无用量估算").assertIsDisplayed()
        compose.onNodeWithText("有电费充值").assertIsDisplayed()
        compose.onNodeWithTag("electricUsageChart").performTouchInput { up() }
        compose.onNodeWithTag("electricUsageReading").assertDoesNotExist()
    }

    @Test fun rechargeAfterOverdrawnBalanceStillShowsReferenceEstimate() {
        wakeQaDevice()
        compose.runOnUiThread { compose.activity.setShowWhenLocked(true); compose.activity.setTurnScreenOn(true); compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val today = LocalDate.parse("2026-11-13")
        val points = electricUsage(listOf(DailyBalance("2026-11-11", "1"), DailyBalance("2026-11-12", "-1"), DailyBalance("2026-11-13", "9")), today)
        compose.setContent { HelperTheme { ElectricUsageChart(points, today) } }
        compose.onNodeWithTag("electricUsageChart").performTouchInput { down(centerRight); advanceEventTime(275) }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("2026-11-13 · 2.00 度").assertIsDisplayed()
        compose.onNodeWithText("平均用量估算").assertIsDisplayed()
        compose.onNodeWithText("有电费充值").assertIsDisplayed()
        compose.onNodeWithTag("electricUsageChart").performTouchInput { up() }
    }

}
