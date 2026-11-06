package app.rawline.mobile.outbound.types

import app.rawline.mobile.outbound.BuildContext
import app.rawline.mobile.outbound.BuildResult
import app.rawline.mobile.outbound.Outbound
import app.rawline.mobile.outbound.QtStrings
import app.rawline.mobile.outbound.SecurityInfo
import app.rawline.mobile.outbound.SecurityLevel
import app.rawline.mobile.outbound.json.JsonArray
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.link.LinkBuilder
import app.rawline.mobile.outbound.link.LinkParser

/** mieru (include/configs/outbounds/mieru.h, src/configs/outbounds/mieru.cpp). */
class Mieru : Outbound("mieru") {
    @JvmField var transport: String = "TCP"
    @JvmField var username: String = ""
    @JvmField var password: String = ""
    @JvmField var multiplexing: String = ""
    @JvmField var traffic_pattern: String = ""

    /** Comma-separated port ranges ("9000-9010,9020-9030"); exported as sing-box's server_ports array. */
    @JvmField var server_ports: String = ""

    /** mieru.cpp:21-58: only mieru's "simple" link is mappable; the base64-protobuf "standard" link is rejected. */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid || url.host.isEmpty() || url.query.isEmpty()) return false
        val q = url.query
        super.parseFromLink(url)
        username = url.userName
        password = url.password

        // mieru pairs every port with its own protocol; one transport is modelled, so the first one wins
        val protocols = q.items.filter { it.first == "protocol" }
        if (protocols.isNotEmpty()) transport = protocols[0].second.uppercase()

        // the core only accepts ranges in server_ports, so an extra single port becomes "N-N"
        val ranges = ArrayList<String>()
        var haveSinglePort = false
        serverPort = 0
        for (item in q.items) {
            if (item.first != "port") continue
            val port = item.second.trim()
            if (port.isEmpty()) continue
            if (port.contains('-')) {
                ranges.add(port)
            } else if (!haveSinglePort) {
                serverPort = QtStrings.toInt(port)
                haveSinglePort = true
            } else {
                ranges.add("$port-$port")
            }
        }
        server_ports = ranges.joinToString(",")

        if (q.has("multiplexing")) multiplexing = q.value("multiplexing")
        if (q.has("traffic-pattern")) traffic_pattern = q.value("traffic-pattern")
        return true
    }

    /** mieru.cpp:60-75. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "mieru") return false
        super.parseFromJson(obj)
        if (obj.contains("transport")) transport = obj.string("transport")
        if (obj.contains("username")) username = obj.string("username")
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("multiplexing")) multiplexing = obj.string("multiplexing")
        if (obj.contains("traffic_pattern")) traffic_pattern = obj.string("traffic_pattern")
        if (obj.contains("server_ports")) server_ports = obj.array("server_ports").strings().joinToString(",")
        return true
    }

    /**
     * mieru.cpp:83-115: no authority port; every port is a repeated "port" item paired with a "protocol" item.
     * The desktop calls setUserName/setPassword unconditionally, which for the null QString of an unset member
     * clears the section; only non-empty values are written here (a stored profile never carries empty ones).
     */
    override fun exportToLink(): String {
        val url = LinkBuilder("mierus")
        if (username.isNotEmpty()) url.setUserName(username)
        if (password.isNotEmpty()) url.setPassword(password)
        url.host = server
        if (name.isNotEmpty()) url.fragment = name

        // mieru requires a profile name, which is not modelled
        url.addQueryItem("profile", "default")

        val protocol = if (transport.isEmpty()) "TCP" else transport.uppercase()
        if (serverPort > 0) {
            url.addQueryItem("port", serverPort.toString())
            url.addQueryItem("protocol", protocol)
        }
        for (part in QtStrings.splitSkipEmpty(server_ports, ",")) {
            val range = part.trim()
            if (range.isEmpty()) continue
            url.addQueryItem("port", range)
            url.addQueryItem("protocol", protocol)
        }

        if (multiplexing.isNotEmpty()) url.addQueryItem("multiplexing", multiplexing)
        if (traffic_pattern.isNotEmpty()) url.addQueryItem("traffic-pattern", traffic_pattern)
        url.addQueryItems(baseLinkQuery())
        return url.build()
    }

    /** mieru.cpp:117-130. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "mieru"
        obj.merge(baseExportToJson())
        writeFields(obj)
        return obj
    }

    /** mieru.cpp:132-145. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "mieru"
        obj.merge(baseBuild(ctx))
        writeFields(obj)
        return BuildResult(obj)
    }

    private fun writeFields(obj: JsonObject) {
        obj["transport"] = if (transport.isEmpty()) "TCP" else transport
        if (username.isNotEmpty()) obj["username"] = username
        if (password.isNotEmpty()) obj["password"] = password
        if (multiplexing.isNotEmpty()) obj["multiplexing"] = multiplexing
        if (traffic_pattern.isNotEmpty()) obj["traffic_pattern"] = traffic_pattern
        val ports = serverPortsArray()
        if (ports.isNotEmpty()) obj["server_ports"] = ports
    }

    /** mieru.cpp:11-18 splitServerPorts. */
    private fun serverPortsArray(): JsonArray {
        val arr = JsonArray()
        for (part in QtStrings.splitSkipEmpty(server_ports, ",")) {
            val range = part.trim()
            if (range.isNotEmpty()) arr.add(range)
        }
        return arr
    }

    /** mieru.cpp:147-150. */
    override fun displayType(): String = "Mieru"

    /** mieru.cpp:152-155. */
    override fun security(): SecurityInfo = SecurityInfo("Encrypted", "", SecurityLevel.Secure)
}
