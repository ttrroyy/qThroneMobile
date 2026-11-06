package app.rawline.mobile.outbound.types

import app.rawline.mobile.outbound.BuildContext
import app.rawline.mobile.outbound.BuildResult
import app.rawline.mobile.outbound.Outbound
import app.rawline.mobile.outbound.SecurityInfo
import app.rawline.mobile.outbound.json.JsonObject

/** direct (include/configs/outbounds/direct.h): dial fields only, no link form. */
class Direct : Outbound("direct") {

    /** direct.h:8. */
    override fun displayType(): String = "Direct"

    /** direct.h:11: not a tunnelling protocol, so a security verdict would only mislead. */
    override fun security(): SecurityInfo = SecurityInfo()

    /** direct.h:13-18 (the missing ": " after "Bind Addr6" is the desktop's). */
    override fun displayAddress(): String {
        if (dial.bind_interface.isNotEmpty()) return "Bind NIC: " + dial.bind_interface
        if (dial.inet4_bind_address.isNotEmpty()) return "Bind Addr4: " + dial.inet4_bind_address
        if (dial.inet6_bind_address.isNotEmpty()) return "Bind Addr6" + dial.inet6_bind_address
        return "Empty"
    }

    /** direct.h:20-25: no type check, only the tag and the dial fields. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("tag")) name = obj.string("tag")
        dial.parseFromJson(obj)
        return true
    }

    /** direct.h:27-33. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "direct"
        if (name.isNotEmpty()) obj["tag"] = name
        obj.merge(dial.exportToJson())
        return obj
    }

    /** direct.h:35-40. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "direct"
        obj.merge(dial.build(ctx))
        return BuildResult(obj)
    }
}
