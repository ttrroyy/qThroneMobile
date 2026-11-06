package app.rawline.mobile.outbound.types

import app.rawline.mobile.outbound.BuildContext
import app.rawline.mobile.outbound.BuildResult
import app.rawline.mobile.outbound.Outbound
import app.rawline.mobile.outbound.SecurityInfo
import app.rawline.mobile.outbound.json.JsonArray
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.json.JsonValues

/** chain (include/configs/outbounds/chain.h): a list of profile ids the generator expands into hops (R2 §F). */
class Chain : Outbound("chain") {

    /** chain.h:10: from in to out — list[0] is the hop the client dials first, the last entry is the exit. */
    @JvmField var list: ArrayList<Long> = ArrayList()

    /** chain.h:12. */
    override fun displayType(): String = "Chain Proxy"

    /** chain.h:14. */
    override fun displayAddress(): String = ""

    /** chain.h:17: no security of its own; it inherits whatever its hops use. */
    override fun security(): SecurityInfo = SecurityInfo()

    /** chain.h:20: holds no secret itself; every hop is checked separately. */
    override fun supportsCredentialStrip(): Boolean = true

    /** chain.h:22-27 (`list` items via QJsonValue::toInt; widened to Long for Room ids). */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("name")) name = obj.string("name")
        if (obj.contains("list")) list = obj.array("list").mapTo(ArrayList()) { JsonValues.toInteger(it) }
        return true
    }

    /** chain.h:29-35: `name` (even empty), `type` and `list` (even empty) are always written. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["name"] = name
        obj["type"] = "chain"
        val ids = JsonArray()
        for (id in list) ids.add(id)
        obj["list"] = ids
        return obj
    }

    /** chain.h:37-40. */
    override fun build(ctx: BuildContext): BuildResult = BuildResult(JsonObject(), "Cannot call Build on chain config")
}
