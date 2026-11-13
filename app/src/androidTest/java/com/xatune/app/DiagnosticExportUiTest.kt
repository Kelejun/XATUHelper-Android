package com.xatune.app

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.xatune.app.ui.*
import org.junit.*
import org.junit.Assert.*

class DiagnosticExportUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun shell(command: String) = automation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    @Test fun savesUtf8SnapshotThroughSystemPickerAndCancelsCleanly() {
        check(BuildConfig.APPLICATION_ID.endsWith(".qa"))
        compose.runOnUiThread {
            compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            compose.activity.setShowWhenLocked(true); compose.activity.setTurnScreenOn(true)
        }
        shell("ls /sdcard/Download").lines().filter { it.startsWith("XATune-ExportFixture-") }.forEach { shell("rm -f /sdcard/Download/$it") }
        var content = "中文诊断\n第二行 ✅"
        compose.setContent { HelperTheme { DiagnosticExport("ExportFixture") { content } } }
        compose.onNodeWithTag("exportDiagnostic:ExportFixture").performClick()
        content = "changed after export"
        var save: AccessibilityNodeInfo? = null
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            save = nodes(automation.rootInActiveWindow).firstOrNull { it.isEnabled && it.isClickable && (it.viewIdResourceName == "android:id/button1" || it.text?.toString() in listOf("保存", "SAVE", "Save")) }
            if (save != null) break
            Thread.sleep(200)
        }
        assertNotNull("System document picker save button", save)
        assertTrue(save!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        compose.waitUntil(15000) { compose.onAllNodesWithText("诊断文本已导出").fetchSemanticsNodes().isNotEmpty() }
        val filename = shell("ls /sdcard/Download").lines().single { it.startsWith("XATune-ExportFixture-") }
        assertEquals("中文诊断\n第二行 ✅", shell("cat /sdcard/Download/$filename"))
        compose.onNodeWithText("好").performClick()
        compose.onNodeWithTag("exportDiagnostic:ExportFixture").performClick()
        Thread.sleep(1000)
        shell("input keyevent 4")
        compose.waitUntil(10000) { compose.onAllNodesWithTag("exportDiagnostic:ExportFixture").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("exportDiagnostic:ExportFixture").assertIsEnabled()
        compose.onNodeWithText("无法导出诊断").assertDoesNotExist()
        shell("ls /sdcard/Download").lines().filter { it.startsWith("XATune-ExportFixture-") }.forEach { shell("rm -f /sdcard/Download/$it") }
    }
}
