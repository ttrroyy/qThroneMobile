package app.rawline.mobile

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TrafficFormatTest {
    @Test fun subscriptionQuotaUsesBinaryUnits() {
        assertEquals("200 GB",TrafficFormat.format(200L * 1024 * 1024 * 1024,Locale.US))
        assertEquals("1.36 GB",TrafficFormat.format((1.36 * 1024 * 1024 * 1024).toLong(),Locale.US))
        assertEquals("1,36 ГБ",TrafficFormat.format((1.36 * 1024 * 1024 * 1024).toLong(),Locale.forLanguageTag("ru")))
    }
    @Test fun emptyAndSmallCountersKeepTheirPrecision() {
        assertEquals("0 B",TrafficFormat.format(0,Locale.US))
        assertEquals("1.01 KB",TrafficFormat.format(1034,Locale.US))
    }
}
