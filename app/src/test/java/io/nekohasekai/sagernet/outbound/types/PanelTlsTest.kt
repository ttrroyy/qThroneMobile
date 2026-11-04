package app.qthrone.mobile.outbound.types

import app.qthrone.mobile.outbound.BuildContext
import app.qthrone.mobile.outbound.OutboundFactory
import org.junit.Assert.*
import org.junit.Test

class PanelTlsTest {
    @Test fun panelCipherSuitesUseStandardTlsWithoutDisablingVerification() {
        val profile = OutboundFactory.parseLink("trojan://password@example.org:443?security=tls&type=grpc&serviceName=tunnel&fp=unsafe&cs=TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256")!!
        val tls = profile.getTls()!!
        assertEquals(listOf("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"),tls.cipher_suites)
        val built = tls.build(BuildContext(utlsFingerprint="chrome"))
        assertFalse(built.contains("utls"))
        assertFalse(built.bool("insecure"))
        assertEquals("unsafe",tls.utls.fingerPrint)
        assertTrue(profile.exportToLink().contains("fp=unsafe"))
    }
}
