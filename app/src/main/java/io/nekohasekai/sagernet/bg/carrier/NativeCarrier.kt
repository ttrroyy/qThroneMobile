package app.rawline.mobile.bg.carrier
import app.rawline.mobile.outbound.json.JsonObject
interface NativeCarrier {
    val carrierKind:CarrierKind
    fun privateConfig(port:Int,udpPort:Int,credentials:CarrierBridgeCredentials):JsonObject
}
