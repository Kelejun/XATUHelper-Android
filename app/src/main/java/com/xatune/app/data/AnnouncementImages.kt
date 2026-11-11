package com.xatune.app.data

import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

class AnnouncementImages(private val directory: File, private val client: OkHttpClient, private val allowHttp: Boolean,
    private val validateImage: (ByteArray) -> Boolean = { bytes ->
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        options.outWidth > 0 && options.outHeight > 0
    }
) {
    private val lock = Any()
    private var generation = 0L
    private var retained = emptySet<String>()
    private fun file(url: String) = File(directory, MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) })
    fun cached(url: String): File? = synchronized(lock) { file(url).takeIf { it.isFile } }
    // Called immediately on the owner thread so late downloads cannot recreate deleted files.
    fun retain(urls: Set<String>): Long = synchronized(lock) {
        retained = urls
        generation++
        directory.mkdirs()
        val filenames = urls.map { file(it).name }.toSet()
        directory.listFiles()?.filter { it.name !in filenames }?.forEach { it.delete() }
        generation
    }
    suspend fun download(urls: Set<String>, token: Long) = withContext(Dispatchers.IO) {
        for (raw in urls) {
            if (synchronized(lock) { token != generation }) break
            if (cached(raw) != null) continue
            val url = raw.toHttpUrlOrNull()?.takeIf { (it.isHttps || allowHttp) && it.username.isEmpty() && it.password.isEmpty() } ?: continue
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    require(response.isSuccessful)
                    val source = requireNotNull(response.body).source()
                    source.request(10L * 1024 * 1024 + 1)
                    require(source.buffer.size <= 10L * 1024 * 1024)
                    val bytes = source.buffer.readByteArray()
                    require(validateImage(bytes))
                    synchronized(lock) {
                        if (token == generation && raw in retained) {
                            val target = file(raw)
                            val temp = File(directory, target.name + ".tmp")
                            try { temp.writeBytes(bytes); check(temp.renameTo(target)) } finally { temp.delete() }
                        }
                    }
                }
            }
        }
    }
}
