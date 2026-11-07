package app.rawline.mobile.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** The routing profiles of github.com/throneproj/routeprofiles still use Throne's links. */
class ThroneRouteProfilesTest {
    // Profile_Russia from the profile branch, as downloaded.
    private val remote = "throne://remoteroute/aHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL3Rocm9uZXByb2ovcm91dGVwcm9maWxlcy9wcm9maWxlL0J5cGFzc19SdXNzaWEjQnlwYXNzIFJ1c3NpYQpodHRwczovL3Jhdy5naXRodWJ1c2VyY29udGVudC5jb20vdGhyb25lcHJvai9yb3V0ZXByb2ZpbGVzL3Byb2ZpbGUvUHJveHlfUnVzc2lhX0Jsb2NrZWQjUHJveHkgUnVzc2lhIEJsb2NrZWQKaHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL3Rocm9uZXByb2ovcm91dGVwcm9maWxlcy9wcm9maWxlL1Byb3h5X0FudGl6YXByZXQjUHJveHkgQW50aXphcHJldApodHRwczovL3Jhdy5naXRodWJ1c2VyY29udGVudC5jb20vdGhyb25lcHJvai9yb3V0ZXByb2ZpbGVzL3Byb2ZpbGUvUHJveHlfUmVmaWx0ZXIjUHJveHkgUmVmaWx0ZXI"
    private val route = "throne://route/eyJraW5kIjoidGhyb25lLXJvdXRlLXByb2ZpbGUiLCJuYW1lIjoiQnlwYXNzIFJ1c3NpYSIsImRlZmF1bHRfb3V0Ym91bmQiOiJwcm94eSIsInJ1bGVzIjpbeyJhY3Rpb24iOiJyb3V0ZSIsImlwX2lzX3ByaXZhdGUiOnRydWUsIm91dGJvdW5kIjoiZGlyZWN0IiwibmFtZSI6InByaXZhdGUiLCJ0eXBlIjoiY3VzdG9tIn1dfQ"

    @Test fun repositoryBundleListsItsProfiles() {
        val entries = RouteShare.fromRemoteRoutesLink(remote)
        assertNotNull(entries)
        assertEquals(listOf("Bypass Russia", "Proxy Russia Blocked", "Proxy Antizapret", "Proxy Refilter"), entries!!.map { it.name })
    }

    @Test fun throneRouteLinkImports() {
        val imported = RouteShare.fromShareInput(route) { null }
        assertNotNull(imported.fatal, imported.profile)
        assertEquals("Bypass Russia", imported.profile!!.name)
    }
}
