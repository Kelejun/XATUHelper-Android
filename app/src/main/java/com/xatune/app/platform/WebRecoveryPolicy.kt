package com.xatune.app.platform

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class WebRecoveryPolicy(private val manual: Boolean = false) {
    private var attempted = false
    fun authenticationResponse(url: String, mainFrame: Boolean = true, status: Int = 200): Boolean =
        !manual && mainFrame && status !in 300..399 && url.toHttpUrlOrNull()?.host == "authserver.xatu.edu.cn"
    fun begin(): Boolean {
        if (manual || attempted) return false
        attempted = true
        return true
    }
}
