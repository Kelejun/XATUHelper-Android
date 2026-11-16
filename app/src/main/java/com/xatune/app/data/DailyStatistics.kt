package com.xatune.app.data

import android.content.Context
import com.google.gson.JsonParser
import com.xatune.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal fun statisticsDay(now: Long): String = Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString()

internal fun dailyStatisticsToken(secret: ByteArray, day: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret, "HmacSHA256"))
    return mac.doFinal("xatune:android:daily:v1:$day".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

internal interface StatisticsStore {
    var enabled: Boolean
    var sent: String
    fun secret(): ByteArray
}

internal class AndroidStatisticsStore(context: Context) : StatisticsStore {
    private val preferences = context.getSharedPreferences("daily_statistics", Context.MODE_PRIVATE)
    private val seed = File(context.noBackupFilesDir, "daily-statistics-seed")
    override var enabled: Boolean
        get() = preferences.getBoolean("enabled", true)
        set(value) { preferences.edit().putBoolean("enabled", value).commit() }
    override var sent: String
        get() = preferences.getString("sent", "").orEmpty()
        set(value) { preferences.edit().putString("sent", value).commit() }
    override fun secret(): ByteArray {
        if (!seed.exists()) {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            seed.outputStream().use { it.write(bytes); it.fd.sync() }
        }
        return seed.readBytes().also { if (it.size != 32) throw java.io.IOException("Invalid statistics seed") }
    }
}

internal data class StatisticsReply(val code: Int, val serverTime: Long? = null)

internal class DailyStatisticsReporter(
    private val store: StatisticsStore,
    private val commit: String,
    private val eligible: Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val send: (String, String, String) -> StatisticsReply
) {
    private val mutex = Mutex()
    private var offset = 0L
    private var attemptKey = ""
    private var attempts = 0
    private var nextAttempt = 0L

    suspend fun tick() = mutex.withLock {
        if (!eligible || !store.enabled || !Regex("[0-9a-f]{7}").matches(commit)) return@withLock
        val day = statisticsDay(now() + offset)
        val key = "$day:$commit"
        if (store.sent == key) return@withLock
        if (attemptKey != key) { attemptKey = key; attempts = 0; nextAttempt = 0 }
        if (attempts >= 3 || now() < nextAttempt) return@withLock
        attempts++
        nextAttempt = now() + if (attempts == 1) 30_000 else 120_000
        try {
            val token = dailyStatisticsToken(store.secret(), day)
            if (!store.enabled) return@withLock
            val response = send(day, token, commit)
            if (response.code == 200) store.sent = key
            if (response.code == 409 && response.serverTime != null) {
                offset = response.serverTime - now()
            }
        } catch (_: java.io.IOException) {
            // Foreground checks retry with a bounded budget. No request data is logged.
        }
    }
}

class DailyStatistics(context: Context) {
    private val store = AndroidStatisticsStore(context)
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS).build()
    private val reporter = DailyStatisticsReporter(store, BuildConfig.GIT_COMMIT.take(7),
        BuildConfig.BUILD_TYPE == "release" && BuildConfig.APPLICATION_ID == "com.xatune.app"
    ) { day, token, commit ->
        val body = """{"day":"$day","daily_token":"$token","build_commit":"$commit"}"""
        val request = Request.Builder().url("https://api.xatune.cn/v1/analytics/daily")
            .header("User-Agent", "XATune-Statistics/1")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val time = if (response.code == 409) runCatching {
                JsonParser.parseString(response.body?.string()).asJsonObject.get("server_time").asLong
            }.getOrNull() else null
            StatisticsReply(response.code, time)
        }
    }
    var enabled: Boolean
        get() = store.enabled
        set(value) { store.enabled = value }

    suspend fun whileForeground() {
        while (true) {
            withContext(Dispatchers.IO) { reporter.tick() }
            delay(30_000)
        }
    }
}
