package app.rawline.mobile.bg.carrier
import org.junit.Assert.*
import org.junit.Test
class VpnListenerPolicyTest {
    @Test fun tunOnlyAllowed() { VpnListenerPolicy.check("""{"inbounds":[{"type":"tun"}]}""") }
    @Test fun unauthenticatedCustomMixedIsRejected() {
        try { VpnListenerPolicy.check("""{"inbounds":[{"type":"mixed","listen":"127.0.0.1"}]}""");fail("unauthenticated proxy allowed") } catch(_:IllegalStateException) {}
    }
    @Test fun authenticatedLoopbackAccepted() {
        VpnListenerPolicy.check("""{"inbounds":[{"type":"socks","listen":"127.0.0.1","users":[{"username":"bridge","password":"01234567890123456789012345678901"}]}]}""")
    }
    @Test fun dashboardWithoutSecretRejected() {
        try { VpnListenerPolicy.check("""{"experimental":{"clash_api":{"external_controller":"127.0.0.1:9090"}}}""");fail("API without secret allowed") } catch(_:IllegalStateException) {}
    }
    @Test fun apiServiceCannotReopenUnauthenticatedDashboard() {
        assertThrows(IllegalStateException::class.java) {
            VpnListenerPolicy.check("""{"services":[{"type":"api","listen":"127.0.0.1","listen_port":9091}]}""")
        }
        VpnListenerPolicy.check("""{"services":[{"type":"api","listen":"127.0.0.1","listen_port":9091,"secret":"01234567890123456789012345678901"}]}""")
        VpnListenerPolicy.check("""{"services":[{"type":"api","listen_port":0}]}""")
    }
    @Test fun customV2rayApiIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            VpnListenerPolicy.check("""{"experimental":{"v2ray_api":{"listen":"127.0.0.1:8080"}}}""")
        }
    }
    @Test fun xrayManagementApiIsRejectedEvenWithoutExplicitListen() {
        assertThrows(IllegalStateException::class.java) {
            VpnListenerPolicy.checkXray("""{"api":{"tag":"api","services":["HandlerService","StatsService"]}}""")
        }
    }
    @Test fun xrayNoAuthAndWeakCredentialsAreRejected() {
        for (settings in listOf("""{"auth":"noauth"}""", """{"auth":"password","accounts":[{"user":"test","pass":"short"}]}""")) {
            assertThrows(IllegalStateException::class.java) {
                VpnListenerPolicy.checkXray("""{"inbounds":[{"protocol":"socks","listen":"127.0.0.1","settings":$settings}]}""")
            }
        }
    }
    @Test fun generatedXrayBridgeIsAcceptedAndWildcardIsRejected() {
        val config = """{"inbounds":[{"protocol":"socks","listen":"127.0.0.1","settings":{"auth":"password","udp":true,"accounts":[{"user":"bridge","pass":"01234567890123456789012345678901"}]}}]}"""
        VpnListenerPolicy.checkXray(config)
        assertThrows(IllegalStateException::class.java) { VpnListenerPolicy.checkXray(config.replace("127.0.0.1", "0.0.0.0")) }
    }
}
