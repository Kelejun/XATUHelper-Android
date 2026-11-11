package com.xatune.app

import kotlinx.coroutines.launch
import android.app.Application
import android.webkit.CookieManager
import com.xatune.app.data.*

class HelperApplication : Application() {
    lateinit var store: LocalStore
        private set
    lateinit var campusRepository: CampusRepository
        private set
    lateinit var repository: TimetableRepository
        private set
    internal lateinit var cookies: SchoolCookies
        private set
    val updates by lazy { AppUpdateRepository(UpdatePreferences(this)) }
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        val vault = SecretVault(this)
        cookies = SchoolCookies(CookieManager.getInstance().apply { setAcceptCookie(true) }, vault)
        store = LocalStore(this)
        repository = TimetableRepository(store, vault, cookies, SchoolNetwork(cookies))
        campusRepository = CampusRepository(this, store, repository, PayNetwork(cookies))
        com.xatune.app.platform.SystemCoordinator.maintain(this)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            com.xatune.app.platform.UpdateScheduler.synchronize(this@HelperApplication)
        }
    }
}
