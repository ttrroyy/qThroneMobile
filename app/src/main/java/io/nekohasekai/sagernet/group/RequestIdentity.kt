package app.rawline.mobile.group

import app.rawline.mobile.database.DataStore
import app.rawline.mobile.database.ProxyGroup
import app.rawline.mobile.database.SendHwid
import app.rawline.mobile.ktx.appUserAgent

/** RequestIdentity (GroupUpdater.hpp:19-26): the user agent and HWID headers of a subscription request. */
class RequestIdentity(
    @JvmField val userAgent: String,
    @JvmField val sendHwid: Boolean,
    @JvmField val device: DeviceDetails,
    @JvmField val timeoutSeconds: Int = 10,
    @JvmField val sendHwidInCookie: Boolean = false,
    @JvmField val allowInsecureRequests: Boolean = false,
) {

    /** identityHeaders (GroupUpdater.cpp:59-70). */
    fun headers(): List<Pair<String, String>> {
        if (!sendHwid) return emptyList()
        val headers = ArrayList<Pair<String, String>>(4)
        fun add(name: String, value: String) {
            if (usableHeaderValue(value)) headers.add(name to value)
        }
        add("x-hwid", device.hwid)
        add("x-device-os", device.os)
        add("x-ver-os", device.osVersion)
        add("x-device-model", device.model)
        if (sendHwidInCookie && usableHeaderValue(device.hwid)) {
            // Cookie values cannot contain separators even when valid as a normal HTTP header.
            val encoded = java.net.URLEncoder.encode(device.hwid, "UTF-8").replace("+", "%20")
            add("Cookie", "hwid=$encoded")
        }
        return headers
    }

    companion object {

        private const val MAX_HEADER_VALUE = 1000

        /** usableHeaderValue (GroupUpdater.cpp:40-42). */
        @JvmStatic
        fun usableHeaderValue(value: String): Boolean =
            value.isNotEmpty() && value.length < MAX_HEADER_VALUE && '\n' !in value && '\r' !in value

        /** applyCustomHwidParams (GroupUpdater.cpp:44-57): `hwid=…,os=…,osVersion=…,model=…`, keys case-insensitive. */
        @JvmStatic
        fun applyCustomHwidParams(device: DeviceDetails, params: String): DeviceDetails {
            var result = device
            for (pair in params.split(',')) {
                val trimmed = pair.trim()
                val eqPos = trimmed.indexOf('=')
                if (eqPos <= 0) continue
                val key = trimmed.substring(0, eqPos).trim().lowercase()
                val value = trimmed.substring(eqPos + 1).trim()
                if (!usableHeaderValue(value)) continue
                result = when (key) {
                    "hwid" -> result.copy(hwid = value)
                    "os" -> result.copy(os = value)
                    "osversion" -> result.copy(osVersion = value)
                    "model" -> result.copy(model = value)
                    else -> result
                }
            }
            return result
        }

        /**
         * ResolveIdentity (GroupUpdater.cpp:299-319): the global subscription settings under the group's overrides;
         * a null [group] resolves the globals alone (ImportUrl and the Advanced placeholders).
         */
        @JvmStatic
        fun resolve(group: ProxyGroup?): RequestIdentity {
            // Panels serving Happ's full Xray JSON otherwise omit protocols such as Hy2.
            var userAgent = if (DataStore.userAgent2.isBlank()) "Happ/3.0 " + app.rawline.mobile.ktx.USER_AGENT else appUserAgent()
            var sendHwid = DataStore.subSendHwid
            var device = applyCustomHwidParams(DeviceDetails.get(), DataStore.subCustomHwidParams)
            if (group == null) return RequestIdentity(userAgent, sendHwid, device, DataStore.subRequestTimeoutSeconds)

            val options = group.subOptions
            if (usableHeaderValue(options.userAgent)) userAgent = options.userAgent
            if (options.sendHwid != SendHwid.KEEP_DEFAULT) sendHwid = options.sendHwid == SendHwid.ON
            if (usableHeaderValue(options.hwid)) device = device.copy(hwid = options.hwid)
            if (usableHeaderValue(options.hwidOs)) device = device.copy(os = options.hwidOs)
            if (usableHeaderValue(options.hwidOsVersion)) device = device.copy(osVersion = options.hwidOsVersion)
            if (usableHeaderValue(options.hwidModel)) device = device.copy(model = options.hwidModel)
            return RequestIdentity(userAgent, sendHwid, device,
                options.requestTimeoutSeconds.takeIf { it in 5..300 } ?: DataStore.subRequestTimeoutSeconds,
                options.sendHwidInCookie, options.allowInsecureRequests)
        }
    }
}
