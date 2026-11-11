package com.xatune.app

import com.xatune.app.data.*
import org.junit.Assert.*
import org.junit.Test

class UpdateStateCodecTest {
    @Test fun cacheRoundTripRestoresTypedLogsAndOnlyPersistentState() {
        val state = AppUpdateState(
            settings = UpdateSettings(UpdateChannel.PRERELEASE, UpdateServer.OTHER, "https://example.com/repo", true),
            logs = listOf(
                UpdateLog(3_000, false, UpdateChannel.PRERELEASE, true, "当前已是最新版本"),
                UpdateLog(2_000, true, UpdateChannel.RELEASE, false, "GitHub HTTP 503"),
                UpdateLog(1_000, true, UpdateChannel.RELEASE, true, "旧记录")
            ),
            checking = true, message = "正在检测", offer = AppRelease("2.0", "", "https://example.com")
        )
        val restored = UpdateStateCodec.decode(UpdateStateCodec.encode(state))
        assertEquals(state.copy(logs = state.logs.take(2), checking = false, message = null, offer = null), restored)
        assertEquals("当前已是最新版本", restored.logs.first().detail)
    }

    @Test fun existingNamedCacheIsReadableWithoutReflectiveListDeserialization() {
        val restored = UpdateStateCodec.decode("""{
            "settings":{"channel":"PRERELEASE","server":"GITHUB","automatic":true},
            "logs":[{"at":1000,"automatic":false,"channel":"RELEASE","success":false,"detail":"HTTP 503"}]
        }""")
        assertEquals(UpdateSettings(channel = UpdateChannel.PRERELEASE, automatic = true), restored.settings)
        assertEquals(listOf(UpdateLog(1_000, false, UpdateChannel.RELEASE, false, "HTTP 503")), restored.logs)
    }

    @Test fun damagedEntriesAreDroppedWithoutLosingSettingsOrValidLogs() {
        val restored = UpdateStateCodec.decode("""{
            "settings":{"channel":"PRERELEASE","server":"OTHER","repositoryUrl":"https://example.com","automatic":true},
            "logs":[null,{},42,{"a":1000,"b":true},
                {"at":1000,"automatic":true,"channel":"UNKNOWN","success":true,"detail":"bad"},
                {"at":2000,"automatic":true,"channel":"RELEASE","success":true,"detail":null},
                {"at":3000,"automatic":false,"channel":"PRERELEASE","success":false,"detail":"保留记录"}]
        }""")
        assertEquals(UpdateSettings(UpdateChannel.PRERELEASE, UpdateServer.OTHER, "https://example.com", true), restored.settings)
        assertEquals(listOf(UpdateLog(3_000, false, UpdateChannel.PRERELEASE, false, "保留记录")), restored.logs)
    }

    @Test fun invalidAndObfuscatedCachesFallBackSafely() {
        for (json in listOf(null, "", "not json", "null", "[]", "42", """{"a":{},"b":[{"a":1000}]}""")) {
            assertEquals(AppUpdateState(), UpdateStateCodec.decode(json))
        }
        assertEquals(AppUpdateState(), UpdateStateCodec.decode("""{
            "settings":{"channel":"UNKNOWN","server":null,"repositoryUrl":{},"automatic":"true"},"logs":{}
        }"""))
    }
}
