package app.rawline.mobile.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.provider.Settings

/** Whether Rawline is the always-on VPN of this user, readable from any process (API 29+). */
object AlwaysOnVpn {

    enum class State { UNKNOWN, OFF, ON, LOCKDOWN }

    // VpnService.isAlwaysOn asks the system about the calling app, so an instance that never runs can ask.
    private class Probe : VpnService()

    fun state(): State {
        if (Build.VERSION.SDK_INT < 29) return State.UNKNOWN
        return try {
            val probe = Probe()
            when {
                !probe.isAlwaysOn -> State.OFF
                probe.isLockdownEnabled -> State.LOCKDOWN
                else -> State.ON
            }
        } catch (_: Throwable) {
            State.UNKNOWN
        }
    }

    fun openSettings(context: Context): Boolean = try {
        context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
