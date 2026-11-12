package com.xatune.app

import com.xatune.app.data.*
import com.xatune.app.ui.markdownBlocks
import com.xatune.app.ui.markdownInline
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AnnouncementsTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private fun notice(id: String = this.id, published: Long = 100, image: String? = null) = Announcement(id, "标题", "内容", null, published, image)
    @Test fun historyKeepsFirstReceiptAcrossWithdrawalExpiryAndRestart() {
        val history = AnnouncementHistory()
        history.receive("a", listOf(notice(image = "https://example.com/cover")), 1000)
        history.receive("a", listOf(notice()), 2000)
        history.receive("a", emptyList(), 3000)
        val restarted = AnnouncementHistory(AnnouncementCodec.decode(AnnouncementCodec.encode(history.records)))
        assertEquals(1, restarted.records.size)
        assertEquals(1000, restarted.records.single().receivedAt)
        assertEquals("https://example.com/cover", restarted.records.single().announcement.imageUrl)
    }
    @Test fun sourcesAreIndependentAndDeletionAllowsFreshReceipt() {
        val history = AnnouncementHistory()
        history.receive("a", listOf(notice()), 1000)
        history.receive("b", listOf(notice()), 2000)
        history.delete(history.records.first { it.source == "a" }.key)
        assertEquals("b", history.records.single().source)
        history.receive("a", listOf(notice()), 3000)
        assertEquals(3000, history.records.first().receivedAt)
        assertEquals(2, history.records.size)
        history.clear()
        history.receive("a", listOf(notice()), 4000)
        assertEquals(4000, history.records.single().receivedAt)
    }
    @Test fun historySortsByReceiptThenPublicationAndLatestUsesPublication() {
        val history = AnnouncementHistory()
        history.receive("a", listOf(notice("one", 300), notice("two", 500)), 1000)
        history.receive("a", listOf(notice("three", 200)), 2000)
        assertEquals(listOf("three", "two", "one"), history.records.map { it.announcement.id })
        assertEquals("two", history.latest!!.announcement.id)
        history.delete(history.latest!!.key)
        assertEquals("one", history.latest!!.announcement.id)
    }
    @Test fun invalidHistoryDoesNotCrash() {
        for (json in listOf("broken", "null", "[{}]", "[null]")) assertTrue(AnnouncementCodec.decode(json).isEmpty())
    }
    private fun response(published: Long = 100, expires: String = "null", image: String = "null") = """{"server_time":200,"announcements":[{"id":"$id","title":"公告","body":"# 内容","published_at":$published,"expires_at":$expires,"image_url":$image,"platforms":["android"]}]}"""
    @Test fun clientUsesAndroidEndpointAndParsesOptionalCover() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(response(image = "\"https://example.com/cover\"")))
            val client = AnnouncementClient(OkHttpClient(), server.url("/prefix/").toString(), true)
            val (items, time) = client.fetch()
            assertEquals(200, time)
            assertEquals("https://example.com/cover", items.single().imageUrl)
            assertEquals("/prefix/v1/announcements?platform=android", server.takeRequest().path)
        }
    }
    @Test fun productionAllowsOnlyFixedHttpHostOrHttpsAndRejectsCredentialsQueryAndFragment() {
        val client = OkHttpClient()
        for (base in listOf("http://example.com", "http://47.109.84.89.evil.example", "http://sub.47.109.84.89", "http://user@47.109.84.89", "http://47.109.84.89?a=1", "http://47.109.84.89#x", "https://user@example.com", "https://example.com?a=1", "https://example.com#x", "garbage")) assertNull(base, AnnouncementClient(client, base, false).endpoint)
        assertNotNull(AnnouncementClient(client, "https://example.com", false).endpoint)
        assertEquals("http://47.109.84.89/v1/announcements?platform=android", AnnouncementClient(client, "http://47.109.84.89", false).endpoint.toString())
        assertNull(AnnouncementClient(client, "http://localhost", false).endpoint)
        assertNotNull(AnnouncementClient(client, "http://localhost", true).endpoint)
    }
    @Test fun clientRejectsExpiredFutureDuplicateAndNonJsonFeeds() {
        MockWebServer().use { server ->
            val client = AnnouncementClient(OkHttpClient(), server.url("/").toString(), true)
            val valid = response()
            val row = valid.substringAfter("[").substringBeforeLast("]")
            for (body in listOf(response(201), response(expires = "200"), """{"server_time":200,"announcements":[$row,$row]}""")) {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
                assertTrue(runCatching { client.fetch() }.isFailure)
            }
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(valid))
            assertTrue(runCatching { client.fetch() }.isFailure)
        }
    }
    @Test fun markdownSupportsBlocksAndKeepsHtmlAsText() {
        val blocks = markdownBlocks("# 标题\n\n段落 **粗体**\n\n- 项目\n1. 顺序\n> 引用\n---\n```kotlin\n<html>\n```")
        assertEquals(listOf("heading", "paragraph", "list", "list", "quote", "rule", "code"), blocks.map { it.kind })
        assertEquals("<html>", blocks.last().text)
        assertEquals("粗体 斜体 链接 code", markdownInline("**粗体** *斜体* [链接](https://example.com) `code`").text)
        assertEquals("安全", markdownInline("[安全](javascript:alert)").text)
    }
}
