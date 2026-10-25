package app.qthrone.mobile.ui.profile

import android.content.Intent
import android.os.Bundle
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import app.qthrone.mobile.ui.MobileAlertDialogBuilder as MaterialAlertDialogBuilder
import app.qthrone.mobile.Action
import app.qthrone.mobile.R
import app.qthrone.mobile.bg.autoselector.AutoSelectorProfiles
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.database.GroupRepo
import app.qthrone.mobile.database.ProfileManager
import app.qthrone.mobile.database.SettingsMapper
import app.qthrone.mobile.database.preference.EditTextPreferenceModifiers
import app.qthrone.mobile.ktx.Logs
import app.qthrone.mobile.ktx.onMainDispatcher
import app.qthrone.mobile.ktx.runOnDefaultDispatcher
import app.qthrone.mobile.ktx.runOnMainDispatcher
import app.qthrone.mobile.outbound.config.AutoSelectorPlan
import app.qthrone.mobile.outbound.config.AutoSelectorPlanner
import app.qthrone.mobile.outbound.config.AutoSelectorSkip
import app.qthrone.mobile.outbound.types.AutoSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * EditAutoSelector (edit_autoselector.cpp + .ui): the tracked groups, the name filter, balancing, the preferred profile
 * with "Use automatic", the live plan summary and the collapsed Advanced groups with the desktop's limits. Saving
 * validates like onEnd and prunes `pool` to the members that still qualify.
 */
class AutoSelectorSettingsActivity : BindingSettingsActivity<AutoSelector>() {

    private companion object {
        /** "Servers from": the checked group ids as a string set; [AutoSelector.gids] is not a binding. */
        const val KEY_GIDS = "gids"
        const val KEY_PINNED = "autoselPinned"
        const val KEY_PLAN = "autoselPlan"
        const val KEY_ADVANCED = "autoselAdvanced"
        const val KEY_BALANCE_HINT = "autoselBalanceHint"
        const val STATE_ADVANCED = "autoselAdvancedExpanded"
        val ADVANCED_CATEGORIES =
            listOf("autoselMembership", "autoselHealth", "autoselSwitching", "autoselBalancing", "autoselEndpoints")

        /** The fields refreshPlanSummary reads. */
        val PLAN_KEYS =
            setOf(KEY_GIDS, "nameFilter", "nameExclude", "countryFilter", "excludeUnavailable", "poolCap", "buildLimit", "resultValidityMins")
    }

    /** A spin box of edit_autoselector.ui: its range, unit and the text of 0 when it has one. */
    private class IntField(
        val key: String,
        val min: Int,
        val max: Int,
        @PluralsRes val unit: Int = 0,
        @StringRes val zero: Int = 0,
    )

    private val intFields = listOf(
        IntField("buildLimit", 1, AutoSelector.MAX_BUILD_LIMIT, R.plurals.autosel_profiles),
        IntField("poolCap", 1, AutoSelector.MAX_POOL_CAP, R.plurals.autosel_profiles),
        IntField("resultValidityMins", 0, 10080, R.plurals.autosel_minutes, R.string.autosel_always_retest),
        IntField("expected", 1, AutoSelector.MAX_BUILD_LIMIT, R.plurals.autosel_profiles),
        IntField("activeSize", 1, AutoSelector.MAX_BUILD_LIMIT, R.plurals.autosel_profiles),
        IntField("intervalSec", 10, 3600, R.plurals.autosel_seconds),
        IntField("benchIntervalSec", 10, 86400, R.plurals.autosel_seconds),
        IntField("watchIntervalSec", 5, 3600, R.plurals.autosel_seconds),
        IntField("sampling", 2, 60),
        IntField("toleranceMs", 0, 10000, R.plurals.autosel_milliseconds),
        IntField("maxRTTms", 0, 60000, R.plurals.autosel_milliseconds, R.string.autosel_no_limit),
        IntField("dialRetries", 0, 5),
        IntField("balanceIntervalSec", 5, 3600, R.plurals.autosel_seconds),
    )

    override fun createEntity() = AutoSelector().apply { gids = arrayListOf(DataStore.editingGroup) }
    override val preferencesResource = R.xml.autoselector_preferences

    // Like a chain, a selector is not a sing-box outbound the schema could check, and its JSON holds local ids.
    override val supportsRawJson = false

    init {
        pbm.text("name")
        pbm.text("nameFilter")
        pbm.text("nameExclude")
        pbm.bool("balance")
        pbm.text("countryFilter")
        pbm.bool("excludeUnavailable")
        pbm.bool("interruptOnSwitch")
        pbm.text("balanceMode")
        pbm.text("testURL")
        pbm.text("connectivityURL")
        for (field in intFields) pbm.int(field.key)
    }

    /** The pin as edited: "Use automatic" clears it at once, like the desktop. */
    private var pinnedId = -1L
    private var advancedExpanded = false
    private var balanceOn = false
    private var balanceMode = "rotate"
    private var planJob: Job? = null
    private var fragment: PreferenceFragmentCompat? = null

    /** Every group id in tab order: the order checked groups are saved in. */
    @Volatile
    private var groupOrder: List<Long> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        advancedExpanded = savedInstanceState?.getBoolean(STATE_ADVANCED) ?: false
        super.onCreate(savedInstanceState)
        supportActionBar?.setTitle(R.string.autosel_title)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ADVANCED, advancedExpanded)
    }

    override fun AutoSelector.init() {
        pbm.writeToCacheAll(this)
        DataStore.profileCacheStore.putStringSet(KEY_GIDS, gids.mapTo(LinkedHashSet()) { it.toString() })
    }

    override fun AutoSelector.serialize() {
        pbm.fromCacheAll(this)
        gids = checkedGroups(gids)
        nameFilter = nameFilter.trim()
        nameExclude = nameExclude.trim()
        countryFilter = countryFilter.trim()
        testURL = testURL.trim()
        connectivityURL = connectivityURL.trim()
        pinnedID = pinnedId
        val id = DataStore.editingId
        // The runtime writes these while the editor is open (after a start and after a ranking).
        if (id > 0) AutoSelectorProfiles.load(id)?.let { stored ->
            pool = stored.pool
            poolRankedAt = stored.poolRankedAt
            lastBuilt = stored.lastBuilt
            lastBuiltAt = stored.lastBuiltAt
            history = stored.history
        }
        normalize()
        // The pool is a ranking, so a filter change can leave entries that no longer qualify.
        val eligible = AutoSelectorPlanner(SettingsMapper.selectorStore()).rankingCandidates(id, this).toHashSet()
        pool = pool.filterTo(ArrayList()) { it in eligible }
    }

    /** onEnd's checks (cpp:146-172). */
    override suspend fun saveAndExit() {
        val error = validate()
        if (error == null) {
            super.saveAndExit()
            return
        }
        onMainDispatcher {
            MaterialAlertDialogBuilder(this@AutoSelectorSettingsActivity)
                .setTitle(R.string.autosel_title)
                .setMessage(error)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun validate(): String? {
        val store = DataStore.profileCacheStore
        if (store.getString("name").isNullOrBlank()) return getString(R.string.autosel_name_empty)
        if (checkedIds().none { GroupRepo.get(it) != null }) return getString(R.string.autosel_select_group)
        for (key in listOf("nameFilter", "nameExclude")) {
            val filter = store.getString(key).orEmpty().trim()
            if (filter.isEmpty()) continue
            try {
                Pattern.compile(filter, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
            } catch (e: PatternSyntaxException) {
                return getString(R.string.autosel_bad_regex, e.description ?: e.message.orEmpty())
            }
        }
        return null
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        fragment = this
        pinnedId = ensureEditingOutbound().pinnedID
        setupGroups()
        setupTexts()
        for (field in intFields) setupInt(field)
        // The change listeners run before the new value is stored, so the state comes from their arguments.
        onSwitch("balance") {
            balanceOn = it
            refreshBalance()
        }
        onMenu("balanceMode") {
            balanceMode = it
            refreshBalance()
        }
        findPreference<Preference>(KEY_PINNED)?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(this@AutoSelectorSettingsActivity)
                .setTitle(R.string.autosel_pinned)
                .setMessage(R.string.autosel_use_automatic_tip)
                .setPositiveButton(R.string.autosel_use_automatic) { _, _ -> releasePin() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
        findPreference<Preference>(KEY_ADVANCED)?.setOnPreferenceClickListener {
            advancedExpanded = !advancedExpanded
            refreshAdvanced()
            true
        }
        refreshAdvanced()
        refreshPinned()
        refreshPlan()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        super.onPreferenceDataStoreChanged(store, key)
        if (key in PLAN_KEYS) runOnMainDispatcher { refreshPlan() }
    }

    override fun onDestroy() {
        fragment = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------------------------------------ rows

    /** "Servers from": non-archived groups in tab order; a checked group outside that list stays listed, so it can be unchecked. */
    private fun PreferenceFragmentCompat.setupGroups() {
        val pref = findPreference<MultiSelectListPreference>(KEY_GIDS) ?: return
        val groups = GroupRepo.all()
        val known = groups.mapTo(HashSet()) { it.id }
        groupOrder = groups.map { it.id }
        val checked = checkedIds()
        val entries = ArrayList<CharSequence>()
        val values = ArrayList<CharSequence>()
        for (group in groups) {
            if (group.archive && group.id !in checked) continue
            entries.add(if (group.archive) getString(R.string.autosel_group_archived, group.displayName()) else group.displayName())
            values.add(group.id.toString())
        }
        for (id in checkedGroups(ensureEditingOutbound().gids)) {
            if (id in known) continue
            entries.add(getString(R.string.autosel_group_missing, id))
            values.add(id.toString())
        }
        pref.entries = entries.toTypedArray()
        pref.entryValues = values.toTypedArray()
        pref.summaryProvider = Preference.SummaryProvider<MultiSelectListPreference> { p ->
            val chosen = p.values
            p.entryValues.indices.filter { p.entryValues[it].toString() in chosen }
                .joinToString(getString(R.string.autosel_list_separator)) { p.entries[it] }
                .ifEmpty { getString(R.string.autosel_groups_none) }
        }
    }

    /** The ids checked in "Servers from". */
    private fun checkedIds(): Set<Long> = DataStore.profileCacheStore.getStringSet(KEY_GIDS).orEmpty()
        .mapNotNullTo(HashSet()) { text -> text.trim().toLongOrNull()?.takeIf { it >= 0 } }

    /** The checked groups in tab order; a checked id no group has any more follows, in its [previous] order. */
    private fun checkedGroups(previous: List<Long>): MutableList<Long> {
        val checked = checkedIds()
        val order = groupOrder.ifEmpty { GroupRepo.all().map { it.id } }
        val out = order.filterTo(ArrayList()) { it in checked }
        for (id in previous + checked.sorted()) if (id in checked && id !in out) out.add(id)
        return out
    }

    private fun PreferenceFragmentCompat.setupTexts() {
        fun summary(key: String, empty: () -> CharSequence) {
            findPreference<EditTextPreference>(key)?.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                it.text?.trim().orEmpty().ifEmpty { empty().toString() }
            }
        }
        summary("nameFilter") { getString(R.string.autosel_name_filter_empty) }
        summary("nameExclude") { getString(R.string.autosel_name_exclude_empty) }
        summary("countryFilter") { getString(R.string.autosel_country_filter_empty) }
        summary("testURL") { getString(R.string.autosel_test_url_empty, DataStore.testUrl) }
        summary("connectivityURL") {
            val direct = DataStore.directTestUrl
            if (direct.isBlank()) getString(R.string.autosel_connectivity_url_none)
            else getString(R.string.autosel_connectivity_url_empty, direct)
        }
    }

    /** A spin box: numbers only, out-of-range input clamped, the unit and the special 0 text in the summary. */
    private fun PreferenceFragmentCompat.setupInt(field: IntField) {
        val pref = findPreference<EditTextPreference>(field.key) ?: return
        pref.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        pref.setOnPreferenceChangeListener { preference, newValue ->
            val typed = (newValue as? String)?.trim()?.toIntOrNull()
            val current = (preference as EditTextPreference).text?.trim()?.toIntOrNull() ?: field.min
            val clamped = (typed ?: current).coerceIn(field.min, field.max)
            if (clamped.toString() == newValue) return@setOnPreferenceChangeListener true
            preference.text = clamped.toString()
            false
        }
        pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
            val value = it.text?.trim()?.toIntOrNull() ?: 0
            when {
                value == 0 && field.zero != 0 -> getString(field.zero)
                field.unit != 0 -> resources.getQuantityString(field.unit, value, value)
                else -> value.toString()
            }
        }
    }

    /** updateBalanceEnabled (cpp:220-240). */
    private fun refreshBalance() {
        val fragment = fragment ?: return
        val on = balanceOn
        val rotating = on && balanceMode != "connection"
        fragment.findPreference<Preference>("balanceMode")?.isEnabled = on
        fragment.findPreference<Preference>("balanceIntervalSec")?.isEnabled = rotating
        fragment.findPreference<Preference>(KEY_BALANCE_HINT)?.summary = getString(
            when {
                !on -> R.string.autosel_balance_hint_off
                rotating -> R.string.autosel_balance_hint_rotate
                else -> R.string.autosel_balance_hint_connection
            }
        )
    }

    private fun refreshAdvanced() {
        val fragment = fragment ?: return
        for (key in ADVANCED_CATEGORIES) fragment.findPreference<PreferenceCategory>(key)?.isVisible = advancedExpanded
        fragment.findPreference<Preference>(KEY_ADVANCED)?.apply {
            summary = getString(if (advancedExpanded) R.string.autosel_advanced_hide else R.string.autosel_advanced_show)
            setIcon(if (advancedExpanded) R.drawable.ic_baseline_expand_less_24 else R.drawable.ic_baseline_expand_more_24)
        }
    }

    /** refreshPinnedRow (cpp:205-218): shown only while a member is pinned. */
    private fun refreshPinned() {
        val row = fragment?.findPreference<Preference>(KEY_PINNED) ?: return
        row.isVisible = pinnedId >= 0
        if (pinnedId < 0) return
        val id = pinnedId
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching { ProfileManager.getProfile(id)?.outbound?.displayName() }.getOrNull()
            }
            if (pinnedId == id) row.summary = getString(
                R.string.autosel_pinned_summary, name ?: getString(R.string.autosel_pinned_missing)
            )
        }
    }

    /** Unlike the rest of the form, releasing the pin is written at once, and a running core releases its own copy. */
    private fun releasePin() {
        pinnedId = -1
        refreshPinned()
        val id = DataStore.editingId
        runOnDefaultDispatcher {
            try {
                ensureEditingOutbound().pinnedID = -1
                if (id <= 0) return@runOnDefaultDispatcher
                val stored = AutoSelectorProfiles.load(id) ?: return@runOnDefaultDispatcher
                if (stored.pinnedID >= 0) {
                    stored.pinnedID = -1
                    AutoSelectorProfiles.save(id, stored)
                }
                val release = Intent(Action.AUTO_SELECTOR_AUTOMATIC).putExtra(Action.EXTRA_PROFILE_ID, id)
                sendBroadcast(release.setPackage(packageName))
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ plan

    /** refreshPlanSummary (cpp:242-295) on a throwaway copy: planning normalises what it gets. */
    private fun refreshPlan() {
        val row = fragment?.findPreference<Preference>(KEY_PLAN) ?: return
        val store = DataStore.profileCacheStore
        val preview = AutoSelector()
        preview.parseFromJson(ensureEditingOutbound().exportToJson())
        preview.gids = checkedGroups(preview.gids)
        preview.nameFilter = store.getString("nameFilter").orEmpty().trim()
        preview.nameExclude = store.getString("nameExclude").orEmpty().trim()
        preview.countryFilter = store.getString("countryFilter").orEmpty().trim()
        preview.excludeUnavailable = store.getBoolean("excludeUnavailable", true)
        store.getString("poolCap")?.trim()?.toIntOrNull()?.let { preview.poolCap = it }
        store.getString("buildLimit")?.trim()?.toIntOrNull()?.let { preview.buildLimit = it }
        store.getString("resultValidityMins")?.trim()?.toIntOrNull()?.let { preview.resultValidityMins = it }
        val selectorId = DataStore.editingId
        planJob?.cancel()
        planJob = lifecycleScope.launch {
            delay(150)
            val plan = withContext(Dispatchers.IO) {
                runCatching { AutoSelectorPlanner(SettingsMapper.selectorStore()).plan(selectorId, preview) }
                    .onFailure { Logs.w(it) }.getOrNull()
            } ?: return@launch
            row.summary = describe(plan)
        }
    }

    private fun describe(plan: AutoSelectorPlan): String {
        if (!plan.ok) {
            return getString(
                when {
                    plan.groups.isNotEmpty() -> R.string.autosel_error_no_members
                    plan.missingGroups > 0 -> R.string.autosel_error_no_group
                    else -> R.string.autosel_error_no_tracked_group
                }
            )
        }
        val lines = ArrayList<String>()
        lines.add(
            if (plan.groups.size > 1) {
                getString(R.string.autosel_plan_counts_groups, plan.eligible, plan.membersInGroup, plan.groups.size, plan.build.size)
            } else {
                getString(R.string.autosel_plan_counts, plan.eligible, plan.membersInGroup, plan.build.size)
            }
        )
        if (plan.missingGroups > 0) {
            lines.add(resources.getQuantityString(R.plurals.autosel_plan_missing_groups, plan.missingGroups, plan.missingGroups))
        }
        if (plan.skipped.isNotEmpty()) {
            val reasons = plan.skipped.joinToString(getString(R.string.autosel_list_separator)) { (skip, count) ->
                getString(R.string.autosel_plan_skip_item, count, getString(skipText(skip)))
            }
            lines.add(getString(R.string.autosel_plan_skipped, reasons))
        }
        if (plan.keptUnavailable > 0) lines.add(getString(R.string.autosel_plan_kept_unavailable, plan.keptUnavailable))
        if (plan.truncated) lines.add(getString(R.string.autosel_plan_truncated, plan.poolCapUsed))
        if (plan.rankedByTest > 0) lines.add(getString(R.string.autosel_plan_reused, plan.rankedByTest))
        if (plan.needsRanking) lines.add(getString(R.string.autosel_plan_needs_ranking))
        return lines.joinToString(" ")
    }

    /** AutoSelectorSkipReason (AutoSelectorPlan.cpp:225-243). */
    @StringRes
    private fun skipText(skip: AutoSelectorSkip): Int = when (skip) {
        AutoSelectorSkip.Missing -> R.string.autosel_skip_missing
        AutoSelectorSkip.MetaType -> R.string.autosel_skip_meta
        AutoSelectorSkip.CoreTransitions -> R.string.autosel_skip_core_transitions
        AutoSelectorSkip.ExtraCore -> R.string.autosel_skip_extra_core
        AutoSelectorSkip.FullConfig -> R.string.autosel_skip_full_config
        AutoSelectorSkip.Malformed -> R.string.autosel_skip_malformed
        AutoSelectorSkip.Tailscale -> R.string.autosel_skip_tailscale
        AutoSelectorSkip.ManagementEndpoint -> R.string.autosel_skip_management
        AutoSelectorSkip.NameFilter -> R.string.autosel_skip_name
        AutoSelectorSkip.CountryFilter -> R.string.autosel_skip_country
        AutoSelectorSkip.Unavailable -> R.string.autosel_skip_unavailable
        AutoSelectorSkip.XrayFullChained -> R.string.autosel_skip_xray_chained
        AutoSelectorSkip.Unsupported -> R.string.autosel_skip_unsupported
    }
}
