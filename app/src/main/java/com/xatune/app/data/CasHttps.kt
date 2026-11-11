package com.xatune.app.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

internal object CasHttps : Interceptor {
    fun upgrade(url: String): String {
        val target = url.toHttpUrlOrNull() ?: return url
        if (target.scheme != "http" || target.host !in setOf("authserver.xatu.edu.cn", "jfpay.xatu.edu.cn") || target.port != 80) return url
        return target.newBuilder().scheme("https").port(443).build().toString()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isRedirect) return response
        val target = response.header("Location")?.let { response.request.url.resolve(it) } ?: return response
        val secure = upgrade(target.toString())
        return if (secure == target.toString()) response else response.newBuilder().header("Location", secure).build()
    }
}
