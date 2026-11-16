package com.xatune.app.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID

data class Announcement(val id: String, val title: String, val body: String, val expiresAt: Long?, val publishedAt: Long, val imageUrl: String? = null)
data class ReceivedAnnouncement(val source: String, val announcement: Announcement, val receivedAt: Long) {
    val key: String get() = source + "#" + announcement.id
}

/** Complete receipts are the source of truth, including after expiry or withdrawal. */
class AnnouncementHistory(records: List<ReceivedAnnouncement> = emptyList()) {
    var records: List<ReceivedAnnouncement> = records
        private set
    val latest: ReceivedAnnouncement? get() = records.maxByOrNull { it.announcement.publishedAt }
    fun receive(source: String, items: List<Announcement>, at: Long) {
        val keys = records.map { it.key }.toMutableSet()
        records = (records + items.map { ReceivedAnnouncement(source, it, at) }.filter { keys.add(it.key) })
            .sortedWith(compareByDescending<ReceivedAnnouncement> { it.receivedAt }.thenByDescending { it.announcement.publishedAt }.thenBy { it.key })
    }
    fun delete(key: String) { records = records.filterNot { it.key == key } }
    fun clear() { records = emptyList() }
}

object AnnouncementCodec {
    private val gson = Gson()
    fun encode(records: List<ReceivedAnnouncement>): String = gson.toJson(records)
    fun decode(json: String): List<ReceivedAnnouncement> = runCatching {
        val records = gson.fromJson<List<ReceivedAnnouncement>>(json, object : TypeToken<List<ReceivedAnnouncement>>() {}.type) ?: emptyList()
        records.filter { record -> runCatching {
            record.source.isNotBlank() && record.receivedAt > 0 && record.announcement.id.isNotBlank() &&
                record.announcement.title.isNotBlank() && record.announcement.body.isNotBlank() && record.announcement.publishedAt > 0
        }.getOrDefault(false) }.distinctBy { it.key }
    }.getOrDefault(emptyList())
}

class AnnouncementClient(private val client: OkHttpClient, base: String, allowHttp: Boolean) {
    val endpoint = base.trim().trimEnd('/').toHttpUrlOrNull()?.takeIf {
        (it.isHttps || allowHttp) && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
    }?.newBuilder()?.addPathSegments("v1/announcements")?.addQueryParameter("platform", "android")?.build()

    fun fetch(): Pair<List<Announcement>, Long> {
        val url = requireNotNull(endpoint)
        return client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
            require(response.code == 200 && response.body?.contentType()?.let { it.type == "application" && it.subtype == "json" } == true)
            val source = requireNotNull(response.body).source()
            source.request(4L * 1024 * 1024 + 1)
            require(source.buffer.size <= 4L * 1024 * 1024)
            val json = JsonParser.parseString(source.buffer.readUtf8()).asJsonObject
            val now = json["server_time"].asLong
            val rows = json["announcements"].asJsonArray
            require(now > 0 && rows.size() <= 100)
            val items = rows.map { element ->
                val row = element.asJsonObject
                val id = row["id"].asString
                require(UUID.fromString(id).toString().equals(id, ignoreCase = true))
                val title = row["title"].asString
                val body = row["body"].asString
                val published = row["published_at"].asLong
                val expires = row["expires_at"]?.takeUnless { it.isJsonNull }?.asLong
                val image = row["image_url"]?.takeUnless { it.isJsonNull }?.asString
                require(title.isNotBlank() && title.codePointCount(0, title.length) <= 120 && body.isNotBlank() && body.codePointCount(0, body.length) <= 10000)
                require(row["platforms"].asJsonArray.any { it.asString == "android" } && published in 1..now && (expires == null || expires > now))
                Announcement(id, title, body, expires, published, image)
            }
            require(items.map { it.id }.distinct().size == items.size)
            items to now
        }
    }
}
