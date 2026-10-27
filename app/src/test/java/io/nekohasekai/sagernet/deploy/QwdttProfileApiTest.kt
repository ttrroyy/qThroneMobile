package app.qthrone.mobile.deploy

import org.junit.Assert.assertEquals
import org.junit.Test

/** The signed form the qWDTT server's authenticateProfileRequest accepts. */
class QwdttProfileApiTest {
    @Test fun proofMatchesTheServer() {
        val form = QwdttProfileApi.proof("unbind", "pass", "dev", "n1")
        assertEquals("6a04f6c1b45b1aeb0aac823892ffe31c5624590bd115878d13ee2dbc4291347c", form["key_id"])
        assertEquals("b0c8a42aa100c367ff315b9cef4cd4dfd4b6fa3ea3e845b5cb2c9e6b05117d89", form["proof"])
        assertEquals("dev", form["device_id"])
        assertEquals("n1", form["nonce"])
    }
}
