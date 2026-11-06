package app.rawline.mobile

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.rawline.core.mobile.Mobile
import app.rawline.core.mobile.StartOptions
import app.rawline.mobile.bg.CoreRuntime
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/** Exercise the bundled native cores: loopback must not grant another app an anonymous proxy. */
@RunWith(AndroidJUnit4::class)
class LocalProxySecurityTest {
    @Test fun bothCoreBridgesRejectAnonymousClientsAndAcceptTheirPrivateCredentials() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        CoreRuntime.setup(application)
        for (xray in listOf(false, true)) {
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val password = UUID.randomUUID().toString().replace("-", "")
            val singConfig = """{"log":{"level":"error"},"inbounds":[{"type":"socks","listen":"127.0.0.1","listen_port":$port,"users":[{"username":"bridge","password":"$password"}]}],"outbounds":[{"type":"direct","tag":"direct"}]}"""
            val nativeXrayJson = """{"log":{"loglevel":"error"},"inbounds":[{"protocol":"socks","listen":"127.0.0.1","port":$port,"settings":{"auth":"password","udp":true,"accounts":[{"user":"bridge","pass":"$password"}]}}],"outbounds":[{"protocol":"freedom","tag":"direct"}]}"""
            val instance = Mobile.newInstance(CoreRuntime.platform, StartOptions().apply {
                coreConfig = if (xray) """{"outbounds":[{"type":"direct","tag":"direct"}]}""" else singConfig
                needXray = xray
                xrayConfig = if (xray) nativeXrayJson else ""
            })
            try {
                instance.start()
                connect(port) { socket ->
                    socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                    assertArrayEquals("Anonymous client accepted by ${if (xray) "Xray" else "sing-box"}", byteArrayOf(5, -1), read(socket, 2))
                }
                connect(port) { socket ->
                    socket.getOutputStream().write(byteArrayOf(5, 1, 2))
                    assertArrayEquals(byteArrayOf(5, 2), read(socket, 2))
                    val user = "bridge".toByteArray(Charsets.UTF_8)
                    val pass = password.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
                    assertArrayEquals("Authenticated bridge cannot connect", byteArrayOf(1, 0), read(socket, 2))
                }
            } finally { instance.close() }
        }
    }

    private fun connect(port: Int, block: (Socket) -> Unit) {
        Socket("127.0.0.1", port).use { socket -> socket.soTimeout = 5000; block(socket) }
    }

    private fun read(socket: Socket, count: Int): ByteArray = ByteArray(count).also { bytes ->
        java.io.DataInputStream(socket.getInputStream()).readFully(bytes)
    }
}
