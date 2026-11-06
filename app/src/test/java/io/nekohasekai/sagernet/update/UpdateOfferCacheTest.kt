package app.rawline.mobile.update

import org.junit.Assert.*
import org.junit.Test

class UpdateOfferCacheTest {
    private val asset = UpdateChecker.Asset("Rawline.apk", 123L, "https://github.com/example/Rawline.apk")
    private val release = UpdateChecker.Release("v1.9.1", "https://github.com/example/releases/v1.9.1", "Notes\nSecond line", false, listOf(asset))

    @Test fun installableOfferSurvivesRestartWithDownloadVerificationMetadata() {
        val offer = UpdateChecker.Offer(release, "1.9.1", 191L, "arm64-v8a", asset, UpdateChecker.ManifestAsset(asset.name, asset.size, "abc"), null)
        val encoded = UpdateOfferCache.encode(UpdateOfferCache.Snapshot(190L, false, 12345L, true, offer))
        val restored = UpdateOfferCache.decode(encoded, 190L, false)!!
        assertEquals(12345L, restored.lastAttempt)
        assertTrue(restored.lastSucceeded)
        assertTrue(restored.offer!!.installable)
        assertEquals(offer.expected!!.sha256, restored.offer.expected!!.sha256)
        assertEquals(offer.apk!!.url, restored.offer.apk!!.url)
        assertEquals(offer.release.body, restored.offer.release.body)
        assertEquals("arm64-v8a", restored.offer.abi)
    }

    @Test fun cachedOfferIsInvalidatedByAnInstalledUpdateOrAChannelChange() {
        val encoded = UpdateOfferCache.encode(UpdateOfferCache.Snapshot(190L, false, 12345L, true, null))
        assertNull(UpdateOfferCache.decode(encoded, 191L, false))
        assertNull(UpdateOfferCache.decode(encoded, 190L, true))
        assertNull(UpdateOfferCache.decode("invalid", 190L, false))
        assertNotNull(UpdateOfferCache.decode(encoded, 190L, false))
    }

    @Test fun legacyBrowserOfferAndRetryTimestampRoundTrip() {
        val offer = UpdateChecker.Offer(release, "1.9.1", 0L, null, null, null, "No manifest")
        val encoded = UpdateOfferCache.encode(UpdateOfferCache.Snapshot(190L, true, 12345L, false, offer))
        val restored = UpdateOfferCache.decode(encoded, 190L, true)!!
        assertFalse(restored.lastSucceeded)
        assertFalse(restored.offer!!.installable)
        assertNull(restored.offer.abi)
        assertEquals("No manifest", restored.offer.browserReason)
    }
}
