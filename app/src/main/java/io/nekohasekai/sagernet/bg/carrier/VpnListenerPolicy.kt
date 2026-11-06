package app.rawline.mobile.bg.carrier

import app.rawline.mobile.outbound.json.JsonInput
import app.rawline.mobile.outbound.json.JsonObject

/** Custom full configurations must not reopen the per-app VPN bypass through local listeners. */
object VpnListenerPolicy {
    fun check(config:String, allowSharing: Boolean = false) {
        val json=JsonInput.parseObject(config)
        for(value in json.array("inbounds")) {
            val inbound=value as? JsonObject ?: error("Invalid VPN inbound configuration")
            if(inbound.string("type")=="tun") continue
            check(inbound.string("type") in listOf("socks","http","mixed")) {
                "VPN custom configuration contains an unsupported listening inbound; use the TUN inbound"
            }
            val explicitSharing = allowSharing &&
                (inbound.string("tag") == "mixed-in" && inbound.string("type") == "socks" ||
                    inbound.string("tag") == "http-in" && inbound.string("type") == "http") &&
                inbound.string("listen") == "0.0.0.0"
            check(explicitSharing || inbound.string("listen") in listOf("127.0.0.1","::1")) {
                "VPN proxy listeners must bind to loopback and require authentication"
            }
            val users=inbound.array("users")
            check(users.isNotEmpty()) { "VPN proxy listener requires authentication to prevent routing bypass" }
            for(user in users) {
                val account=user as? JsonObject ?: error("Invalid VPN proxy credentials")
                check(account.string("username").isNotEmpty() && account.string("password").length>=32) {
                    "VPN proxy listener requires a strong password of at least 32 characters"
                }
            }
        }
        val api=json.obj("experimental").obj("clash_api")
        if(api.string("external_controller").isNotEmpty()) {
            check(api.string("secret").length>=32) { "VPN dashboard API requires a strong secret of at least 32 characters" }
        }
        check(json.obj("experimental").obj("v2ray_api").string("listen").isEmpty()) {
            "Unauthenticated V2Ray API listeners are not allowed on Android"
        }
        for (value in json.array("services")) {
            val service = value as? JsonObject ?: error("Invalid VPN service configuration")
            check(service.string("type") == "api") { "Unsupported listening service in VPN configuration" }
            if (service.int("listen_port") == 0) continue // In-process statistics only; no TCP listener.
            check(service.string("listen") in listOf("127.0.0.1", "::1") && service.string("secret").length >= 32) {
                "VPN API service must bind to loopback and require a strong secret of at least 32 characters"
            }
        }
    }

    /** Full Xray documents are untrusted too, including auto-selector and latency-test members. */
    fun checkXray(config: String) {
        if (config.isBlank()) return
        val json = JsonInput.parseObject(config)
        check(json.obj("api").isEmpty()) { "Xray management API is not allowed on Android" }
        check(json.obj("metrics").string("listen").isEmpty()) { "Unauthenticated Xray metrics listener is not allowed on Android" }
        for (value in json.array("inbounds")) {
            val inbound = value as? JsonObject ?: error("Invalid Xray inbound configuration")
            check(inbound.string("listen") in listOf("127.0.0.1", "::1")) {
                "Xray bridge listeners must bind to loopback"
            }
            val settings = inbound.obj("settings")
            check(inbound.string("protocol") in listOf("socks", "http")) {
                "Xray custom configuration contains an unsupported listening inbound"
            }
            if (inbound.string("protocol") == "socks") {
                check(settings.string("auth") == "password") { "Xray SOCKS listener requires authentication" }
            }
            val accounts = settings.array("accounts")
            check(accounts.isNotEmpty()) { "Xray listener requires authentication" }
            for (valueAccount in accounts) {
                val account = valueAccount as? JsonObject ?: error("Invalid Xray credentials")
                check(account.string("user").isNotEmpty() && account.string("pass").length >= 32) {
                    "Xray listener requires a strong password of at least 32 characters"
                }
            }
        }
    }
}
