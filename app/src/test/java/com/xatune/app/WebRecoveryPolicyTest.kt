package com.xatune.app

import com.xatune.app.platform.WebRecoveryPolicy
import org.junit.Assert.*
import org.junit.Test

class WebRecoveryPolicyTest {
    @Test fun onlyFinalMainFrameExactAuthenticationHostTriggers() {
        val policy = WebRecoveryPolicy()
        assertTrue(policy.authenticationResponse("https://authserver.xatu.edu.cn/authserver/login"))
        assertFalse(policy.authenticationResponse("https://authserver.xatu.edu.cn/authserver/login", mainFrame = false))
        assertFalse(policy.authenticationResponse("https://authserver.xatu.edu.cn/authserver/login", status = 302))
        assertFalse(policy.authenticationResponse("https://authserver.xatu.edu.cn.evil.test/"))
        assertFalse(policy.authenticationResponse("https://ehall.xatu.edu.cn/path?next=authserver.xatu.edu.cn"))
        assertFalse(policy.authenticationResponse("invalid"))
    }
    @Test fun automaticRecoveryIsBoundedAndManualPagesAreExcluded() {
        val automatic = WebRecoveryPolicy(); assertTrue(automatic.begin()); assertFalse(automatic.begin())
        val manual = WebRecoveryPolicy(true); assertFalse(manual.begin()); assertFalse(manual.authenticationResponse("https://authserver.xatu.edu.cn/"))
    }
    @Test fun canceledAuthPageErrorsAreIgnoredAcrossRecoveryAndReloadWithoutMaskingRealFailures() {
        val authUrl = "https://authserver.xatu.edu.cn/authserver/login?service=https://ehall.xatu.edu.cn/"
        val entryUrl = "https://ehall.xatu.edu.cn/xsfw/sys/swmqdzsapp/*default/index.do"
        val policy = WebRecoveryPolicy()

        // First auth page stopped during automatic recovery: error during recovery is ignored.
        assertTrue(policy.begin())
        policy.markStopped(authUrl)
        assertTrue(policy.shouldIgnoreMainFrameError(authUrl, authUrl, recovering = true, authFailure = false))

        // Late error from the stopped auth page arriving after the entry URL starts reloading is ignored.
        policy.markStopped(authUrl)
        policy.onNavigationStarted(entryUrl)
        assertTrue(policy.shouldIgnoreMainFrameError(authUrl, entryUrl, recovering = false, authFailure = false))

        // A real network failure on the reloaded entry page is NOT ignored.
        assertFalse(policy.shouldIgnoreMainFrameError(entryUrl, entryUrl, recovering = false, authFailure = false))

        // Second auth page stopped when automatic recovery is already exhausted: does not overwrite authFailure.
        assertFalse(policy.begin())
        policy.onNavigationStarted(authUrl)
        policy.markStopped(authUrl)
        assertTrue(policy.shouldIgnoreMainFrameError(authUrl, authUrl, recovering = false, authFailure = true))
    }
}
