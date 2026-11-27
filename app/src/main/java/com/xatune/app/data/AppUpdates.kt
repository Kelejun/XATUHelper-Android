package com.xatune.app.data

import android.annotation.SuppressLint
import android.content.Context
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
const val API_BASE_URL = "https://api.xatune.cn"
const val API_RELEASE_MANIFEST_URL = "$API_BASE_URL/download/release/release.update.json"
const val API_RELEASE_DOWNLOAD_BASE_URL = "$API_BASE_URL/download/release"
val pushChannel: String = BuildConfig.PUSH_CHANNEL

enum class UpdateChannel(val value: String, val label: String) {
    RELEASE("release", "稳定版"), PRERELEASE("prerelease", "开发版");
    companion object {
        fun fromPushChannel(value: String) = entries.firstOrNull { it.value == value } ?: RELEASE
    }
}
enum class UpdateServer { GITHUB, API, OTHER }
data class UpdateSettings(
    val channel: UpdateChannel = UpdateChannel.fromPushChannel(pushChannel),
    val server: UpdateServer = UpdateServer.GITHUB,
    val repositoryUrl: String = "",
    val automatic: Boolean = false
)
data class UpdateLog(val at: Long, val automatic: Boolean, val channel: UpdateChannel, val success: Boolean, val detail: String)
data class AppRelease(
    val version: String,
    val notes: String,
    val url: String,
    val versionCode: Int = 0,
    val server: UpdateServer = UpdateServer.GITHUB,
    val apiUrl: String? = null
)
data class AppUpdateState(
    val settings: UpdateSettings = UpdateSettings(), val logs: List<UpdateLog> = emptyList(),
    val checking: Boolean = false, val message: String? = null, val offer: AppRelease? = null
)

fun updateNotesForDisplay(notes: String): String = notes.substringBefore("附件：").substringBefore("附件:").trim()
fun updateManifestUrl(channel: UpdateChannel): String = when (channel) {
    UpdateChannel.RELEASE -> "$RELEASES_URL/latest/download/release.update.json"
    UpdateChannel.PRERELEASE -> "$RELEASES_URL/download/Prerelease/prerelease.update.json"
}
fun updateManifestUrl(channel: UpdateChannel, server: UpdateServer): String = when (server) {
    UpdateServer.GITHUB -> updateManifestUrl(channel)
    UpdateServer.API -> when (channel) {
        UpdateChannel.RELEASE -> API_RELEASE_MANIFEST_URL
        UpdateChannel.PRERELEASE -> throw IOException("国内服务器仅支持稳定版更新，请切换至稳定版或选择 GitHub")
    }
    UpdateServer.OTHER -> throw IOException("其他服务器暂未接入，请选择 GitHub 或国内服务器")
}
fun apiReleaseArch(supportedAbis: List<String> = android.os.Build.SUPPORTED_ABIS?.toList().orEmpty()): String {
    for (abi in supportedAbis) {
        when (abi) {
            "arm64-v8a" -> return "arm64"
            "armeabi-v7a" -> return "arm"
            "x86_64" -> return "x64"
            "x86" -> return "x86"
        }
    }
    return "arm64"
}
fun apiReleaseDownloadUrl(supportedAbis: List<String> = android.os.Build.SUPPORTED_ABIS?.toList().orEmpty()): String =
    "$API_RELEASE_DOWNLOAD_BASE_URL/${apiReleaseArch(supportedAbis)}"

interface UpdateStorage {
    suspend fun read(): AppUpdateState
    suspend fun write(state: AppUpdateState)
}

/** Kept outside school-account storage so logout does not erase update preferences or logs. */
class UpdatePreferences(context: Context) : UpdateStorage {
    private val preferences = context.getSharedPreferences("app_updates_v2", Context.MODE_PRIVATE)
    override suspend fun read(): AppUpdateState = withContext(Dispatchers.IO) {
        UpdateStateCodec.decode(preferences.getString("state", null))
    }
    @SuppressLint("ApplySharedPref")
    override suspend fun write(state: AppUpdateState): Unit = withContext(Dispatchers.IO) {
        if (!preferences.edit().putString("state", UpdateStateCodec.encode(state)).commit()) throw IOException("无法保存更新设置与日志")
    }
}

class AppUpdateRepository(
    private val storage: UpdateStorage,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).retryOnConnectionFailure(false).build(),
    private val endpoint: (UpdateChannel, UpdateServer) -> String = ::updateManifestUrl,
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    private val clock: () -> Long = System::currentTimeMillis,
    private val apiDownloadUrl: () -> String = { apiReleaseDownloadUrl() }
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
                if (settings.server == UpdateServer.OTHER) throw IOException("其他服务器暂未接入，请选择 GitHub 或国内服务器")
                if (settings.server == UpdateServer.API && settings.channel != UpdateChannel.RELEASE) {
                    throw IOException("国内服务器仅支持稳定版更新，请切换至稳定版或选择 GitHub")
                }
                val json = JsonParser.parseString(downloadManifest(settings.channel, settings.server)).asJsonObject
                if (json.get("schemaVersion")?.asInt != 1 || json.get("applicationId")?.asString != "com.xatune.app")
                    throw IOException("更新清单格式不正确")
                val expected = if (settings.channel == UpdateChannel.RELEASE) "stable" else "prerelease"
                if (json.get("channel")?.asString != expected) throw IOException("更新清单渠道不匹配")
                val code = json.get("versionCode")?.asInt?.takeIf { it > 0 } ?: throw IOException("清单缺少有效版本代码")
                val version = json.get("versionName")?.asString?.takeIf { it.isNotBlank() } ?: throw IOException("清单缺少版本名称")
                val githubUrl = if (settings.channel == UpdateChannel.PRERELEASE) "$RELEASES_URL/tag/Prerelease" else "$RELEASES_URL/tag/v$version"
                val apiUrl = if (settings.channel == UpdateChannel.RELEASE) apiDownloadUrl() else null
                AppRelease(
                    version = version,
                    notes = json.get("notes")?.takeUnless { it.isJsonNull }?.asString.orEmpty().take(4000),
                    url = githubUrl,
                    versionCode = code,
                    server = settings.server,
                    apiUrl = apiUrl
                )
            }
            val newer = release.versionCode > currentVersionCode
            record(settings, !manual, true, if (newer) "发现新版本 ${release.version}（${release.versionCode}）" else "当前已是最新版本", release.takeIf { newer })
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val serverName = if (settings.server == UpdateServer.API) "国内服务器" else "GitHub"
            val reason = when (e) {
                is java.net.UnknownHostException -> "无法解析${serverName}地址，请检查网络"
                is java.net.SocketTimeoutException -> "连接${serverName}超时"
                else -> e.message?.take(200) ?: "无法读取更新清单"
            }
            record(settings, !manual, false, reason, null)
        } finally {
            withContext(NonCancellable) { stateMutex.withLock { mutable.value = mutable.value.copy(checking = false) } }
        }
    }
    private fun downloadManifest(channel: UpdateChannel, server: UpdateServer): String {
        val serverName = if (server == UpdateServer.API) "国内服务器" else "GitHub"
        val primary = endpoint(channel, server).toHttpUrl()
        // Older GitHub Releases still use update.json. Only a missing new filename may fall back,
        // and both requests stay under the selected channel's original download path.
        val urls = if (server == UpdateServer.GITHUB) listOf(primary, requireNotNull(primary.resolve("update.json"))) else listOf(primary)
        for (url in urls) {
            val request = Request.Builder().url(url)
                .header("Accept", "application/json").header("User-Agent", "XATune-Android").build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) return response.body?.string() ?: throw IOException("清单响应为空")
                if (response.code != 404) throw IOException("$serverName HTTP ${response.code}")
            }
        }
        throw IOException("$serverName ${channel.label}更新清单尚未发布（HTTP 404）")
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
