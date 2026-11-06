package app.rawline.mobile.deploy

import app.rawline.mobile.outbound.link.Hosts
import app.rawline.mobile.outbound.link.LinkBuilder
import org.json.JSONObject

/** Links in the original apps' formats: qWDTT's admin link and the CSQTT panel's v2 link. */
object DeployConfig {
    /**
     * [ownDeviceId] is this phone's identity, which a profile imported without device_id gets.
     * A server bound to it needs no device_id in the link, as in the original apps; servers
     * deployed earlier were bound to a random identity, so their links still carry it.
     */
    fun link(config: JSONObject, ownDeviceId: String? = null): String {
        val kind=config.getString("type")
        require(kind in listOf("qwdtt","csqtt"))  // WDTT Plus stores its wdtt://connect link itself
        if(kind=="csqtt") return app.rawline.mobile.outbound.link.formatCsqttLink(
            config.getString("server"),config.getInt("server_port"),config.getString("password"),name=config.optString("name"))
        val url=LinkBuilder(kind).apply { host=if(kind=="qwdtt") "config" else "connect";plusAsEscape=true }
        if(kind=="qwdtt") {
            // qwdtt://config?name&peer&hashes&workers=18&port=9000&pass, as the original admin tab builds it.
            url.addQueryItem("name",config.optString("name").ifBlank { config.getString("password") })
            url.addQueryItem("peer",Hosts.displayAddress(config.getString("server"),config.getInt("server_port")))
            url.addQueryItem("hashes",config.optJSONArray("hashes")?.let { a -> (0 until a.length()).joinToString(",") { a.getString(it) } }.orEmpty())
            url.addQueryItem("workers","18");url.addQueryItem("port","9000")
            url.addQueryItem("pass",config.getString("password"))
        } else {
            // csqtt://connect?v=2&host&peer&password, as the original server panel builds it.
            url.addQueryItem("v","2");url.addQueryItem("host",config.getString("server"))
            url.addQueryItem("peer",config.getInt("server_port").toString());url.addQueryItem("password",config.getString("password"))
        }
        config.optString("device_id").takeIf { it.isNotBlank() && it!=ownDeviceId }?.let { url.addQueryItem("device_id",it) }
        return url.build()
    }
    fun display(saved: String): String = if(saved.trimStart().startsWith("{")) runCatching { link(JSONObject(saved)) }.getOrDefault(saved) else saved
}
