package com.xatune.app

import androidx.test.platform.app.InstrumentationRegistry

internal fun wakeQaDevice() {
    check(BuildConfig.APPLICATION_ID.endsWith(".qa"))
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    for (command in listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard")) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }
}
