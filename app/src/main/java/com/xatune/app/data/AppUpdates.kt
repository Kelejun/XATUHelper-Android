package com.xatune.app.data

import android.annotation.SuppressLint
import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.xatune.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

const val RELEASES_URL = "https://github.com/Kelejun/XATUHelper-Android/releases"
val pushChannel: String = BuildConfig.PUSH_CHANNEL

enum class UpdateChannel(val value: String, val label: String) {
    RELEASE("release", "稳定版"), PRERELEASE("prerelease", "开发版");
    companion object {
        fun fromPushChannel(value: String) = entries.firstOrNull { it.value == value } ?: RELEASE
    }
}
enum class UpdateServer { GITHUB, OTHER }
data class UpdateSettings(
    val channel: UpdateChannel = UpdateChannel.fromPushChannel(pushChannel),
    val server: UpdateServer = UpdateServer.GITHUB,
    val repositoryUrl: String = "",
    val automatic: Boolean = false
)
data class UpdateLog(val at: Long, val automatic: Boolean, val channel: UpdateChannel, val success: Boolean, val detail: String)
data class AppRelease(val version: String, val notes: String, val url: String, val versionCode: Int = 0)
data class AppUpdateState(
    val settings: UpdateSettings = UpdateSettings(), val logs: List<UpdateLog> = emptyList(),
    val checking: Boolean = false, val message: String? = null, val offer: AppRelease? = null
)

fun updateNotesForDisplay(notes: String): String = notes.substringBefore("附件：").substringBefore("附件:").trim()
fun updateManifestUrl(channel: UpdateChannel): String = when (channel) {
    UpdateChannel.RELEASE -> "$RELEASES_URL/latest/download/release.update.json"
    UpdateChannel.PRERELEASE -> "$RELEASES_URL/download/Prerelease/prerelease.update.json"
}

interface UpdateStorage {
    suspend fun read(): AppUpdateState
    suspend fun write(state: AppUpdateState)
}

/** Kept outside school-account storage so logout does not erase update preferences or logs. */
class UpdatePreferences(context: Context) : UpdateStorage {
    private val preferences = context.getSharedPreferences("app_updates_v2", Context.MODE_PRIVATE)
    private val gson = Gson()
    override suspend fun read(): AppUpdateState = withContext(Dispatchers.IO) {
        runCatching { gson.fromJson(preferences.getString("state", null), AppUpdateState::class.java) }
            .getOrNull()?.copy(checking = false, message = null, offer = null) ?: AppUpdateState()
    }
    @SuppressLint("ApplySharedPref")
    override suspend fun write(state: AppUpdateState): Unit = withContext(Dispatchers.IO) {
        val saved = state.copy(logs = state.logs.take(2), checking = false, message = null, offer = null)
        if (!preferences.edit().putString("state", gson.toJson(saved)).commit()) throw IOException("无法保存更新设置与日志")
    }
}

class AppUpdateRepository(
    private val storage: UpdateStorage,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).retryOnConnectionFailure(false).build(),
    private val endpoint: (UpdateChannel) -> String = ::updateManifestUrl,
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val stateMutex = Mutex()
    private val checkMutex = Mutex()
    private var loaded = false
    private val mutable = MutableStateFlow(AppUpdateState())
    val state = mutable.asStateFlow()

    suspend fun initialize() = stateMutex.withLock { load() }
    private suspend fun load() {
        if (!loaded) {
            val saved = storage.read()
            mutable.value = saved.copy(logs = saved.logs.take(2), checking = false, message = null, offer = null)
            loaded = true
        }
    }
    suspend fun configure(change: (UpdateSettings) -> UpdateSettings) = stateMutex.withLock {
        load()
        val next = mutable.value.copy(settings = change(mutable.value.settings), message = null, offer = null)
        storage.write(next)
        mutable.value = next
    }
    fun dismissOffer() { mutable.update { it.copy(offer = null) } }

    /** Every request reaches the server. There is no interval, cached response or failure backoff. */
    suspend fun check(manual: Boolean = false) = checkMutex.withLock check@{
        val settings = stateMutex.withLock settings@{
            load()
            if (!manual && !mutable.value.settings.automatic) return@settings null
            mutable.value = mutable.value.copy(checking = true, message = null, offer = null)
            mutable.value.settings
        } ?: return@check
        try {
            val release = withContext(Dispatchers.IO) {
                if (settings.server == UpdateServer.OTHER) throw IOException("其他服务器暂未接入，请选择 GitHub")
                val json = JsonParser.parseString(downloadManifest(settings.channel)).asJsonObject
                if (json.get("schemaVersion")?.asInt != 1 || json.get("applicationId")?.asString != "com.xatune.app")
                    throw IOException("更新清单格式不正确")
                val expected = if (settings.channel == UpdateChannel.RELEASE) "stable" else "prerelease"
                if (json.get("channel")?.asString != expected) throw IOException("更新清单渠道不匹配")
                val code = json.get("versionCode")?.asInt?.takeIf { it > 0 } ?: throw IOException("清单缺少有效版本代码")
                val version = json.get("versionName")?.asString?.takeIf { it.isNotBlank() } ?: throw IOException("清单缺少版本名称")
                val url = if (settings.channel == UpdateChannel.PRERELEASE) "$RELEASES_URL/tag/Prerelease" else
                    "$RELEASES_URL/tag/v$version"
                AppRelease(version, json.get("notes")?.takeUnless { it.isJsonNull }?.asString.orEmpty().take(4000), url, code)
            }
            val newer = release.versionCode > currentVersionCode
            record(settings, !manual, true, if (newer) "发现新版本 ${release.version}（${release.versionCode}）" else "当前已是最新版本", release.takeIf { newer })
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val reason = when (e) {
                is java.net.UnknownHostException -> "无法解析 GitHub 地址，请检查网络"
                is java.net.SocketTimeoutException -> "连接 GitHub 超时"
                else -> e.message?.take(200) ?: "无法读取更新清单"
            }
            record(settings, !manual, false, reason, null)
        } finally {
            withContext(NonCancellable) { stateMutex.withLock { mutable.value = mutable.value.copy(checking = false) } }
        }
    }
    private fun downloadManifest(channel: UpdateChannel): String {
        val primary = endpoint(channel).toHttpUrl()
        // Older Releases still use update.json. Only a missing new filename may fall back,
        // and both requests stay under the selected channel's original download path.
        val legacy = requireNotNull(primary.resolve("update.json"))
        for (url in listOf(primary, legacy)) {
            val request = Request.Builder().url(url)
                .header("Accept", "application/json").header("User-Agent", "XATune-Android").build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) return response.body?.string() ?: throw IOException("清单响应为空")
                if (response.code != 404) throw IOException("GitHub HTTP ${response.code}")
            }
        }
        throw IOException("GitHub ${channel.label}更新清单尚未发布（HTTP 404）")
    }

    private suspend fun record(settings: UpdateSettings, automatic: Boolean, success: Boolean, detail: String, release: AppRelease?) = stateMutex.withLock {
        val previous = mutable.value
        val log = UpdateLog(clock(), automatic = automatic, channel = settings.channel, success = success, detail = detail)
        val next = previous.copy(logs = (listOf(log) + previous.logs).take(2), message = detail,
            offer = release.takeIf { previous.settings == settings })
        storage.write(next)
        mutable.value = next
    }
}
