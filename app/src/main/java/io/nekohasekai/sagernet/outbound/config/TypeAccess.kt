package app.rawline.mobile.outbound.config

import app.rawline.mobile.outbound.Outbound
import app.rawline.mobile.outbound.types.Chain
import app.rawline.mobile.outbound.types.Custom
import app.rawline.mobile.outbound.types.Hysteria
import app.rawline.mobile.outbound.types.Socks

/** The desktop's Profile::Chain() / Custom() / Hysteria() / Socks() casts, which yield nothing for any other type. */
internal object TypeAccess {
    /** chain.h:10 `list`, stored in -> out; empty for anything that is not a Chain. */
    fun chainHops(outbound: Outbound): List<Long> = (outbound as? Chain)?.list ?: emptyList()

    fun asCustom(outbound: Outbound): Custom? = outbound as? Custom

    fun asSocks(outbound: Outbound): Socks? = outbound as? Socks

    /** isCustomFullConfig (generate.cpp:419-422). */
    fun isCustomFullConfig(outbound: Outbound): Boolean = (outbound as? Custom)?.isFullConfig() == true

    /** hysteria.h:57-59 RealmActive() (generate.cpp:1357). */
    fun realmActive(outbound: Outbound): Boolean = (outbound as? Hysteria)?.realmActive() == true
}
