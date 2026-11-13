package com.xatune.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.xatune.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.jsoup.Jsoup
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class CampusRepository(private val context: Context, val store: LocalStore, private val session: TimetableRepository, private val pay: PayNetwork) {
    suspend fun selectedDorm(account: String) = store.extra("dorm:$account", Dorm::class.java)
    suspend fun electricity(account: String): Electricity? = store.extra("electric:$account", Electricity::class.java)?.takeIf { it.dorm == selectedDorm(account) }
    suspend fun autoElectricity(account: String) = store.extra("autoElectric:$account", Boolean::class.javaObjectType) ?: true
    suspend fun reminders(account: String) = store.extra("reminders:$account", ReminderSettings::class.java) ?: ReminderSettings()
    private suspend fun rawCalendar(): SchoolCalendar? = withContext(Dispatchers.IO) {
        val cached = store.extra("schoolCalendar", SchoolCalendar::class.java) ?: return@withContext null
        if (cached.recognitionVersion >= 1 || cached.imagePath.isBlank()) return@withContext cached
        // Re-read the saved image when upgrading, without depending on campus network access.
        val bitmap = runCatching { decodeCalendar(File(cached.imagePath).takeIf { it.isFile }?.readBytes() ?: return@withContext cached) }.getOrNull() ?: return@withContext cached
        val recognized = try { CalendarOcr.read(bitmap) } catch (_: Exception) { return@withContext cached } finally { bitmap.recycle() }
        cached.copy(rawText = recognized.text, semesters = recognized.semesters.ifEmpty { cached.semesters }, coverageEnd = recognized.coverageEnd, recognitionVersion = 1).also { store.extra("schoolCalendar", it) }
    }
    suspend fun calendar(): SchoolCalendar? = rawCalendar()?.let { applyCorrection(it) }
    suspend fun originalCalendar(): SchoolCalendar? = rawCalendar()
    private fun correctionKey(url: String) = "schoolCalendar.corrections:$url"
    suspend fun applyCorrection(raw: SchoolCalendar): SchoolCalendar {
        val correction = store.extra(correctionKey(raw.imageUrl), CalendarCorrection::class.java)?.takeIf { it.error() == null }?.normalized() ?: return raw
        return raw.copy(semesters = correction.semesters, notes = correction.notes)
    }
    suspend fun saveCalendarCorrection(imageUrl: String, draft: CalendarCorrection): SchoolCalendar {
        val correction = draft.normalized()
        require(correction.error() == null) { correction.error().orEmpty() }
        val raw = rawCalendar() ?: error("校历已更新，请重新打开")
        require(raw.imageUrl == imageUrl) { "校历已更新，请重新打开" }
        store.extra(correctionKey(imageUrl), correction.takeUnless { it == CalendarCorrection.original(raw) })
        return applyCorrection(raw)
    }
    suspend fun projects(account: String) = session.withSession(account) { pay.projects() }
    suspend fun areas(account: String, project: String) = session.withSession(account) { pay.areas(project) }
    suspend fun buildings(account: String, project: String, area: String) = session.withSession(account) { pay.buildings(project, area) }
    suspend fun rooms(account: String, project: String, area: String, building: String) = session.withSession(account) { pay.rooms(project, area, building) }
    suspend fun saveDorm(account: String, dorm: Dorm) { if (store.account() != account) throw SessionExpired(); store.extra("dorm:$account", dorm); store.extra("electric:$account", null) }
    suspend fun clearDorm(account: String) { store.extra("dorm:$account", null); store.extra("electric:$account", null) }
    suspend fun refreshElectricity(account: String): Electricity {
        val dorm = selectedDorm(account) ?: throw IllegalStateException("请先选择宿舍")
        val balance = session.withSession(account) { pay.balance(dorm) }
        if (store.account() != account || selectedDorm(account) != dorm) throw IllegalStateException("宿舍已切换，请刷新")
        return Electricity(dorm, balance, System.currentTimeMillis()).also { store.extra("electric:$account", it); store.record(account, it) }
    }
    private val calendarClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).addNetworkInterceptor { chain ->
        val url = chain.request().url
        if (url.scheme != "https" || url.host != "jwcw.xatu.edu.cn") throw java.io.IOException("校历链接不属于教务处站点")
        chain.proceed(chain.request())
    }.build()
    private fun download(url: String): ByteArray = calendarClient.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36").header("Accept", "text/html,image/*;q=0.9,*/*;q=0.8").build()).execute().use {
        if (!it.isSuccessful) throw java.io.IOException("教务处返回 HTTP ${it.code}，请在校园网下重试")
        val body = it.body ?: throw java.io.IOException("教务处未返回内容")
        if (body.contentLength() > 20_000_000) throw java.io.IOException("校历图片过大")
        body.byteStream().use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; if (output.size() + n > 20_000_000) throw java.io.IOException("校历图片过大"); output.write(buffer, 0, n) }
            output.toByteArray()
        }
    }
    private fun html(url: String): String = String(download(url), Charsets.UTF_8).also { if (it.contains("无权访问") || it.contains("无权限访问")) throw java.io.IOException("校历仅限校园网访问，请连接校园网；已保存的校历仍可查看。") }
    private fun decodeCalendar(bytes: ByteArray): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ParseFailure("校历原图格式异常")
    var sample = 1; while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096 || bounds.outWidth.toLong() * bounds.outHeight / sample / sample > 12_000_000) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw ParseFailure("无法打开校历原图")
    }
    suspend fun refreshCalendar(): SchoolCalendar = withContext(Dispatchers.IO) {
        val page = CalendarParser.latest(html(CalendarParser.index))
        val document = html(page)
        val image = CalendarParser.image(document, page)
        val bytes = download(image)
        val bitmap = decodeCalendar(bytes)
        val recognized = try { CalendarOcr.read(bitmap) } catch (_: Exception) { CalendarOcr.Result("", emptyList(), null) } finally { bitmap.recycle() }
        val name = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file = File(context.filesDir, "calendar-$name.image")
        android.util.AtomicFile(file).let { atomic -> val stream = atomic.startWrite(); try { stream.write(bytes); atomic.finishWrite(stream) } catch (e: Exception) { atomic.failWrite(stream); throw e } }
        val result = SchoolCalendar(Jsoup.parse(document).title().substringBefore("-西安工业"), page, image, file.absolutePath, recognized.text, recognized.semesters, System.currentTimeMillis(), recognized.coverageEnd, 1)
        store.extra("schoolCalendar", result)
        context.filesDir.listFiles()?.filter { it.name.startsWith("calendar-") && it != file }?.forEach { it.delete() }
        applyCorrection(result)
    }
}
