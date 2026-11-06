package moe.matsuri.nb4a

import android.content.Context
import app.rawline.mobile.R
import app.rawline.mobile.ktx.app
import app.rawline.mobile.ktx.getColorAttr

// Settings for all protocols
object Protocols {

    // Display

    @Suppress("UNUSED_PARAMETER")
    fun Context.getProtocolColor(type: String): Int {
        return getColorAttr(R.attr.accentOrTextSecondary)
    }

    // Test

    fun genFriendlyMsg(msg: String): String {
        val msgL = msg.lowercase()
        return when {
            msgL.contains("timeout") || msgL.contains("deadline") -> {
                app.getString(R.string.connection_test_timeout_error)
            }

            msgL.contains("refused") || msgL.contains("closed pipe") -> {
                app.getString(R.string.connection_test_refused)
            }

            else -> msg
        }
    }

}
