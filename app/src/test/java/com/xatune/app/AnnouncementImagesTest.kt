package com.xatune.app

import com.xatune.app.data.AnnouncementImages
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AnnouncementImagesTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun cachedCoversSurviveRestartAndLastReferenceRemovalDeletesFile() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("cover"))
            val directory = temporary.newFolder()
            val store = AnnouncementImages(directory, OkHttpClient(), true) { true }
            val url = server.url("/shared.png").toString()
            val refs = setOf(url)
            store.download(refs, store.retain(refs))
            assertEquals("cover", store.cached(url)!!.readText())
            val restarted = AnnouncementImages(directory, OkHttpClient(), true) { true }
            restarted.download(refs, restarted.retain(refs))
            assertEquals(1, server.requestCount)
            // A shared URL stays retained while any history entry still references it.
            restarted.retain(refs)
            assertNotNull(restarted.cached(url))
            restarted.retain(emptySet())
            assertNull(restarted.cached(url))
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }
    @Test fun delayedDownloadCannotRestoreDeletedCover() = runBlocking {
        MockWebServer().use { server ->
            val started = CountDownLatch(1)
            val finish = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    started.countDown()
                    check(finish.await(5, TimeUnit.SECONDS))
                    return MockResponse().setBody("late cover")
                }
            }
            val directory = temporary.newFolder()
            val store = AnnouncementImages(directory, OkHttpClient(), true) { true }
            val url = server.url("/late.png").toString()
            val refs = setOf(url)
            val token = store.retain(refs)
            val download = launch(Dispatchers.IO) { store.download(refs, token) }
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS))
                store.retain(emptySet())
            } finally { finish.countDown() }
            download.join()
            assertNull(store.cached(url))
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }
    @Test fun oversizedAndInvalidImagesAreNotPersisted() = runBlocking {
        MockWebServer().use { server ->
            val store = AnnouncementImages(temporary.newFolder(), OkHttpClient(), true) { false }
            val url = server.url("/bad").toString()
            val refs = setOf(url)
            server.enqueue(MockResponse().setBody("not an image"))
            store.download(refs, store.retain(refs))
            assertNull(store.cached(url))
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(10 * 1024 * 1024 + 1))))
            store.download(refs, store.retain(refs))
            assertNull(store.cached(url))
        }
    }
}
