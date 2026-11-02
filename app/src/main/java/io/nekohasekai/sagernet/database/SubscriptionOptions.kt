package app.qthrone.mobile.database

import android.os.Parcelable
import androidx.room.TypeConverter
import app.qthrone.mobile.outbound.json.JsonInput
import app.qthrone.mobile.outbound.json.JsonObject
import kotlinx.parcelize.Parcelize

/** Group.h:11-16, stored in `groups.test_sort_by`. */
enum class TestBy(val value: Int) {
    LATENCY(0), DL_SPEED(1), UL_SPEED(2), IP_OUT(3);

    companion object {
        @JvmStatic
        fun of(value: Int): TestBy = entries.firstOrNull { it.value == value } ?: LATENCY
    }
}

/** Group.h:18-23, stored in `groups.test_items_to_show`. */
enum class TestShowItems(val value: Int) {
    ALL(0), NONE(1), IP_ONLY(2), SPEED_ONLY(3);

    val showSpeed: Boolean get() = this == ALL || this == SPEED_ONLY
    val showIp: Boolean get() = this == ALL || this == IP_ONLY

    companion object {
        @JvmStatic
        fun of(value: Int): TestShowItems = entries.firstOrNull { it.value == value } ?: ALL
    }
}

/** Group.h:25-29, stored in `groups.traffic_sort_by`. */
enum class TrafficBy(val value: Int) {
    TOTAL(0), DL(1), UL(2);

    companion object {
        @JvmStatic
        fun of(value: Int): TrafficBy = entries.firstOrNull { it.value == value } ?: TOTAL
    }
}

/** Group.h:31-34, stored in `groups.type_sort_by`. */
enum class TypeBy(val value: Int) {
    BY_TYPE(0), BY_SECURITY(1);

    companion object {
        @JvmStatic
        fun of(value: Int): TypeBy = entries.firstOrNull { it.value == value } ?: BY_TYPE
    }
}

/** Group.h:36-41; the value doubles as the index of the "Send HWID" menu. */
enum class SendHwid(val value: Int) {
    KEEP_DEFAULT(0), ON(1), OFF(2);

    companion object {
        @JvmStatic
        fun of(value: Int): SendHwid = entries.firstOrNull { it.value == value } ?: KEEP_DEFAULT
    }
}

/** Persistent rule; legacy ping toggles are read as PING until the group is saved. */
enum class ServerSortRule(val value: Int) {
    NONE(0), PING(1), NAME(2);

    companion object {
        fun of(value: Int): ServerSortRule = entries.firstOrNull { it.value == value } ?: NONE
    }
}

/**
 * The per-group subscription options (`SubscriptionOptions`, Group.h:43-63) stored in `groups.sub_options_json`.
 * Empty strings inherit the global subscription settings; `removeUnavailable` and `sortByLatency` only take effect
 * while `urlTest` is on. The codec is Group.cpp:8-45: defaults are omitted, so all defaults are `{}`.
 */
@Parcelize
data class SubscriptionOptions(
    var routeProfileId: Long = -1L,
    var userAgent: String = "",
    var sendHwid: SendHwid = SendHwid.KEEP_DEFAULT,
    var hwid: String = "",
    var hwidOs: String = "",
    var hwidOsVersion: String = "",
    var hwidModel: String = "",
    var keepWorking: Boolean = false,
    var removeDuplicates: Boolean = false,
    var removeInsecure: Boolean = false,
    var removeInvalid: Boolean = false,
    var urlTest: Boolean = false,
    var removeUnavailable: Boolean = false,
    var sortByLatency: Boolean = false,
    var autoSortByPing: Boolean = false,
    var serverSortRule: ServerSortRule = ServerSortRule.NONE,
    /** Zero inherits the global HTTP request timeout. */
    var requestTimeoutSeconds: Int = 0,
    /** Android-only: this subscription's own update period in minutes (30+); zero follows the global setting. */
    var autoUpdateMinutes: Int = 0,
    var sendHwidInCookie: Boolean = false,
    var allowInsecureRequests: Boolean = false,
    var ignoreNameFilters: Boolean = false,
    var allowCollapse: Boolean = true,
    var hideServerSettings: Boolean = false,
    /** Android-only (#53): name filters applied on every update; desktop's codec drops these keys. */
    var nameInclude: String = "",
    var nameExclude: String = "",
    /** Android-only: the subscription info card above the profiles; stored only when off. */
    var showInfoCard: Boolean = true,
    var routingProvider: String = "",
    var providerRoutingEnabled: Boolean = true,
    var providerRouting: String = "",
    var providerRoutingProfiles: String = "[]",
    var providerIgnoreImport: Boolean = false,
    var providerRoutingManual: Boolean = false,
) : Parcelable {

    val effectiveSortRule: ServerSortRule
        get() = if (serverSortRule != ServerSortRule.NONE) serverSortRule
        else if (autoSortByPing || sortByLatency) ServerSortRule.PING else ServerSortRule.NONE

    fun toJson(): JsonObject {
        val json = JsonObject()
        if (routeProfileId > 0) json["route_profile_id"] = routeProfileId
        if (userAgent.isNotEmpty()) json["user_agent"] = userAgent
        if (sendHwid != SendHwid.KEEP_DEFAULT) json["send_hwid"] = sendHwid.value
        if (hwid.isNotEmpty()) json["hwid"] = hwid
        if (hwidOs.isNotEmpty()) json["hwid_os"] = hwidOs
        if (hwidOsVersion.isNotEmpty()) json["hwid_os_version"] = hwidOsVersion
        if (hwidModel.isNotEmpty()) json["hwid_model"] = hwidModel
        if (keepWorking) json["keep_working"] = true
        if (removeDuplicates) json["remove_duplicates"] = true
        if (removeInsecure) json["remove_insecure"] = true
        if (removeInvalid) json["remove_invalid"] = true
        if (urlTest) json["url_test"] = true
        if (removeUnavailable) json["remove_unavailable"] = true
        if (sortByLatency) json["sort_by_latency"] = true
        if (autoSortByPing) json["auto_sort_by_ping"] = true
        if (serverSortRule != ServerSortRule.NONE) json["server_sort_rule"] = serverSortRule.value
        if (requestTimeoutSeconds in 5..300) json["request_timeout_seconds"] = requestTimeoutSeconds
        if (autoUpdateMinutes >= 30) json["auto_update_minutes"] = autoUpdateMinutes
        if (sendHwidInCookie) json["send_hwid_in_cookie"] = true
        if (allowInsecureRequests) json["allow_insecure_requests"] = true
        if (ignoreNameFilters) json["ignore_name_filters"] = true
        if (!allowCollapse) json["allow_collapse"] = false
        if (hideServerSettings) json["hide_server_settings"] = true
        if (nameInclude.isNotEmpty()) json["name_include"] = nameInclude
        if (nameExclude.isNotEmpty()) json["name_exclude"] = nameExclude
        if (!showInfoCard) json["show_info_card"] = false
        if (routingProvider.isNotEmpty()) {
            json["routing_provider"] = routingProvider
            json["provider_routing_enabled"] = providerRoutingEnabled
            json["provider_routing"] = providerRouting
            json["provider_routing_profiles"] = providerRoutingProfiles
            if (providerIgnoreImport) json["provider_ignore_import"] = true
            if (providerRoutingManual) json["provider_routing_manual"] = true
        }
        return json
    }

    /** The column text: compact, keys sorted (QJsonDocument::toJson(Compact)). */
    fun toJsonString(): String = toJson().toCompact()

    companion object {

        @JvmStatic
        fun fromJson(json: JsonObject): SubscriptionOptions {
            val mode = json.int("send_hwid")
            return SubscriptionOptions(
                routeProfileId = json.integer("route_profile_id").takeIf { it > 0 } ?: -1L,
                userAgent = json.string("user_agent"),
                sendHwid = if (mode == SendHwid.ON.value || mode == SendHwid.OFF.value) SendHwid.of(mode)
                else SendHwid.KEEP_DEFAULT,
                hwid = json.string("hwid"),
                hwidOs = json.string("hwid_os"),
                hwidOsVersion = json.string("hwid_os_version"),
                hwidModel = json.string("hwid_model"),
                keepWorking = json.bool("keep_working"),
                removeDuplicates = json.bool("remove_duplicates"),
                removeInsecure = json.bool("remove_insecure"),
                removeInvalid = json.bool("remove_invalid"),
                urlTest = json.bool("url_test"),
                removeUnavailable = json.bool("remove_unavailable"),
                sortByLatency = json.bool("sort_by_latency"),
                autoSortByPing = json.bool("auto_sort_by_ping"),
                serverSortRule = ServerSortRule.of(json.int("server_sort_rule")),
                requestTimeoutSeconds = json.int("request_timeout_seconds").takeIf { it in 5..300 } ?: 0,
                autoUpdateMinutes = json.int("auto_update_minutes").takeIf { it >= 30 } ?: 0,
                sendHwidInCookie = json.bool("send_hwid_in_cookie"),
                allowInsecureRequests = json.bool("allow_insecure_requests"),
                ignoreNameFilters = json.bool("ignore_name_filters"),
                allowCollapse = !json.isBool("allow_collapse") || json.bool("allow_collapse"),
                hideServerSettings = json.bool("hide_server_settings"),
                nameInclude = json.string("name_include"),
                nameExclude = json.string("name_exclude"),
                showInfoCard = !json.isBool("show_info_card") || json.bool("show_info_card"),
                routingProvider = json.string("routing_provider"),
                providerRoutingEnabled = !json.isBool("provider_routing_enabled") || json.bool("provider_routing_enabled"),
                providerRouting = json.string("provider_routing"),
                providerRoutingProfiles = json.string("provider_routing_profiles").ifBlank { "[]" },
                providerIgnoreImport = json.bool("provider_ignore_import"),
                providerRoutingManual = json.bool("provider_routing_manual"),
            )
        }

        /** A column that is not a JSON object reads as all defaults (GroupsRepo.cpp:212-215). */
        @JvmStatic
        fun parse(text: String?): SubscriptionOptions {
            if (text.isNullOrBlank()) return SubscriptionOptions()
            return JsonInput.parseObjectOrNull(text)?.let(::fromJson) ?: SubscriptionOptions()
        }
    }

    class Converter {
        @TypeConverter
        fun toColumn(options: SubscriptionOptions): String = options.toJsonString()

        @TypeConverter
        fun fromColumn(text: String?): SubscriptionOptions = parse(text)
    }
}
