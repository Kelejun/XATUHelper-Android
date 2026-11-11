package com.xatune.app.platform

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

internal object CampusWebNavigation {
    enum class Destination { WEB, APP, BLOCKED }

    fun destination(page: String, target: String, hasGesture: Boolean): Destination {
        // Browser navigations may cross domains or follow redirects without a gesture.
        // SchoolUrls.trusted is a school-service boundary, not a browser URL allowlist.
        if (target.toHttpUrlOrNull() != null || target.substringBefore('#') == "about:blank") return Destination.WEB
        val scheme = runCatching { URI(target).scheme?.lowercase() }.getOrNull()
        return when {
            page in setOf("netfee", "charge") && scheme in setOf("weixin", "alipay", "alipays") -> Destination.APP
            hasGesture && scheme in setOf("tel", "mailto") -> Destination.APP
            else -> Destination.BLOCKED
        }
    }
}
