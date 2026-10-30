package app.qthrone.mobile.outbound.types

import app.qthrone.mobile.bg.carrier.CarrierBridgeCredentials
import app.qthrone.mobile.outbound.json.JsonObject
import app.qthrone.mobile.outbound.import.ProfileImport
import org.junit.Assert.*
import org.junit.Test

/** Links from OpenFlux's share package: frozen ones from share/compat_test.go and ones share.Make writes today. */
class OpenFluxLinkTest {
    private fun read(link: String) = OpenFlux().takeIf { it.parseFromLink(link) }
    private fun config(profile: OpenFlux) = profile.privateConfig(1080, 1081, CarrierBridgeCredentials("user", "pass"))
    private fun transports(profile: OpenFlux) = config(profile).array("transports").filterIsInstance<JsonObject>()

    @Test fun frozenUpstreamLinksStillRead() {
        val cups = read("openflux://v1/TMpBCsIwEAXQu_y1aTCJm4B4EHEhNehAnAmTaUVK7y6ii771W2B65d5ErSOfF9i7FWSMU-vClbhgh0krMh5mrWfvia3oTOU1fNPwW77SXNwoN-K7P6nI87j_cyGE4GKM0aWUkjtsYL2snwAAAP__")!!
        assertEquals("cupsonline", transports(cups).single().string("type"))
        assertFalse(config(cups).bool("negotiate"))
        val mailru = read("openflux://v1/qlbKS8xNVbJS8k3MzNErKlVIK8pMzUtR0lEqKUrMKy7ILyopVrKKrlYqqSwAKctNzMwpKlXSUSotylGyUnJMck5xTXPPMNT3zPLO8c3zLzBSqo2tBQQAAP__")!!
        assertEquals("Mail.ru friend", mailru.name)
        assertEquals("AbCdEfGh1/IjKlMnOp2", transports(mailru).single().string("url"))
        val session = read("openflux://v1/rI_BaiwhEEV_pSnonWPb7TweCCFkM-QfQha2FhlJj0pZTkaG_vdgAoHssyw4dc-9d4j2gmDgOV0QBER8SxwsIximigIKOkIGAy4RoePhnKjgsFpmpDYUtnnrjy5FxlsHz8y5mGnyyRXZbPR4k1S_zuka8OOx0vbQ7MGH8n7Idd2CG_XTuJzG5WRXBwKYbCw5ERcwL3fglnvD63cWCKi0_YknU0gUuIGZldrFj8mHvvQX8E8J8MF276K0VHKetfxvjketYX_dPwMAAP__")!!
        assertTrue(config(session).bool("negotiate"))
        assertEquals("correct horse battery staple", config(session).string("secret"))
        assertEquals("203.0.113.7", session.server)
        assertEquals(4433, session.serverPort)
        val legacy = read("openflux://v1/JMk7DsIwDAbgu_xz1PB--CqIIdgGhqqJbFdqVPXuDMzfCq6iDMKon8IdCa5sGiDs9ofj6Xy53u7lxaJvJISVyVu1cNBjRfSmIPQyiS5ImG0E4RvRnHKWyj78bbA5L9ie2y8AAP__")!!
        assertEquals("legacy", config(legacy).string("codec"))
        assertNotNull(read("openflux://v1/PY0xCwIxDEb_S2a94loQNydHN3Eod1EDvaSkaUWO---2HJox7328BTjMCB4exYoi7MA0cE6ilsHfFrBP6ngsKQtH4q4Uje31MkvZO0dsqJXwPXRp2CwXqeJ-lIn46U4qMh9DuzbeQtdf5UwYJ_CH9f5Hki5YsSUWCJ2sXw"))
    }

    @Test fun telemostWithoutRoomIsAcceptedAndUnknownCarriersAreNot() {
        // A user's export: Telemost without a link starts a conference; mtslink is not a carrier OpenFlux knows.
        val telemost = read("openflux://v1/NY5LCsIwGISvIlkpWMhD08cB3HoAcZGkUyy0SUl_RZHe3b-iu-GbYWbeIroRohHnCfE03J-b1BWj6-NmSxgwppl2Yi9CahE45R2FG1omM0IGMdJWHW1n0Bpt4I2GqkOtnfQIZSU1DjX7JSpZKoadbWVX64OyXgZjAP9tj4TnWvYbLx6KMWUX5yllmkVzeQt6TevR_y0O3PPAgMWU-5R7eonGHpfr8gE")!!
        assertEquals("", transports(telemost).single().string("url"))
        assertNull(read("openflux://v1/LY7LbsMgEEV_JWLVSiXmYYPxB2TbD6i64DEoVm1AgKNYUf69uM1qNOeM7twHCnoFNKHPBOGybPdT9HjVczi9rbUsc_h5Rx_IRge2HRld7RVcIwVshtoQE3QQnoPjjIPhDKiyimliwMqRMOhV8xJGImmDXjjiFeupMMRyDmD-0kOF-xH2-o1vtOGadSgp5lrQ9PVAdU9Hz1er5re8tP1aaypT1637uSl8uHPeOhN1duV_dEzZ3go9YC-Exz1QiUfie-yEHJxx0jo5tMCU55jnuqOJEvL8fv4C"))
    }

    @Test fun currentCarriersAreAccepted() {
        // share.NodeConfig: what the node wizard prints, a Volga document with direct as the backup.
        val node = read("openflux://v1/jM7BSsQwFIXhVwlnHZJ0OiJmJz6GuLgmVxvQJNzcypSh7y5lQHA328PPx7mi0jcjorbMsKj82bSQMqLKyhaDk7AigsxYSDib22Lah5lPJi0klJRlwCK1qnw54kW1j-h9bmm4jWrmi5PVcy7qs39-f4GFCtXRm-hAfL1Ct378-LnVsFjl606pS2lSdEOcQtjtn5WLcNJ_wUOwyIUO-RRmF9w0ze4xPp3PM_a3_XcA")!!
        assertEquals(listOf("vyandex", "direct"), transports(node).map { it.string("type") })
        assertEquals("https://docs.yandex.ru/edit/d/AbC", config(node).string("context"))
        val telemost = read("openflux://v1/lI5NSsAwFISvEmYdGm39zVXExSN9WqVNyssrtJQu9CjeoSvFM6Q3kiIewN0wzHx8KyINDI_yUfbyWfbyVb6Pt-MdFpGfk76QMrzKxBaZg7DCg0zuSLg1v41JT6apTehIKChLhkVIUXk-x53qmL1zyj0PKWu1UGx5rmRyr-6ybq6ub27v7i_OBAsVinlMohn-YYUu42n394XFJP0_odvj9jMA")!!
        assertEquals("Телемост", telemost.name)
        assertEquals("telemost", transports(telemost).single().string("type"))
        assertEquals("avito", transports(read("openflux://v1/TMwxCgJBDIXhq4RXj412exWxiDGygm6WlyjKMneXwcb25-PfkG70wgSVnJV-kV-RuMphLzYr1cqZaLBYyt8DM-Kx07OhoahLrsFKTMcN9Vl93F63CjQ8ef_n_dS_AwA")!!).single().string("type"))
        val stream = read("openflux://v1/JMnBCsMgDAbgd_nPstzzKmMH2YQV1IQkQov47kV6_iaa_AoYHlZyQ0JY7q5i4eD3RFy6-TvUpdejFyQMq2D8I9SZaNPrMTKRRifWZ90DAA")!!
        assertEquals("stream", config(stream).string("mode"))
    }

    @Test fun contextIsNeverInvented() {
        val cups = read("openflux://v1/TMpBCsIwEAXQu_y1aTCJm4B4EHEhNehAnAmTaUVK7y6ii771W2B65d5ErSOfF9i7FWSMU-vClbhgh0krMh5mrWfvia3oTOU1fNPwW77SXNwoN-K7P6nI87j_cyGE4GKM0aWUkjtsYL2snwAAAP__")!!
        assertFalse(config(cups).contains("context"))
        val stored = OpenFlux()
        assertTrue(stored.parseFromJson(JsonObject().apply {
            this["type"] = "openflux"; this["negotiate"] = true; this["secret"] = "a shared secret of 32 characters"
            this["context"] = "http://openflux"
            this["transports"] = app.qthrone.mobile.outbound.json.JsonInput.parseValue("""[{"type":"yandex","url":"https://disk.yandex.ru/i/abc"}]""")
        }))
        assertFalse(config(stored).contains("context"))
    }

    @Test fun linksSurviveTheWayFromAChat() {
        val link = "openflux://v1/rI_BaiwhEEV_pSnonWPb7TweCCFkM-QfQha2FhlJj0pZTkaG_vdgAoHssyw4dc-9d4j2gmDgOV0QBER8SxwsIximigIKOkIGAy4RoePhnKjgsFpmpDYUtnnrjy5FxlsHz8y5mGnyyRXZbPR4k1S_zuka8OOx0vbQ7MGH8n7Idd2CG_XTuJzG5WRXBwKYbCw5ERcwL3fglnvD63cWCKi0_YknU0gUuIGZldrFj8mHvvQX8E8J8MF276K0VHKetfxvjketYX_dPwMAAP__"
        val body = link.removePrefix(OpenFlux.PREFIX)
        val mangled = listOf(
            "  $link\n",
            link + "=".repeat((4 - body.length % 4) % 4),
            OpenFlux.PREFIX + body.replace('-', '+').replace('_', '/'),
            OpenFlux.PREFIX + body.substring(0, 30) + "​" + body.substring(30),
        )
        for (input in mangled) assertNotNull(input, read(input))
        assertEquals(1, ProfileImport.parse("\n$link\n").outbounds.size)
    }

    @Test fun invalidLinksAreRejected() {
        val good = "openflux://v1/TMpBCsIwEAXQu_y1aTCJm4B4EHEhNehAnAmTaUVK7y6ii771W2B65d5ErSOfF9i7FWSMU-vClbhgh0krMh5mrWfvia3oTOU1fNPwW77SXNwoN-K7P6nI87j_cyGE4GKM0aWUkjtsYL2snwAAAP__"
        assertNull(read("openflux://v2/abc"))
        assertNull(read(good.uppercase()))
        assertNull(read(good.dropLast(30)))
    }

    @Test fun exportedLinkReadsBack() {
        val node = read("openflux://v1/jM7BSsQwFIXhVwlnHZJ0OiJmJz6GuLgmVxvQJNzcypSh7y5lQHA328PPx7mi0jcjorbMsKj82bSQMqLKyhaDk7AigsxYSDib22Lah5lPJi0klJRlwCK1qnw54kW1j-h9bmm4jWrmi5PVcy7qs39-f4GFCtXRm-hAfL1Ct378-LnVsFjl606pS2lSdEOcQtjtn5WLcNJ_wUOwyIUO-RRmF9w0ze4xPp3PM_a3_XcA")!!
        val again = read(node.exportToLink())!!
        assertEquals(config(node).toCompact(), config(again).toCompact())
        assertEquals(node.name, again.name)
    }
}
