package com.xatune.app.platform

import com.xatune.app.platform.CampusWebNavigation.Destination.*
import org.junit.Assert.assertEquals
import org.junit.Test

class CampusWebNavigationTest {
    @Test fun cashierRedirectsAndBlankWindowsStayInWebViewWithoutGesture() {
        for (url in listOf("https://cashier.example/pay", "http://self.xatu.edu.cn/Self/", "about:blank", "about:blank#payment")) {
            assertEquals(url, WEB, CampusWebNavigation.destination("netfee", url, false))
        }
    }

    @Test fun paymentAppRedirectsDoNotRequireDirectGesture() {
        for (page in listOf("netfee", "charge")) for (url in listOf("weixin://wap/pay?order=fixture", "alipays://platformapi/startapp", "alipay://pay", "WEIXIN://wap/pay")) {
            assertEquals(url, APP, CampusWebNavigation.destination(page, url, false))
        }
    }

    @Test fun unrelatedPagesCannotAutomaticallyLaunchPaymentApps() {
        assertEquals(BLOCKED, CampusWebNavigation.destination("leave", "weixin://wap/pay", false))
        assertEquals(BLOCKED, CampusWebNavigation.destination("netfee", "tel:12345", false))
        assertEquals(APP, CampusWebNavigation.destination("netfee", "tel:12345", true))
    }

    @Test fun unsupportedSchemesAreBlockedEvenWithGesture() {
        for (url in listOf("intent://pay#Intent;scheme=weixin;end", "file:///etc/passwd", "javascript:alert(1)", "data:text/html,fixture", "unknown://pay", "https://", "about:config")) {
            assertEquals(url, BLOCKED, CampusWebNavigation.destination("netfee", url, true))
        }
    }
}
