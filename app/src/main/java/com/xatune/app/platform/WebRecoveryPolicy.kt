package com.xatune.app.platform

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class WebRecoveryPolicy(private val manual: Boolean = false) {
    private var attempted = false
    private val canceledAuthUrls = mutableSetOf<String>()

    fun authenticationResponse(url: String, mainFrame: Boolean = true, status: Int = 200): Boolean =
        !manual && mainFrame && status !in 300..399 && url.toHttpUrlOrNull()?.host == "authserver.xatu.edu.cn"

    fun begin(): Boolean {
        if (manual || attempted) return false
        attempted = true
        return true
    }

    fun markStopped(url: String?) {
        val normalized = normalizeUrl(url) ?: return
        if (authenticationResponse(normalized)) canceledAuthUrls.add(normalized)
    }

    fun onNavigationStarted(url: String?) {
        val normalized = normalizeUrl(url) ?: return
        if (authenticationResponse(normalized)) canceledAuthUrls.remove(normalized)
    }

    fun shouldIgnoreMainFrameError(
        requestUrl: String?,
        activeUrl: String?,
        recovering: Boolean,
        authFailure: Boolean
    ): Boolean {
        val normalizedRequest = normalizeUrl(requestUrl) ?: return false
        if (normalizedRequest in canceledAuthUrls) {
            canceledAuthUrls.remove(normalizedRequest)
            return true
        }
        if (!authenticationResponse(normalizedRequest)) return false
        if (recovering || authFailure) return true
        val normalizedActive = normalizeUrl(activeUrl)
        return normalizedActive != null && normalizedActive != normalizedRequest
    }

    private fun normalizeUrl(url: String?): String? =
        url?.trim()?.takeIf { it.isNotEmpty() }?.toHttpUrlOrNull()?.toString()
}
