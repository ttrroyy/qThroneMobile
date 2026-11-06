package app.rawline.mobile.outbound.config

import app.rawline.mobile.outbound.link.Hosts
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom

/** MkManyPorts and GetRandomString (src/global/Utils.cpp:204-219, :77-91). */
internal object LocalPorts {
    private const val AUTH_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private val random = SecureRandom()

    /**
     * Reserves [count] distinct loopback ports: every listener stays bound until all of them have been recorded, so a
     * batch never repeats a port; then all are closed and the ports handed out. A failed bind records 0, which the
     * callers treat as "reserve again" or as an error (generate.cpp:1451-1457, :1488-1499).
     */
    @JvmStatic
    fun reserve(count: Int, host: String = "127.0.0.1"): List<Int> {
        val ports = ArrayList<Int>(count)
        val sockets = ArrayList<ServerSocket>(count)
        // listenWithRetry (Utils.cpp:184-194): a host that is not an address literal binds to any interface.
        val bindAddress = if (host.isNotEmpty() && Hosts.isIpAddress(host)) {
            try {
                InetAddress.getByName(host.removePrefix("[").removeSuffix("]"))
            } catch (e: IOException) {
                null
            }
        } else null
        try {
            repeat(count) {
                var port = 0
                try {
                    val socket = ServerSocket()
                    socket.bind(if (bindAddress != null) InetSocketAddress(bindAddress, 0) else InetSocketAddress(0))
                    sockets.add(socket)
                    port = socket.localPort
                } catch (e: IOException) {
                    // recorded as 0
                } catch (e: SecurityException) {
                    // recorded as 0
                }
                ports.add(port)
            }
        } finally {
            for (socket in sockets) {
                try {
                    socket.close()
                } catch (e: IOException) {
                    // nothing to release
                }
            }
        }
        return ports
    }

    /** GetRandomString: [length] characters of `[A-Za-z0-9]`. */
    @JvmStatic
    fun randomAuth(length: Int = 32): String {
        val sb = StringBuilder(length)
        repeat(length) { sb.append(AUTH_ALPHABET[random.nextInt(AUTH_ALPHABET.length)]) }
        return sb.toString()
    }
}
