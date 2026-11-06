package app.rawline.mobile.bg.carrier

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PowerManager
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/** WDTT Plus v20's stdin lifecycle: preserve live channels while its own native policy recovers. */
internal class WdttPlusLifecycle(private val context: Context, private val send: (String) -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val networks = mutableSetOf<Network>()
    private var networkRegistered = false
    private var receiverRegistered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!closed.get()) when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> send("DEVICE_SLEEP")
                Intent.ACTION_SCREEN_ON -> send("DEVICE_WAKE")
            }
        }
    }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed(network, true)
        override fun onLost(network: Network) = changed(network, false)
    }

    init {
        try {
            // Ignore our VPN network: only Wi-Fi/mobile handovers invalidate the native path cache.
            connectivity?.allNetworks?.forEach { network ->
                val capabilities = connectivity?.getNetworkCapabilities(network)
                if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) networks += network
            }
            connectivity?.registerNetworkCallback(NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
            networkRegistered = connectivity != null
            ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            if (context.getSystemService(PowerManager::class.java)?.isInteractive == false) send("DEVICE_SLEEP")
            send(if (networks.isEmpty()) "CONNECTION_OFFLINE" else "CONNECTION_ONLINE")
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    @Synchronized private fun changed(network: Network, available: Boolean) {
        if (closed.get()) return
        val changed = if (available) networks.add(network) else networks.remove(network)
        if (changed) {
            send("CONNECTION_NETWORK")
            send(if (networks.isEmpty()) "CONNECTION_OFFLINE" else "CONNECTION_ONLINE")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (networkRegistered) runCatching { connectivity?.unregisterNetworkCallback(callback) }
        if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
    }
}
