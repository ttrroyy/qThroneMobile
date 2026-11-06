package app.rawline.mobile.bg
import org.junit.Assert.*
import org.junit.Test
class TunApplicationPolicyTest {
    @Test fun nativeProbesStayOutsideRegularTunnel() {
        assertEquals(listOf("browser","rawline"),TunApplicationPolicy.resolve("rawline",emptyList(),listOf("browser")).excluded)
    }
    @Test fun includedAppsCannotCaptureNativeProbe() {
        val result=TunApplicationPolicy.resolve("rawline",listOf("rawline","browser","browser"),emptyList())
        assertEquals(listOf("browser"),result.included)
        assertTrue(result.excluded.isEmpty())
    }
    @Test(expected=IllegalArgumentException::class) fun appOnlySelectionCannotAccidentallyRouteEverything() {
        TunApplicationPolicy.resolve("rawline",listOf("rawline"),emptyList())
    }
}
