package com.xatune.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.xatune.app.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class InformationSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HelperApplication
    private val models = ViewModelStore()
    @After fun cleanup() { compose.runOnUiThread { models.clear() }; runBlocking { app.store.clear() } }
    @Test fun reducedFrequencyDefaultsOffAndPersistsAcrossModelRestart() {
        wakeQaDevice()
        runBlocking { app.store.clear() }
        lateinit var model: HelperViewModel
        compose.runOnUiThread {
            compose.activity.setShowWhenLocked(true); compose.activity.setTurnScreenOn(true)
            compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            model = HelperViewModel(app.repository); models.put("first", model)
        }
        compose.waitUntil(10000) { !model.state.value.starting }
        compose.setContent { val state by model.state.collectAsState(); HelperTheme { SettingsScreen(state, {}, {}, {}, {}, onReducedInformationUpdates = model::reducedInformationUpdates) } }
        compose.onNodeWithTag("reduceInformationUpdates").performScrollTo().assertIsOff().performClick()
        compose.waitUntil(5000) { runBlocking { app.store.extra("reduceAcademicRefreshFrequency", Boolean::class.javaObjectType) == true } }
        compose.onNodeWithTag("reduceInformationUpdates").assertIsOn()
        lateinit var restored: HelperViewModel
        compose.runOnUiThread { restored = HelperViewModel(app.repository); models.put("restored", restored) }
        compose.waitUntil(10000) { !restored.state.value.starting }
        assertTrue(restored.state.value.reducedInformationUpdates)
        compose.onNodeWithTag("reduceInformationUpdates").performClick()
        compose.waitUntil(5000) { runBlocking { app.store.extra("reduceAcademicRefreshFrequency", Boolean::class.javaObjectType) == false } }
        compose.onNodeWithTag("reduceInformationUpdates").assertIsOff()
    }
}
