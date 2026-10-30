package app.qthrone.mobile.outbound.import

import app.qthrone.mobile.outbound.types.WireGuard
import org.junit.Assert.*
import org.junit.Test

class AmneziaDnsImportTest {
    private val conf = "[Interface]\nAddress = 10.8.1.2/32\nDNS = \$PRIMARY_DNS, \$SECONDARY_DNS\nPrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\n" +
        "[Peer]\nPublicKey = BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=\nAllowedIPs = 0.0.0.0/0\nEndpoint = 192.0.2.10:51820\n"

    private fun link(root: String) = "vpn://" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(root.toByteArray())

    private fun container() = """{"container":"amnezia-awg","awg":{"last_config":${org.json.JSONObject.quote(conf)}}}"""

    @Test fun amneziaExportFillsTheDnsPlaceholdersFromItsDnsFields() {
        val result = ProfileImport.parse(link("""{"dns1":"172.29.172.254","dns2":"10.8.1.1","containers":[${container()}]}"""))
        val profile = result.outbounds.single() as WireGuard
        assertEquals(listOf("172.29.172.254", "10.8.1.1"), profile.dns)
        assertEquals("172.29.172.254", profile.dnsServer())
    }

    @Test fun amneziaExportWithoutDnsFieldsTakesAmneziaDefaults() {
        val profile = ProfileImport.parse(link("""{"containers":[${container()}]}""")).outbounds.single() as WireGuard
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), profile.dns)
    }
}
