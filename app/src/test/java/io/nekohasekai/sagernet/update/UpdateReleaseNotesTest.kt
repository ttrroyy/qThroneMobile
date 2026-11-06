package app.rawline.mobile.update

import org.junit.Assert.*
import org.junit.Test

class UpdateReleaseNotesTest {
    private val body = "Release notes\n\n" + UpdateReleaseNotes.START +
        """{"schema":1,"versionCode":69999,"assets":{"arm64-v8a":{"sha256":"abc"}}}""" + UpdateReleaseNotes.END

    @Test fun githubBodyProvidesVerificationDataWithoutShowingItToUsers() {
        assertEquals(69999L, UpdateReleaseNotes.metadata(body)!!.getLong("versionCode"))
        assertEquals("abc", UpdateReleaseNotes.metadata(body)!!.getJSONObject("assets").getJSONObject("arm64-v8a").getString("sha256"))
        assertEquals("Release notes", UpdateReleaseNotes.visible(body))
        assertTrue(UpdateReleaseNotes.containsMetadata(body))
    }

    @Test fun oldNotesAndTrailingTextStayReadable() {
        assertNull(UpdateReleaseNotes.metadata("Old release"))
        assertEquals("Old release", UpdateReleaseNotes.visible("Old release"))
        assertEquals("Release notes\n\n\nMore notes", UpdateReleaseNotes.visible(body + "\nMore notes"))
    }

    @Test fun ambiguousTruncatedAndOversizedMetadataIsRejected() {
        for (invalid in listOf(body + body, body.substringBefore(UpdateReleaseNotes.END),
            UpdateReleaseNotes.START + " ".repeat(256 * 1024 + 1) + UpdateReleaseNotes.END,
            UpdateReleaseNotes.START + "invalid JSON" + UpdateReleaseNotes.END)) {
            assertTrue(runCatching { UpdateReleaseNotes.metadata(invalid) }.isFailure)
        }
        assertEquals("Release notes", UpdateReleaseNotes.visible(body.substringBefore(UpdateReleaseNotes.END)))
    }
}
