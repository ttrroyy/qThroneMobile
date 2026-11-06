package app.rawline.mobile.outbound.config

import app.rawline.mobile.outbound.json.*

/** Enforce app selection inside the core as well as Android's routing policy. */
object VpnAppGuard {
    fun allowedUids(packages: Map<Int,List<String>>, selected: Set<String>, bypass: Boolean): List<Int> =
        packages.filter { (uid,names) -> uid>=0 && names.isNotEmpty() &&
            if(bypass) names.none { it in selected } else names.any { it in selected }
        }.keys.sorted()

    fun apply(config: JsonObject, allowedUids: List<Int>) {
        val tunTags=config.array("inbounds").filterIsInstance<JsonObject>()
            .filter { it.string("type")=="tun" }.map { it.string("tag") }
        require(tunTags.none { it.isEmpty() }) { "Per-app VPN requires tagged TUN inbounds" }
        val route=config.obj("route").copy()
        val rules=JsonArray()
        for(tag in tunTags) {
            rules.add(if(allowedUids.isEmpty()) jsonObjectOf("inbound" to tag,"action" to "reject")
            else jsonObjectOf("type" to "logical","mode" to "and","rules" to JsonArray.of(
                jsonObjectOf("inbound" to tag),
                jsonObjectOf("user_id" to JsonArray.of(*allowedUids.toTypedArray()),"invert" to true)
            ),"action" to "reject"))
        }
        for(rule in route.array("rules")) rules.add(rule)
        route["rules"]=rules
        route["find_process"]=true
        config["route"]=route
    }
}
