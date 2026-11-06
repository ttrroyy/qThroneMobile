package app.rawline.mobile.aidl

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class TrafficDataBatch(
    val items: ArrayList<TrafficData> = arrayListOf(),
) : Parcelable
