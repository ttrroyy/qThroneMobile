package app.rawline.mobile.group

import android.annotation.SuppressLint
import android.os.Build
import android.provider.Settings
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.ktx.app
import java.util.UUID

/**
 * GetDeviceDetails (DeviceDetailsHelper.cpp:130-183) on Android (D21): ANDROID_ID (or a UUID generated once and kept
 * as a device-local setting), "Android", the release version and the device model.
 */
data class DeviceDetails(
    val hwid: String,
    val os: String,
    val osVersion: String,
    val model: String,
) {
    companion object {

        @Volatile
        private var androidId: String? = null

        @JvmStatic
        fun get(): DeviceDetails = DeviceDetails(
            hwid = hwid(),
            os = "Android",
            osVersion = Build.VERSION.RELEASE.orEmpty(),
            model = Build.MODEL.orEmpty(),
        )

        @SuppressLint("HardwareIds")
        private fun hwid(): String {
            androidId?.let { return it }
            val id = runCatching {
                Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull().orEmpty().trim()
            if (id.isNotEmpty()) {
                androidId = id
                return id
            }
            // Re-read after writing: both processes converge on the stored value.
            if (DataStore.hwidFallback.isBlank()) DataStore.hwidFallback = UUID.randomUUID().toString()
            return DataStore.hwidFallback
        }
    }
}
