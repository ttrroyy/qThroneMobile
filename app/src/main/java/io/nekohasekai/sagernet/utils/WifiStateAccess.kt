package app.rawline.mobile.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import app.rawline.mobile.SagerNet
import app.rawline.mobile.ktx.Logs

/**
 * What reading the SSID/BSSID of the connected Wi-Fi takes (WifiServiceImpl.getConnectionInfo redaction): location
 * permission from 8.1, location services from 9, precise and "all the time" location from 10, because the VPN keeps
 * running with the UI closed and its foreground service type carries no location capability.
 */
object WifiStateAccess {

    enum class Status { OK, NEED_FOREGROUND, NEED_BACKGROUND, LOCATION_OFF }

    private const val UNKNOWN_SSID = "<unknown ssid>"
    private const val REDACTED_BSSID = "02:00:00:00:00:00"
    private val EMPTY = "" to ""

    fun status(context: Context): Status {
        if (Build.VERSION.SDK_INT < 27) return Status.OK
        if (!hasForeground(context)) return Status.NEED_FOREGROUND
        if (Build.VERSION.SDK_INT >= 29 && !granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            return Status.NEED_BACKGROUND
        }
        if (Build.VERSION.SDK_INT >= 28 && !locationEnabled(context)) return Status.LOCATION_OFF
        return Status.OK
    }

    fun hasForeground(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            Build.VERSION.SDK_INT < 29 && granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun locationEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    /** SSID and BSSID of the Wi-Fi behind the default network; empty when that is not Wi-Fi or the values are redacted. */
    fun read(context: Context): Pair<String, String> {
        SagerNet.underlyingNetwork?.let { network ->
            val capabilities = SagerNet.connectivity.getNetworkCapabilities(network)
            if (capabilities != null && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return EMPTY
        }
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return EMPTY
        @Suppress("DEPRECATION") val info = manager.connectionInfo ?: return EMPTY
        val rawSsid = info.ssid.orEmpty()
        val ssid = when {
            rawSsid == UNKNOWN_SSID -> ""
            rawSsid.length >= 2 && rawSsid.startsWith('"') && rawSsid.endsWith('"') -> rawSsid.substring(1, rawSsid.length - 1)
            else -> rawSsid
        }
        val bssid = info.bssid?.takeUnless { it == REDACTED_BSSID }.orEmpty()
        return ssid to bssid
    }

    /**
     * Calls [onChange] whenever the SSID or BSSID changes while Wi-Fi rules are in use: the core re-reads the Wi-Fi
     * state only when the default interface changes, which misses roaming and hopping between networks on one wlan0.
     */
    class Monitor(private val context: Context, private val onChange: () -> Unit) {

        private var last: Pair<String, String>? = null
        private var registered = false

        private inner class Callback : ConnectivityManager.NetworkCallback {
            constructor() : super()

            @RequiresApi(31)
            constructor(flags: Int) : super(flags)

            override fun onAvailable(network: Network) = check()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = check()
            override fun onLost(network: Network) = check()
        }

        private val callback = if (Build.VERSION.SDK_INT >= 31) {
            Callback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO)
        } else {
            Callback()
        }

        fun start() {
            synchronized(this) { last = runCatching { read(context) }.getOrNull() }
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            try {
                SagerNet.connectivity.registerNetworkCallback(request, callback)
                registered = true
            } catch (e: Throwable) {
                Logs.w(e)
            }
        }

        fun stop() {
            if (!registered) return
            registered = false
            runCatching { SagerNet.connectivity.unregisterNetworkCallback(callback) }
        }

        private fun check() {
            val state = runCatching { read(context) }.getOrNull() ?: return
            synchronized(this) {
                if (state == last) return
                last = state
            }
            try {
                onChange()
            } catch (e: Throwable) {
                Logs.w(e)
            }
        }
    }
}
