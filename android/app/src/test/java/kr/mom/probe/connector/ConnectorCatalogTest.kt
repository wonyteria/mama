package kr.mom.probe.connector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorCatalogTest {
    private val ealimi = ConnectorCatalog.site("ealimi-web")!!
    private val hiclass = ConnectorCatalog.site("hiclass-web")!!

    @Test fun allowsOnlyAllowedHttpsHosts() {
        assertTrue(ConnectorCatalog.isAllowedHttps(hiclass, "https://www.hiclass.net/"))
        assertFalse(ConnectorCatalog.isAllowedHttps(hiclass, "http://www.hiclass.net/"))
        assertFalse(ConnectorCatalog.isAllowedHttps(hiclass, "https://hiclass.net.evil.example/"))
        assertFalse(ConnectorCatalog.isAllowedHttps(hiclass, "javascript:alert(1)"))
    }

    @Test fun introIsNotClaimedAsConnected() {
        assertFalse(ConnectorCatalog.isLikelySignedIn(hiclass, hiclass.startUrl))
        assertFalse(ConnectorCatalog.isLikelySignedIn(hiclass, "https://www.hiclass.net/home"))
    }

    @Test fun ealimiSigninNeedsARealPostLoginUrl() {
        assertFalse(ConnectorCatalog.isLikelySignedIn(ealimi, ealimi.startUrl))
        assertFalse(ConnectorCatalog.isLikelySignedIn(ealimi, "https://www.ealimi.com/Main"))
        assertFalse(ConnectorCatalog.isLikelySignedIn(ealimi, "https://www.ealimi.com.evil.example/Main"))
    }

    @Test fun rejectsCredentialBearingUrlsAndAlternatePorts() {
        assertFalse(ConnectorCatalog.isAllowedHttps(ealimi, "https://user:secret@www.ealimi.com/"))
        assertFalse(ConnectorCatalog.isAllowedHttps(ealimi, "https://www.ealimi.com:8443/"))
        assertTrue(ConnectorCatalog.isAllowedHttps(ealimi, "https://www.ealimi.com:443/"))
    }

    @Test fun appAndWebsiteOverlapUsesOnlyTheAppInProductConnections() {
        assertFalse(ConnectorCatalog.shouldShowWebsite("ealimi-web"))
        assertFalse(ConnectorCatalog.shouldShowWebsite("hiclass-web"))
        assertTrue(ConnectorCatalog.shouldShowWebsite("neis-public"))
        assertTrue(ConnectorCatalog.shouldShowWebsite("neis-parent"))
        assertTrue(ConnectorCatalog.shouldShowWebsite("school-website-snjj"))
        assertTrue(ConnectorCatalog.preferredAppPackage("ealimi-web") == "com.ewut.allealimi")
        assertTrue(ConnectorCatalog.preferredAppPackage("hiclass-web") == "com.iscreammedia.app.hiclass.android")
    }
}
