package app.rawline.mobile.update

import org.junit.Assert.*
import org.junit.Test

class SemverTagTest {
    @Test fun onlyRawlineTagsAreReleases() {
        assertNull(Semver.ofTag("v3.3.0"))
        assertNull(Semver.ofTag("v1.0.0"))
        val first = Semver.ofTag("rawline-1.0.0")!!
        assertTrue(Semver.compare(Semver.ofTag("rawline-1.0.1")!!, first) > 0)
        assertTrue(Semver.compare(Semver.ofTag("rawline-1.1.0-beta.1")!!, first) > 0)
    }
}
