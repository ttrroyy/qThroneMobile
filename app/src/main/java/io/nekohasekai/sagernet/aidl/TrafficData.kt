package app.rawline.mobile.aidl

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class TrafficData(
    var id: Long = 0L,
    var tx: Long = 0L,
    var rx: Long = 0L,
) : Parcelable
