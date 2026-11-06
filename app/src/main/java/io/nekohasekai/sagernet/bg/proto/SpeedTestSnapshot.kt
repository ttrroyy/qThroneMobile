package app.rawline.mobile.bg.proto

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * One live sample of the speed test in flight (ITestSessionCallback.onSpeedProgress): [mode] is a
 * SpeedTestSettings mode name, [stage] one of the STAGE_ values, [serverCountry] the country name speedtest.net reports
 * (CountryNames.toCode maps it), [downloadSpeed]/[uploadSpeed] the core's rate strings ("12.34Mbps").
 */
@Parcelize
data class SpeedTestSnapshot(
    val profileId: Long,
    val profileName: String,
    val mode: String,
    val stage: String,
    val downloadBitsPerSecond: Long = 0,
    val uploadBitsPerSecond: Long = 0,
    val downloadBytes: Long = 0,
    val uploadBytes: Long = 0,
    val latencyMs: Long = 0,
    val serverName: String = "",
    val serverCountry: String = "",
    val error: String = "",
    val cancelled: Boolean = false,
    val done: Boolean = false,
    val downloadSpeed: String = "",
    val uploadSpeed: String = "",
) : Parcelable {

    companion object {
        const val STAGE_DISCOVERY = "discovery"
        const val STAGE_LATENCY = "latency"
        const val STAGE_DOWNLOAD = "download"
        const val STAGE_UPLOAD = "upload"
        const val STAGE_COMPLETE = "complete"
        const val STAGE_CANCELLED = "cancelled"
        const val STAGE_ERROR = "error"
    }
}
