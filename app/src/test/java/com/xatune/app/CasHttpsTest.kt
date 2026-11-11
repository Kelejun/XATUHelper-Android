package com.xatune.app

import com.xatune.app.data.CasHttps
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class CasHttpsTest {
    @Test fun legacyCasRedirectsAreUpgradedBeforeFollowing() {
        MockWebServer().use { server ->
            server.start()
            val client = OkHttpClient.Builder().followRedirects(false).addNetworkInterceptor(CasHttps).build()
            val query = "/authserver/login?service=http%3A%2F%2Fjwgl2018.xatu.edu.cn%2Feams%2FhomeExt.action&ticket=ST-fixture"
            for (code in listOf(301, 302, 303, 307, 308)) {
                server.enqueue(MockResponse().setResponseCode(code).addHeader("Location", "http://authserver.xatu.edu.cn$query"))
                client.newCall(Request.Builder().url(server.url("/eams/")).build()).execute().use { response ->
                    assertEquals(code, response.code)
                    assertEquals("https://authserver.xatu.edu.cn$query", response.header("Location"))
                }
            }
        }
    }

    @Test fun onlyTheExactLegacyCasOriginIsUpgraded() {
        assertEquals("https://authserver.xatu.edu.cn/authserver/login", CasHttps.upgrade("http://authserver.xatu.edu.cn:80/authserver/login"))
        for (url in listOf(
            "http://jwgl2018.xatu.edu.cn/eams/",
            "http://self.xatu.edu.cn/Self/",
            "http://authserver.xatu.edu.cn.evil.example/authserver/login",
            "http://evil.authserver.xatu.edu.cn/authserver/login",
            "http://authserver.xatu.edu.cn:8080/authserver/login",
            "https://authserver.xatu.edu.cn/authserver/login"
        )) assertEquals(url, url, CasHttps.upgrade(url))
    }

    @Test fun campusNetworkChargeUsesHttpsWithoutChangingTheCallback() {
        assertEquals("https://jfpay.xatu.edu.cn/casLoginNetCharge/?ticket=ST-fixture",
            CasHttps.upgrade("http://jfpay.xatu.edu.cn/casLoginNetCharge/?ticket=ST-fixture"))
        for (url in listOf("http://jfpay.xatu.edu.cn.evil.example/casLoginNetCharge/",
            "http://sub.jfpay.xatu.edu.cn/casLoginNetCharge/", "http://jfpay.xatu.edu.cn:8080/casLoginNetCharge/")) {
            assertEquals(url, CasHttps.upgrade(url))
        }
    }
}
