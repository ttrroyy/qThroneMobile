package app.qthrone.mobile.outbound.types

import org.junit.Assert.*
import org.junit.Test

class OlcRtcLinkTest {
    @Test fun installScriptLinkWithFlagNameReads() {
        val profile = OlcRtc()
        assertTrue(profile.parseFromLink("olcrtc://jitsi?datachannel@https://meet.egovm.ru/olcrtc-antinet-room#d823fa01cb3e0609b67322f7cf984c4ee2e4ce2e294936fc24ef38c9e59f4799\$🇩🇪кфвл"))
        assertEquals("🇩🇪кфвл", profile.name)
        assertEquals("meet.egovm.ru", profile.server)
    }

    @Test fun olderLinksDropTheVideoOptionsTheClientNoLongerTakes() {
        val profile = OlcRtc()
        assertTrue(profile.parseFromLink("olcrtc://jitsi?videochannel<video-w=1080&video-h=1080&video-fps=30&video-bitrate=900&video-hw=none&video-codec=qrcode>@https://meet.example.org/room#" + "a".repeat(64)))
        assertFalse(profile.exportToJson().obj("options").contains("video-bitrate"))
    }
}
