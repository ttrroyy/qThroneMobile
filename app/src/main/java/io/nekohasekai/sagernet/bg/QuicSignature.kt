package app.qthrone.mobile.bg

import app.qthrone.core.mobile.Mobile
import app.qthrone.mobile.ktx.Logs
import app.qthrone.mobile.ktx.readableMessage
import app.qthrone.mobile.outbound.json.JsonValues
import app.qthrone.mobile.outbound.json.jsonObjectOf

/** :bg side of ICoreService.genQuicSignature (core/mobile/amnezia.go); like warpRegister, failures travel in "error". */
object QuicSignature {

    /** Returns `{"error","packets"}`, packets being up to five `<b 0x...>` values for I1..I5 in order. */
    fun generate(domain: String): String = try {
        val iterator = Mobile.genQuicSignature(domain)
        val packets = ArrayList<String>()
        while (iterator.hasNext()) packets.add(iterator.next())
        jsonObjectOf("error" to "", "packets" to JsonValues.stringArray(packets)).toCompact()
    } catch (e: Throwable) {
        Logs.w(e)
        jsonObjectOf("error" to e.readableMessage).toCompact()
    }
}
