package app.rawline.mobile.deploy

import app.rawline.mobile.outbound.types.Csqtt
import app.rawline.mobile.outbound.types.Qwdtt
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeployConfigTest {
    private fun config(kind: String)=JSONObject().put("type",kind).put("name","VPS + test")
        .put("server","2001:db8::1").put("server_port",if(kind=="qwdtt") 56000 else 46000)
        .put("password",if(kind=="qwdtt") "secret+&!" else "secret123")
        .put("device_id","stable-deployed-owner").put("hashes",org.json.JSONArray())

    private fun keys(link: String)=link.substringAfter('?').split('&').map { it.substringBefore('=') }

    @Test fun qwdttLinkMatchesTheOriginalAdminLink() {
        val link=DeployConfig.link(config("qwdtt"),"stable-deployed-owner")
        assertTrue(link.startsWith("qwdtt://config?"))
        assertEquals(listOf("name","peer","hashes","workers","port","pass"),keys(link))
        assertTrue(link.contains("&workers=18&port=9000&"))
        val parsed=Qwdtt()
        assertTrue(parsed.parseFromLink(link))
        assertEquals("2001:db8::1",parsed.server)
        assertEquals(56000,parsed.serverPort)
        assertEquals("VPS + test",parsed.name)
        assertEquals("secret+&!",parsed.password)
        assertEquals(18,parsed.workers)
        assertEquals("wg",parsed.mode)
        assertTrue(parsed.hashes.isEmpty())
    }
    @Test fun csqttLinkMatchesTheOriginalPanelLink() {
        val link=DeployConfig.link(config("csqtt"),"stable-deployed-owner")
        assertTrue(link.startsWith("csqtt://"))
        assertFalse(link.contains("password="))
        assertNotNull(app.rawline.mobile.outbound.link.parseCsqttLink(link))
        val parsed=Csqtt()
        assertTrue(parsed.parseFromLink(link))
        assertEquals("2001:db8::1",parsed.server)
        assertEquals(46000,parsed.serverPort)
        assertEquals("secret123",parsed.password)
        assertTrue(parsed.hashes.isEmpty())
    }
    @Test fun serversBoundToAnotherIdentityKeepItInTheLink() {
        for(kind in listOf("qwdtt")) {
            val link=DeployConfig.link(config(kind),"this-phone")
            assertTrue(link.endsWith("&device_id=stable-deployed-owner"))
            val parsed=if(kind=="csqtt") Csqtt() else Qwdtt()
            assertTrue(parsed.parseFromLink(link))
            assertEquals("stable-deployed-owner",parsed.deviceId)
        }
    }
    @Test fun legacySavedJsonDisplaysAsAnImportableLink() {
        val data=config("qwdtt")
        assertEquals(DeployConfig.link(data),DeployConfig.display(data.toString(2)))
        assertEquals("",DeployConfig.display(""))
        val link=DeployConfig.link(data)
        assertEquals(link,DeployConfig.display(link))
    }
}
