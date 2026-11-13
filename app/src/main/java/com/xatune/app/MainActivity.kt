package com.xatune.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.xatune.app.ui.*

class MainActivity : ComponentActivity() {
    private lateinit var helperModel: HelperViewModel
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        helperModel.openDestination(intent.getStringExtra("open_page"))
        intent.removeExtra("open_page")
    }
    override fun onStart() { super.onStart(); if (::helperModel.isInitialized) helperModel.checkUpdates() }
    override fun onStop() { if (::helperModel.isInitialized) helperModel.pauseAutomaticUpdates(); super.onStop() }
    override fun onResume() { super.onResume(); if (::helperModel.isInitialized) helperModel.resume() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Compose owns both palettes. OEM automatic darkening can invert text independently
        // of its container, especially on cached layers during page transitions.
        if (android.os.Build.VERSION.SDK_INT >= 29) window.decorView.isForceDarkAllowed = false
        val model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = HelperViewModel((application as HelperApplication).repository, (application as HelperApplication).campusRepository, updates = (application as HelperApplication).updates, updateSchedule = { com.xatune.app.platform.UpdateScheduler.synchronize(application) }) { com.xatune.app.platform.SystemCoordinator.synchronize(application) } as T
        })[HelperViewModel::class.java]
        helperModel = model
        model.openDestination(intent.getStringExtra("open_page")); intent.removeExtra("open_page")
        val announcements = ViewModelProvider(this)[AnnouncementViewModel::class.java]
        lifecycle.addObserver(announcements)
        setContent { HelperTheme { HelperApp(model, announcements) } }
    }
}
