package kr.mom.probe.connector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorCatalogTest {
    private val neis = ConnectorCatalog.site("neis-parent")!!
    private val ealimi = ConnectorCatalog.site("ealimi-web")!!

    @Test fun allowsOnlyHttpsNeisHosts() {
        assertTrue(ConnectorCatalog.isAllowedHttps(neis, "https://parents.neis.go.kr/csp-prnt/"))
        assertTrue(ConnectorCatalog.isAllowedHttps(neis, "https://auth.neis.go.kr/login"))
        assertFalse(ConnectorCatalog.isAllowedHttps(neis, "http://parents.neis.go.kr/"))
        assertFalse(ConnectorCatalog.isAllowedHttps(neis, "https://neis.go.kr.evil.example/"))
        assertFalse(ConnectorCatalog.isAllowedHttps(neis, "javascript:alert(1)"))
    }

    @Test fun introIsNotClaimedAsConnected() {
        assertFalse(ConnectorCatalog.isLikelySignedIn(neis, neis.startUrl))
        assertFalse(ConnectorCatalog.isLikelySignedIn(neis, "https://parents.neis.go.kr/csp-prnt/#/prn-main/home"))
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
}
