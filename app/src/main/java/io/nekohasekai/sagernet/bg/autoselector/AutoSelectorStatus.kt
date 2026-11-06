package app.rawline.mobile.bg.autoselector

import app.rawline.mobile.outbound.json.JsonArray
import app.rawline.mobile.outbound.json.JsonInput
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.json.jsonObjectOf

/**
 * What the service reports about the auto-selector it runs (AutoSelectorView, AutoSelectorMonitor.hpp), carried as JSON
 * by ISagerNetService.autoSelectorStatus and cbAutoSelectorUpdate. [members] is the core's member table in rank order;
 * only a status asked for with its members carries it, so the callbacks stay small.
 */
data class AutoSelectorStatus(
    val phase: String = PHASE_IDLE,
    val selectorId: Long = -1,
    val selectorName: String = "",
    /** Profiles URL-tested before a (re)build ([PHASE_MEASURING]). */
    val measuring: Int = 0,
    /** While running: starting | probing | ready | suspended ("starting" until the core first reports). */
    val corePhase: String = "",
    val selectedId: Long = -1,
    val selectedName: String = "",
    /** The member the core holds as pinned, -1 = automatic. */
    val pinnedId: Long = -1,
    /** Unix ms of the core's last switch, 0 = none. */
    val lastSwitchMs: Long = 0,
    val lastSwitchReason: String = "",
    /** Members in the running build. */
    val membersTotal: Int = 0,
    val suspended: Boolean = false,
    val membersProbed: Int = 0,
    val membersAlive: Int = 0,
    val membersQualified: Int = 0,
    val membersCooldown: Int = 0,
    val probesInFlight: Int = 0,
    val roundsCompleted: Int = 0,
    val lastRoundMs: Long = 0,
    val nextRoundMs: Long = 0,
    val balance: Boolean = false,
    val exhaustedSinceMs: Long = 0,
    val members: List<Member> = emptyList(),
    /** The runtime's last notice (a refused pin, a rebuild), "" = none. */
    val notice: String = "",
    val noticeAtMs: Long = 0,
) {

    /** AutoSelectorMemberView. */
    data class Member(
        val id: Long,
        val tag: String,
        val name: String = "",
        val selected: Boolean = false,
        val selectedUdp: Boolean = false,
        /** ok | degraded | untested | dead | cooldown. */
        val state: String = "",
        val rank: Int = 0,
        val qualified: Boolean = false,
        val active: Boolean = false,
        val averageMs: Int = 0,
        val deviationMs: Int = 0,
        val minMs: Int = 0,
        val maxMs: Int = 0,
        val samples: Int = 0,
        val failures: Int = 0,
        val probes: Int = 0,
        val dialTotal: Int = 0,
        val dialFail: Int = 0,
        val lastOkMs: Long = 0,
        val lastProbeMs: Long = 0,
        val cooldownUntilMs: Long = 0,
        val lastError: String = "",
    ) {
        val isDead: Boolean get() = state == STATE_DEAD
        val isUsable: Boolean get() = state == STATE_OK || state == STATE_DEGRADED
        val hasProblem: Boolean get() = state == STATE_DEAD || state == STATE_COOLDOWN || state == STATE_DEGRADED

        internal fun toJson(): JsonObject = jsonObjectOf(
            "id" to id,
            "tag" to tag,
            "name" to name,
            "selected" to selected,
            "selected_udp" to selectedUdp,
            "state" to state,
            "rank" to rank,
            "qualified" to qualified,
            "active" to active,
            "average_ms" to averageMs,
            "deviation_ms" to deviationMs,
            "min_ms" to minMs,
            "max_ms" to maxMs,
            "samples" to samples,
            "failures" to failures,
            "probes" to probes,
            "dial_total" to dialTotal,
            "dial_fail" to dialFail,
            "last_ok_ms" to lastOkMs,
            "last_probe_ms" to lastProbeMs,
            "cooldown_until_ms" to cooldownUntilMs,
            "last_error" to lastError,
        )

        internal companion object {
            fun fromJson(obj: JsonObject) = Member(
                id = obj.integer("id"),
                tag = obj.string("tag"),
                name = obj.string("name"),
                selected = obj.bool("selected"),
                selectedUdp = obj.bool("selected_udp"),
                state = obj.string("state"),
                rank = obj.int("rank"),
                qualified = obj.bool("qualified"),
                active = obj.bool("active"),
                averageMs = obj.int("average_ms"),
                deviationMs = obj.int("deviation_ms"),
                minMs = obj.int("min_ms"),
                maxMs = obj.int("max_ms"),
                samples = obj.int("samples"),
                failures = obj.int("failures"),
                probes = obj.int("probes"),
                dialTotal = obj.int("dial_total"),
                dialFail = obj.int("dial_fail"),
                lastOkMs = obj.integer("last_ok_ms"),
                lastProbeMs = obj.integer("last_probe_ms"),
                cooldownUntilMs = obj.integer("cooldown_until_ms"),
                lastError = obj.string("last_error"),
            )
        }
    }

    /** A selector is being measured or run. */
    val active: Boolean get() = phase != PHASE_IDLE

    fun member(id: Long): Member? = members.firstOrNull { it.id == id }

    fun toJson(): String {
        val obj = jsonObjectOf(
            "phase" to phase,
            "selector_id" to selectorId,
            "selector_name" to selectorName,
            "measuring" to measuring,
            "core_phase" to corePhase,
            "selected_id" to selectedId,
            "selected_name" to selectedName,
            "pinned_id" to pinnedId,
            "last_switch_ms" to lastSwitchMs,
            "last_switch_reason" to lastSwitchReason,
            "members_total" to membersTotal,
            "suspended" to suspended,
            "members_probed" to membersProbed,
            "members_alive" to membersAlive,
            "members_qualified" to membersQualified,
            "members_cooldown" to membersCooldown,
            "probes_in_flight" to probesInFlight,
            "rounds_completed" to roundsCompleted,
            "last_round_ms" to lastRoundMs,
            "next_round_ms" to nextRoundMs,
            "balance" to balance,
            "exhausted_since_ms" to exhaustedSinceMs,
            "notice" to notice,
            "notice_at_ms" to noticeAtMs,
        )
        obj["members"] = JsonArray().also { array -> members.forEach { array.add(it.toJson()) } }
        return obj.toCompact()
    }

    companion object {
        const val PHASE_IDLE = "idle"

        /** Candidates are URL-tested before the selector (re)starts. */
        const val PHASE_MEASURING = "measuring"

        /** The core runs the selector ([corePhase] tells how far it got). */
        const val PHASE_RUNNING = "running"

        const val STATE_OK = "ok"
        const val STATE_DEGRADED = "degraded"
        const val STATE_UNTESTED = "untested"
        const val STATE_DEAD = "dead"
        const val STATE_COOLDOWN = "cooldown"

        @JvmField
        val IDLE = AutoSelectorStatus()

        /** Anything unreadable is [IDLE]. */
        @JvmStatic
        fun parse(json: String?): AutoSelectorStatus {
            if (json.isNullOrBlank()) return IDLE
            val obj = JsonInput.parseObjectOrNull(json) ?: return IDLE
            return AutoSelectorStatus(
                phase = obj.string("phase").ifEmpty { PHASE_IDLE },
                selectorId = if (obj.contains("selector_id")) obj.integer("selector_id") else -1,
                selectorName = obj.string("selector_name"),
                measuring = obj.int("measuring"),
                corePhase = obj.string("core_phase"),
                selectedId = if (obj.contains("selected_id")) obj.integer("selected_id") else -1,
                selectedName = obj.string("selected_name"),
                pinnedId = if (obj.contains("pinned_id")) obj.integer("pinned_id") else -1,
                lastSwitchMs = obj.integer("last_switch_ms"),
                lastSwitchReason = obj.string("last_switch_reason"),
                membersTotal = obj.int("members_total"),
                suspended = obj.bool("suspended"),
                membersProbed = obj.int("members_probed"),
                membersAlive = obj.int("members_alive"),
                membersQualified = obj.int("members_qualified"),
                membersCooldown = obj.int("members_cooldown"),
                probesInFlight = obj.int("probes_in_flight"),
                roundsCompleted = obj.int("rounds_completed"),
                lastRoundMs = obj.integer("last_round_ms"),
                nextRoundMs = obj.integer("next_round_ms"),
                balance = obj.bool("balance"),
                exhaustedSinceMs = obj.integer("exhausted_since_ms"),
                members = obj.array("members").mapNotNull { (it as? JsonObject)?.let(Member::fromJson) },
                notice = obj.string("notice"),
                noticeAtMs = obj.integer("notice_at_ms"),
            )
        }
    }
}
