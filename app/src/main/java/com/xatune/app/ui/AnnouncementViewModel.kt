package com.xatune.app.ui

import android.app.Application
import androidx.lifecycle.*
import com.xatune.app.BuildConfig
import com.xatune.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

typealias Announcement = com.xatune.app.data.Announcement

class AnnouncementViewModel @JvmOverloads constructor(application: Application, baseUrl: String = BuildConfig.ANNOUNCEMENTS_BASE_URL, preferencesName: String = "announcements") : AndroidViewModel(application), DefaultLifecycleObserver {
    val pending = MutableStateFlow<List<Announcement>>(emptyList())
    private val preferences = application.getSharedPreferences(preferencesName, 0)
    private val client = OkHttpClient.Builder().followSslRedirects(false).callTimeout(12, TimeUnit.SECONDS).build()
    private val feed = AnnouncementClient(client, baseUrl, BuildConfig.DEBUG)
    private val source = feed.endpoint?.toString().orEmpty()
    private val ledger = AnnouncementHistory(AnnouncementCodec.decode(preferences.getString("history.v1", "[]")!!))
    val history = MutableStateFlow(ledger.records)
    val imageRevision = MutableStateFlow(0)
    private val images = AnnouncementImages(File(application.filesDir, "AnnouncementImages"), client, BuildConfig.DEBUG)
    private var job: Job? = null
    private var expiry: Job? = null
    private var refreshRequested = false
    val latest: ReceivedAnnouncement? get() = ledger.latest
    init {
        // Old versions only stored read IDs. Retain read state only when history exists.
        val edit = preferences.edit()
        for ((key, value) in preferences.all) {
            if (value is Set<*>) {
                val recordSource = if (key.startsWith("seen:")) key.removePrefix("seen:") else
                    key.toHttpUrlSource()
                val ids = ledger.records.filter { it.source == recordSource }.map { it.announcement.id }.toSet()
                edit.remove(key).putStringSet("seen:" + recordSource, value.filterIsInstance<String>().toSet().intersect(ids))
            }
        }
        edit.apply()
        synchronizeImages()
    }
    private fun String.toHttpUrlSource(): String = trimEnd('/') + "/v1/announcements?platform=android"
    override fun onStart(owner: LifecycleOwner) { refresh() }
    fun refresh() {
        if (feed.endpoint == null) return
        if (job?.isActive == true) { refreshRequested = true; return }
        job = viewModelScope.launch {
            do {
                refreshRequested = false
                try {
                    val (items, serverTime) = withContext(Dispatchers.IO) { feed.fetch() }
                    ledger.receive(source, items, System.currentTimeMillis())
                    persist()
                    val seen = preferences.getStringSet("seen:" + source, emptySet()).orEmpty()
                    pending.value = items.filter { it.id !in seen }
                    expiry?.cancel()
                    val received = android.os.SystemClock.elapsedRealtime()
                    expiry = viewModelScope.launch {
                        while (isActive) {
                            val next = pending.value.mapNotNull { it.expiresAt }.minOrNull() ?: break
                            val now = serverTime + android.os.SystemClock.elapsedRealtime() - received
                            delay((next - now).coerceIn(1, 86400000))
                            val current = serverTime + android.os.SystemClock.elapsedRealtime() - received
                            pending.value = pending.value.filter { (it.expiresAt ?: Long.MAX_VALUE) > current }
                        }
                    }
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { pending.value = emptyList(); expiry?.cancel() }
            } while (refreshRequested)
            // The request lock covers feed requests only; image downloads run independently.
            synchronizeImages()
        }
    }
    private fun persist() {
        history.value = ledger.records
        preferences.edit().putString("history.v1", AnnouncementCodec.encode(ledger.records)).apply()
    }
    fun acknowledge(record: ReceivedAnnouncement) {
        val key = "seen:" + record.source
        preferences.edit().putStringSet(key, preferences.getStringSet(key, emptySet()).orEmpty() + record.announcement.id).apply()
        if (record.source == source) pending.value = pending.value.filterNot { it.id == record.announcement.id }
    }
    fun dismiss(id: String) { ledger.records.firstOrNull { it.source == source && it.announcement.id == id }?.let(::acknowledge) }
    fun delete(record: ReceivedAnnouncement) {
        val key = "seen:" + record.source
        preferences.edit().putStringSet(key, preferences.getStringSet(key, emptySet()).orEmpty() - record.announcement.id).apply()
        ledger.delete(record.key)
        if (record.source == source) pending.value = pending.value.filterNot { it.id == record.announcement.id }
        persist(); synchronizeImages()
    }
    fun clearHistory() {
        val edit = preferences.edit()
        preferences.all.keys.filter { it.startsWith("seen:") }.forEach { edit.remove(it) }
        edit.apply()
        expiry?.cancel(); ledger.clear(); pending.value = emptyList()
        persist(); synchronizeImages()
    }
    fun cachedImage(url: String): File? = images.cached(url)
    private fun synchronizeImages() {
        val urls = ledger.records.mapNotNull { it.announcement.imageUrl }.toSet()
        val token = images.retain(urls)
        imageRevision.value++
        viewModelScope.launch { images.download(urls, token); imageRevision.value++ }
    }
}
