package com.xatune.app

import com.xatune.app.data.*
import com.xatune.app.platform.nextUpdateAt
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class AppUpdatesTest {
    private class MemoryStorage(var saved: AppUpdateState = AppUpdateState(settings = UpdateSettings(channel = UpdateChannel.RELEASE))) : UpdateStorage {
        override suspend fun read() = saved
        override suspend fun write(state: AppUpdateState) { saved = state }
    }
    private fun manifest(code: Int = 35, channel: String = "stable") = MockResponse().setBody(
        """{"schemaVersion":1,"applicationId":"com.xatune.app","channel":"$channel","versionCode":$code,"versionName":"1.2.0","notes":"更新说明","releaseUrl":"https://untrusted.example"}""")

    @Test fun manifestAddressesAndBuildDefaultsMatchChannels() {
        assertEquals("$RELEASES_URL/latest/download/release.update.json", updateManifestUrl(UpdateChannel.RELEASE))
        assertEquals("$RELEASES_URL/download/Prerelease/prerelease.update.json", updateManifestUrl(UpdateChannel.PRERELEASE))
        assertEquals(API_RELEASE_MANIFEST_URL, updateManifestUrl(UpdateChannel.RELEASE, UpdateServer.API))
        assertEquals("$RELEASES_URL/latest/download/release.update.json", updateManifestUrl(UpdateChannel.RELEASE, UpdateServer.GITHUB))
        assertEquals("arm64", apiReleaseArch(listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("arm", apiReleaseArch(listOf("armeabi-v7a")))
        assertEquals("x64", apiReleaseArch(listOf("x86_64", "x86")))
        assertEquals("x86", apiReleaseArch(listOf("x86")))
        assertEquals("arm64", apiReleaseArch(emptyList()))
        assertEquals("$API_RELEASE_DOWNLOAD_BASE_URL/arm", apiReleaseDownloadUrl(listOf("armeabi-v7a")))
        assertEquals(UpdateChannel.RELEASE, UpdateChannel.fromPushChannel("release"))
        assertEquals(UpdateChannel.PRERELEASE, UpdateChannel.fromPushChannel("prerelease"))
        assertEquals(UpdateChannel.fromPushChannel(BuildConfig.PUSH_CHANNEL), UpdateSettings().channel)
        assertEquals(UpdateServer.GITHUB, UpdateSettings().server)
        assertFalse(UpdateSettings().automatic)
    }

    @Test fun disabledAutomaticChecksDoNotRequestOrLogButManualChecksAlwaysRun() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val storage = MemoryStorage()
            val repo = AppUpdateRepository(storage, endpoint = { _, _ -> server.url("/").toString() }, currentVersionCode = 34)
            repo.check()
            assertEquals(0, server.requestCount)
            assertTrue(repo.state.value.logs.isEmpty())
            repeat(2) { server.enqueue(manifest()); repo.check(manual = true) }
            assertEquals(2, server.requestCount)
            assertEquals(2, storage.saved.logs.size)
            assertTrue(storage.saved.logs.all { it.success && !it.automatic })
            assertEquals(35, repo.state.value.offer?.versionCode)
            assertEquals("$RELEASES_URL/tag/v1.2.0", repo.state.value.offer?.url)
            assertEquals(UpdateServer.GITHUB, repo.state.value.offer?.server)
            val request = server.takeRequest()
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
            assertNull(request.getHeader("If-None-Match"))
        }
    }

    @Test fun rateLimitFailureDoesNotBlockImmediateRetryAndOnlyTwoMixedLogsSurviveRestart() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val storage = MemoryStorage()
            var now = 1_000L
            val repo = AppUpdateRepository(storage, endpoint = { _, _ -> server.url("/").toString() }, currentVersionCode = 34, clock = { now++ })
            repo.configure { it.copy(automatic = true) }
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "86400"))
            repo.check()
            assertFalse(repo.state.value.logs.first().success)
            assertTrue(repo.state.value.logs.first().detail.contains("429"))
            assertTrue(repo.state.value.logs.first().automatic)
            server.enqueue(manifest())
            repo.check(manual = true)
            server.enqueue(manifest(34))
            repo.check()
            assertEquals(3, server.requestCount)
            val logs = repo.state.value.logs
            assertEquals(2, logs.size)
            assertTrue(logs[0].automatic)
            assertFalse(logs[1].automatic)
            assertTrue(logs.all { it.success })
            assertEquals("当前已是最新版本", logs[0].detail)
            assertNull(repo.state.value.offer)
            val restarted = AppUpdateRepository(storage)
            restarted.initialize()
            assertEquals(logs, restarted.state.value.logs)
            assertTrue(restarted.state.value.settings.automatic)
            repo.configure { it.copy(automatic = false) }
            repo.check()
            assertEquals(3, server.requestCount)
            assertEquals(logs, repo.state.value.logs)
        }
    }

    @Test fun selectedPrereleaseIsFetchedAndComparedUsingVersionCode() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val repo = AppUpdateRepository(MemoryStorage(), endpoint = { channel, _ -> server.url("/${channel.value}.update.json").toString() }, currentVersionCode = 34)
            repo.configure { it.copy(channel = UpdateChannel.PRERELEASE) }
            server.enqueue(manifest(channel = "prerelease"))
            repo.check(manual = true)
            assertEquals("/prerelease.update.json", server.takeRequest().path)
            assertEquals("$RELEASES_URL/tag/Prerelease", repo.state.value.offer?.url)
            assertEquals(UpdateChannel.PRERELEASE, repo.state.value.logs.first().channel)
            repo.dismissOffer()
            assertNull(repo.state.value.offer)
        }
    }

    @Test fun apiServerChecksStableReleaseAndOffersDirectArchDownloadUrl() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val storage = MemoryStorage(
                AppUpdateState(settings = UpdateSettings(channel = UpdateChannel.RELEASE, server = UpdateServer.API, automatic = true))
            )
            val repo = AppUpdateRepository(
                storage,
                endpoint = { _, srv ->
                    assertEquals(UpdateServer.API, srv)
                    server.url("/download/release/release.update.json").toString()
                },
                currentVersionCode = 34,
                apiDownloadUrl = { apiReleaseDownloadUrl(listOf("x86_64", "x86")) }
            )
            server.enqueue(manifest(code = 36, channel = "stable"))
            repo.check(manual = false)
            assertEquals("/download/release/release.update.json", server.takeRequest().path)
            val offer = requireNotNull(repo.state.value.offer)
            assertEquals(36, offer.versionCode)
            assertEquals(UpdateServer.API, offer.server)
            assertEquals("$API_RELEASE_DOWNLOAD_BASE_URL/x64", offer.url)
            assertTrue(repo.state.value.logs.first().automatic)
            assertTrue(repo.state.value.logs.first().success)
        }
    }

    @Test fun apiServerRejectsPrereleaseAndDoesNotFallbackToLegacyManifestOn404() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val storage = MemoryStorage(
                AppUpdateState(settings = UpdateSettings(channel = UpdateChannel.PRERELEASE, server = UpdateServer.API))
            )
            val repo = AppUpdateRepository(storage, endpoint = { _, _ -> server.url("/download/release/release.update.json").toString() })
            repo.check(manual = true)
            assertEquals(0, server.requestCount)
            assertFalse(repo.state.value.logs.first().success)
            assertTrue(repo.state.value.logs.first().detail.contains("后端 API 仅支持稳定版"))

            repo.configure { it.copy(channel = UpdateChannel.RELEASE, server = UpdateServer.API) }
            server.enqueue(MockResponse().setResponseCode(404))
            repo.check(manual = true)
            assertEquals(1, server.requestCount)
            assertFalse(repo.state.value.logs.first().success)
            assertTrue(repo.state.value.logs.first().detail.contains("后端 API"))
        }
    }

    @Test fun invalidManifestIsLoggedAsFailureAndDoesNotOfferUpdate() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val repo = AppUpdateRepository(MemoryStorage(), endpoint = { _, _ -> server.url("/").toString() })
            for (response in listOf(manifest(channel = "prerelease"), MockResponse().setBody("not json"), manifest(code = 0))) {
                server.enqueue(response)
                repo.check(manual = true)
                assertFalse(repo.state.value.logs.first().success)
                assertNull(repo.state.value.offer)
                assertFalse(repo.state.value.checking)
            }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun otherServerOnlyPersistsInputAndNeverUsesItForRequests() = runBlocking {
        var requests = 0
        val storage = MemoryStorage()
        val repo = AppUpdateRepository(storage, endpoint = { _, _ -> requests++; error("Must not fetch") })
        repo.configure { it.copy(server = UpdateServer.OTHER, repositoryUrl = "https://mirror.example/repo") }
        repo.check(manual = true)
        assertEquals(0, requests)
        assertEquals("https://mirror.example/repo", storage.saved.settings.repositoryUrl)
        assertFalse(storage.saved.logs.first().success)
        assertTrue(storage.saved.logs.first().detail.contains("暂未接入"))
    }

    @Test fun dailyScheduleUsesNextLocalNineIncludingTimeZoneAndDaylightSaving() {
        val before = ZonedDateTime.parse("2026-11-11T08:59:00+08:00[Asia/Shanghai]")
        assertEquals("2026-11-11T09:00+08:00[Asia/Shanghai]", nextUpdateAt(before).toString())
        assertEquals("2026-11-12T09:00+08:00[Asia/Shanghai]", nextUpdateAt(before.withHour(9).withMinute(0)).toString())
        val dst = ZonedDateTime.parse("2026-03-07T10:00:00-05:00[America/New_York]")
        assertEquals("2026-03-08T09:00-04:00[America/New_York]", nextUpdateAt(dst).toString())
    }

    @Test fun attachmentSectionIsExcludedFromDisplayedReleaseNotes() {
        assertEquals("更新内容", updateNotesForDisplay("更新内容\n\n附件：\n- app.apk"))
    }
}
