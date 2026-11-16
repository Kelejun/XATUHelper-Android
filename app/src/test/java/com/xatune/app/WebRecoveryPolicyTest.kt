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
}
