package app.rawline.mobile.outbound.types

import app.rawline.mobile.outbound.BuildContext
import app.rawline.mobile.outbound.OutboundFactory
import org.junit.Assert.*
import org.junit.Test

class HttpTest {
    @Test fun httpsKeepsTlsThroughImportStorageAndExport() {
        val imported=OutboundFactory.parseLink("https://192.0.2.1:4443/#HTTPS") as Http
        assertTrue(imported.tls.enabled)
        assertEquals("HTTPS",imported.displayType())
        val restored=Http()
        assertTrue(restored.parseFromJson(imported.exportToJson()))
        assertTrue(restored.build(BuildContext()).json.obj("tls").bool("enabled"))
        assertTrue(restored.exportToLink().startsWith("https://"))
        assertFalse(restored.tls.insecure)
    }
    @Test fun plainHttpStaysPlain() {
        val profile=Http()
        assertTrue(profile.parseFromLink("http://192.0.2.1:8080"))
        assertEquals("HTTP",profile.displayType())
        assertFalse(profile.tls.enabled)
    }
}
