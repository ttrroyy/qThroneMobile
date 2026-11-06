package app.qthrone.mobile.bg.carrier

import app.qthrone.mobile.outbound.types.Qwdtt
import app.qthrone.mobile.outbound.types.Socks
import java.net.ServerSocket
import java.net.DatagramSocket
import java.net.InetAddress

/** One generation owns its bridge; no runtime secret is written back to a stored profile. */
class CarrierPlan private constructor(
    val profileId:Long, val kind:CarrierKind, val port:Int, val key:String,
    val credentials:CarrierBridgeCredentials, val privateJson:String,
) {
    fun outbound()=Socks().also {
        it.server="127.0.0.1";it.serverPort=port
        it.username=credentials.username;it.password=credentials.password
    }
    companion object {
        /**
         * [withAccount] false checks the configuration only: automatic hashes are not requested from VK.
         * [interactive] false (tests) never waits for a VK sign-in.
         */
        fun qwdtt(profileId:Long, profile:NativeCarrier, withAccount:Boolean=true, interactive:Boolean=true):CarrierPlan {
            val identity=(profile as app.qthrone.mobile.outbound.Outbound).exportToJson().also { it.remove("name");it.remove("tag") }.toCompact()
            val key=java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
            CarrierPool.activePlan(key)?.let { return it }
            val runtimeProfile = if(profile is Qwdtt && withAccount) NativeHashPolicy.runtimeProfile(profile, VkRelayAccount.selectedHashes(VkRelayAccount.selectionKey(profile.type,profile.server,profile.serverPort)), { VkRelayAccount.hashes(it, interactive) }) { VkRelayAccount.tokenForCalls(interactive) } else profile
            val loopback=InetAddress.getByName("127.0.0.1")
            val credentials=CarrierBridgeCredentials.fresh()
            return ServerSocket(0,1,loopback).use { tcp -> DatagramSocket(0,loopback).use { udp ->
                CarrierPlan(profileId,profile.carrierKind,tcp.localPort,key,credentials,
                    runtimeProfile.privateConfig(tcp.localPort,udp.localPort,credentials).also {
                        if(profile is Qwdtt && profile.type=="qwdtt" && profile.hashSource=="auto") it["vk_auth"]="account"
                    }.toCompact())
            } }
        }
    }
}
