package app.qthrone.mobile.ui.route

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import app.qthrone.mobile.ui.MobileAlertDialogBuilder as MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import app.qthrone.mobile.R
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.database.DnsServerHistory
import app.qthrone.mobile.database.RouteDnsSettings
import app.qthrone.mobile.database.RouteManager
import app.qthrone.mobile.databinding.LayoutRouteAddRuleBinding
import app.qthrone.mobile.databinding.LayoutRouteDnsBinding
import app.qthrone.mobile.databinding.LayoutRouteProfileHeaderBinding
import app.qthrone.mobile.databinding.LayoutRouteRawBinding
import app.qthrone.mobile.databinding.LayoutRouteRuleItemBinding
import app.qthrone.mobile.databinding.LayoutRouteRulesTitleBinding
import app.qthrone.mobile.group.RemoteRouteUpdater
import app.qthrone.mobile.ktx.FixedLinearLayoutManager
import app.qthrone.mobile.ktx.confirmAction
import app.qthrone.mobile.route.OutboundIds
import app.qthrone.mobile.route.RouteDns
import app.qthrone.mobile.route.RouteProfile
import app.qthrone.mobile.route.RouteRule
import app.qthrone.mobile.route.RuleType
import app.qthrone.mobile.ui.DnsServerSuggestions
import app.qthrone.mobile.ui.ThemedActivity
import app.qthrone.mobile.utils.PackageCache
import app.qthrone.mobile.widget.UndoSnackbarManager
import app.qthrone.mobile.widget.applyListInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A route profile: name, default outbound, the remote source of a remote profile, its DNS, and the ordered rule list
 * (drag to reorder, swipe to delete, tap to edit in [RouteRuleActivity], switch to enable). Edits stay in memory until
 * Apply; the result carries the saved id as [EXTRA_SAVED_ID].
 */
class RouteProfileActivity : ThemedActivity(R.layout.layout_route_profile) {

    companion object {
        const val EXTRA_PROFILE_ID = "id"
        const val EXTRA_REMOTE = "remote"

        /** A new, unsaved profile to start from (RouteJson), e.g. an imported rule list that still needs a name. */
        const val EXTRA_PROFILE_JSON = "profile"
        const val EXTRA_SAVED_ID = "savedId"

        private val RULE_NAME = Regex("rule_(\\d{1,6})")

        private const val DISABLED_ALPHA = 0.5f

        fun nextRuleName(rules: List<RouteRule>): String {
            val used = rules.mapTo(HashSet()) { it.name }
            var n = rules.mapNotNull { RULE_NAME.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
            do {
                n++
            } while ("rule_$n" in used)
            return "rule_$n"
        }
    }

    class EditorModel : ViewModel() {
        var profile: RouteProfile? = null
        var servers: Map<Long, String> = emptyMap()
        var appLabels: Map<String, String> = emptyMap()
        var warnings: List<String> = emptyList()
        var dirty = false
        var fetching = false
        var saving = false

        /**
         * [dnsDraft] holds the own values, kept while the global ones apply, and [dnsOpened] what the editor opened
         * with, so only servers typed here are remembered. [dnsGlobal] is shown while global applies.
         */
        var dnsCustom = false
        var dnsDraft: RouteDns? = null
        var dnsOpened: RouteDns? = null
        var dnsGlobal: RouteDns? = null
        var dnsObject = false
        var remoteSuggestions: List<String> = emptyList()
        var directSuggestions: List<String> = emptyList()
        var dnsChecked = false
    }

    private class Loaded(
        val profile: RouteProfile,
        val servers: Map<Long, String>,
        val appLabels: Map<String, String>,
        val dnsGlobal: RouteDns,
        val dnsObject: Boolean,
        val remoteSuggestions: List<String>,
        val directSuggestions: List<String>,
    )

    private val model: EditorModel by viewModels()
    private lateinit var list: RecyclerView
    private val headerAdapter = HeaderAdapter()
    private val dnsAdapter = DnsAdapter()
    private val titleAdapter = TitleAdapter()
    private val rulesAdapter = RulesAdapter()
    private val footerAdapter = FooterAdapter()
    private val rawAdapter = RawAdapter()
    private lateinit var touchHelper: ItemTouchHelper
    private lateinit var undoManager: UndoSnackbarManager<RouteRule>

    private val ruleEditor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onRuleResult(it.resultCode, it.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.route_profile_title)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        list = findViewById(R.id.rule_list)
        list.layoutManager = FixedLinearLayoutManager(list)
        list.adapter = ConcatAdapter(headerAdapter, dnsAdapter, rawAdapter, titleAdapter, rulesAdapter, footerAdapter)
        findViewById<android.view.View>(R.id.coordinator).setBackgroundColor(app.qthrone.mobile.ui.MobilePalette.background)
        app.qthrone.mobile.ui.MobileViewStyle.apply(findViewById(R.id.coordinator))
        list.applyListInsets(ime = true)
        undoManager = UndoSnackbarManager(this, rulesAdapter)
        touchHelper = ItemTouchHelper(TouchCallback()).also { it.attachToRecyclerView(list) }
        onBackPressedDispatcher.addCallback(this) { close() }
        if (model.profile == null) load()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar =
        Snackbar.make(findViewById(R.id.coordinator), text, Snackbar.LENGTH_LONG)

    private fun load() {
        val id = intent.getLongExtra(EXTRA_PROFILE_ID, 0L)
        val json = intent.getStringExtra(EXTRA_PROFILE_JSON)
        val remote = intent.getBooleanExtra(EXTRA_REMOTE, false)
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val p = when {
                    id > 0L -> RouteManager.get(id)
                    json != null -> RouteJson.profileFromJson(json).apply { this.id = 0L }
                    else -> RouteProfile().apply {
                        is_remote = remote
                        // RouteItem.cpp:61-67 seeds an empty profile with the DNS hijack rule.
                        if (!remote) rules.add(RouteRule().apply {
                            name = "dns-hijack"
                            protocol = "dns"
                            action = "hijack-dns"
                        })
                    }
                } ?: return@withContext null
                nameRules(p)
                Loaded(
                    p,
                    RouteServers.names(p.rules.map { it.outbound_id }),
                    appLabels(labelledPackages(p.rules)),
                    RouteDnsSettings.global(),
                    DataStore.useDnsObject,
                    DnsServerHistory.remoteSuggestions(),
                    DnsServerHistory.directSuggestions(),
                )
            }
            if (loaded == null) {
                finish()
                return@launch
            }
            if (model.profile != null) return@launch
            val p = loaded.profile
            model.profile = p
            model.servers = loaded.servers
            model.appLabels = loaded.appLabels
            model.dnsGlobal = loaded.dnsGlobal
            model.dnsObject = loaded.dnsObject
            model.remoteSuggestions = loaded.remoteSuggestions
            model.directSuggestions = loaded.directSuggestions
            model.dnsCustom = p.custom_dns
            model.dnsDraft = p.dns ?: loaded.dnsGlobal
            model.dnsOpened = model.dnsDraft
            model.dirty = json != null
            refreshAll()
        }
    }

    private fun nameRules(p: RouteProfile) {
        for (rule in p.rules) if (rule.name.isBlank()) rule.name = nextRuleName(p.rules)
    }

    private fun appLabels(packages: Collection<String>): Map<String, String> {
        if (packages.isEmpty()) return emptyMap()
        val cache = PackageCache.snapshot()
        return packages.distinct().associateWith { cache.loadLabel(it) }
    }

    /** The packages the rules' summaries name, without the unknown-app entry. */
    private fun labelledPackages(rules: List<RouteRule>): List<String> =
        rules.flatMap { rule -> rule.package_name.filter { it != RouteRule.UNKNOWN_PACKAGE } }

    @SuppressLint("NotifyDataSetChanged")
    private fun refreshAll() {
        headerAdapter.notifyDataSetChanged()
        dnsAdapter.notifyDataSetChanged()
        titleAdapter.notifyDataSetChanged()
        rulesAdapter.notifyDataSetChanged()
        footerAdapter.notifyDataSetChanged()
        rawAdapter.notifyDataSetChanged()
        invalidateOptionsMenu()
    }

    private fun markDirty() {
        model.dirty = true
    }

    private fun countChanged() = titleAdapter.notifyItemChanged(0)

    private fun editDns(change: (RouteDns) -> RouteDns) {
        val draft = model.dnsDraft ?: return
        model.dnsDraft = change(draft)
        markDirty()
    }

    /** The typed own values stay in the draft while the global ones apply. */
    private fun setCustomDns(custom: Boolean) {
        if (model.dnsCustom == custom) return
        model.dnsCustom = custom
        markDirty()
        dnsAdapter.holder?.bindMode()
    }

    /**
     * custom_dns follows the choice and dns takes the values, except that an untouched seed stays unstored, so a later
     * switch to own DNS starts from the global settings of that time. Returns what it stored.
     */
    private fun applyDns(p: RouteProfile): RouteDns? {
        val draft = model.dnsDraft ?: return null
        p.custom_dns = model.dnsCustom
        val values = draft.copy(remoteDns = draft.remoteDns.trim(), directDns = draft.directDns.trim())
        if (!p.custom_dns && p.dns == null && values == model.dnsOpened) return null
        p.dns = values
        return values
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.route_profile_menu, menu)
        if (model.profile?.is_raw == true) {
            menu.removeItem(R.id.action_add_rule)
            menu.removeItem(R.id.action_apply)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_add_rule -> addRule()
            R.id.action_apply -> save()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        close()
        return true
    }

    private fun close() {
        if (!model.dirty) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unsaved_changes_prompt)
            .setPositiveButton(R.string.save) { _, _ -> save() }
            .setNegativeButton(R.string.discard) { _, _ -> finish() }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun message(title: Int, text: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** RouteItem::accept (RouteItem.cpp:597-649): name, remote URL, then the empty-rule filter and check. */
    private fun save() {
        val p = model.profile ?: return
        if (p.is_raw) return
        if (model.saving) return
        undoManager.flush()
        p.name = p.name.trim()
        if (p.name.isEmpty()) {
            message(R.string.route_profile_invalid_title, getString(R.string.route_profile_empty_name))
            return
        }
        if (p.is_remote) {
            p.remote_url = p.remote_url.trim()
            if (p.remote_url.isEmpty()) {
                message(R.string.route_profile_invalid_title, getString(R.string.route_profile_need_url))
                return
            }
        }
        val draft = model.dnsDraft
        if (model.dnsCustom && draft != null && (draft.remoteDns.isBlank() || draft.directDns.isBlank())) {
            model.dnsChecked = true
            dnsAdapter.holder?.bindErrors()
            list.smoothScrollToPosition(headerAdapter.itemCount)
            return
        }
        val kept = p.rules.filterNot { it.isEmpty() }
        if (!p.is_remote && kept.isEmpty()) {
            message(R.string.route_profile_empty_title, getString(R.string.route_profile_empty))
            return
        }
        val removed = p.rules.size - kept.size
        val toSave = p.copy().apply { rules = kept.toMutableList() }
        val opened = model.dnsOpened
        val storedDns = applyDns(toSave)
        model.saving = true
        lifecycleScope.launch {
            try {
                val id = withContext(Dispatchers.IO) {
                    val saved = RouteManager.save(toSave)
                    if (storedDns != null && opened != null) {
                        if (storedDns.remoteDns != opened.remoteDns) DnsServerHistory.rememberRemote(storedDns.remoteDns)
                        if (storedDns.directDns != opened.directDns) DnsServerHistory.rememberDirect(storedDns.directDns)
                    }
                    saved
                }
                if (removed > 0) {
                    Toast.makeText(
                        this@RouteProfileActivity,
                        resources.getQuantityString(R.plurals.route_profile_empty_rules_removed, removed, removed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                setResult(RESULT_OK, Intent().putExtra(EXTRA_SAVED_ID, id))
                finish()
            } finally {
                model.saving = false
            }
        }
    }

    private fun addRule() {
        val p = model.profile ?: return
        undoManager.flush()
        val rule = RouteRule().apply { name = nextRuleName(p.rules) }
        ruleEditor.launch(Intent(this, RouteRuleActivity::class.java).apply {
            putExtra(RouteRuleActivity.EXTRA_RULE, RouteJson.ruleToJson(rule))
            putExtra(RouteRuleActivity.EXTRA_INDEX, -1)
        })
    }

    private fun editRule(index: Int) {
        val rule = model.profile?.rules?.getOrNull(index) ?: return
        undoManager.flush()
        ruleEditor.launch(Intent(this, RouteRuleActivity::class.java).apply {
            putExtra(RouteRuleActivity.EXTRA_RULE, RouteJson.ruleToJson(rule))
            putExtra(RouteRuleActivity.EXTRA_INDEX, index)
        })
    }

    private fun onRuleResult(code: Int, data: Intent?) {
        val p = model.profile ?: return
        if (data == null) return
        val index = data.getIntExtra(RouteRuleActivity.EXTRA_INDEX, -1)
        when (code) {
            RESULT_OK -> {
                val rule = RouteJson.ruleFromJson(data.getStringExtra(RouteRuleActivity.EXTRA_RULE))
                if (index in p.rules.indices) {
                    if (rule.name.isBlank()) rule.name = nextRuleName(p.rules)
                    p.rules[index] = rule
                    rulesAdapter.notifyItemChanged(index)
                } else {
                    if (rule.name.isBlank()) rule.name = nextRuleName(p.rules)
                    p.rules.add(rule)
                    rulesAdapter.notifyItemInserted(p.rules.size - 1)
                    countChanged()
                    list.post { list.smoothScrollToPosition(list.adapter!!.itemCount - 1) }
                }
                markDirty()
                resolveNames(rule)
            }

            RouteRuleActivity.RESULT_DELETE -> if (index in p.rules.indices) {
                val removed = p.rules.removeAt(index)
                rulesAdapter.notifyItemRemoved(index)
                countChanged()
                markDirty()
                undoManager.remove(index to removed)
            }
        }
    }

    /** Loads the server and app names a changed rule shows but the editor does not know yet. */
    private fun resolveNames(rule: RouteRule) {
        val server = rule.outbound_id.takeIf { it > 0 && it !in model.servers }
        val packages = labelledPackages(listOf(rule)).filter { it !in model.appLabels }
        if (server == null && packages.isEmpty()) return
        lifecycleScope.launch {
            val (servers, labels) = withContext(Dispatchers.IO) {
                RouteServers.names(listOfNotNull(server)) to appLabels(packages)
            }
            model.servers = model.servers + servers
            model.appLabels = model.appLabels + labels
            rulesAdapter.notifyItemRangeChanged(0, rulesAdapter.itemCount)
        }
    }

    private fun fetch() {
        val p = model.profile ?: return
        val url = p.remote_url.trim()
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            message(R.string.route_profile_invalid_url_title, getString(R.string.route_profile_invalid_url))
            return
        }
        if (p.isEmpty()) {
            doFetch()
            return
        }
        confirmAction(
            getString(R.string.confirm_replace_rules),
            getString(R.string.route_profile_fetch_confirm),
            R.string.confirm_replace,
        ) { doFetch() }
    }

    /** The editor's "Fetch" (RouteItem.cpp:263-346): the fetched rules replace the edited ones; the typed name stays. */
    private fun doFetch() {
        val p = model.profile ?: return
        undoManager.flush()
        model.fetching = true
        headerAdapter.holder?.bindRemoteState()
        lifecycleScope.launch {
            try {
                val copy = p.copy()
                val warnings = ArrayList<String>()
                val error = withContext(Dispatchers.IO) { RemoteRouteUpdater.fetchInto(copy, warnings) }
                if (error != null) {
                    model.fetching = false
                    headerAdapter.holder?.bindRemoteState()
                    message(R.string.route_profile_fetch_failed, error)
                    return@launch
                }
                p.rules = copy.rules
                p.default_outbound_id = copy.default_outbound_id
                p.remote_last_update = copy.remote_last_update
                if (p.name.isBlank()) p.name = copy.name
                nameRules(p)
                val (servers, labels) = withContext(Dispatchers.IO) {
                    RouteServers.names(p.rules.map { it.outbound_id }) to appLabels(labelledPackages(p.rules))
                }
                model.servers = servers
                model.appLabels = labels
                model.warnings = warnings
                model.fetching = false
                markDirty()
                refreshAll()
                val loaded = getString(R.string.route_profile_fetched, p.rules.size)
                if (warnings.isEmpty()) {
                    message(R.string.route_profile_fetched_title, loaded)
                } else {
                    message(R.string.route_profile_fetched_warnings_title, loaded + "\n\n" + warnings.joinToString("\n"))
                }
            } finally {
                model.fetching = false
            }
        }
    }

    private inner class HeaderAdapter : RecyclerView.Adapter<HeaderHolder>() {
        var holder: HeaderHolder? = null

        override fun getItemCount() = if (model.profile == null) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            HeaderHolder(LayoutRouteProfileHeaderBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: HeaderHolder, position: Int) {
            this.holder = holder
            holder.bind()
        }
    }

    private inner class HeaderHolder(val binding: LayoutRouteProfileHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        private var updating = false

        private val chips = mapOf(
            R.id.outbound_proxy to OutboundIds.PROXY,
            R.id.outbound_direct to OutboundIds.DIRECT,
            R.id.outbound_block to OutboundIds.BLOCK,
            R.id.outbound_warp to OutboundIds.WARP_BYPASS,
        )

        init {
            binding.outboundProxy.text = OutboundIds.toName(OutboundIds.PROXY)
            binding.outboundDirect.text = OutboundIds.toName(OutboundIds.DIRECT)
            binding.outboundBlock.text = OutboundIds.toName(OutboundIds.BLOCK)
            binding.outboundWarp.text = RouteTexts.WARP_BYPASS
            binding.name.doAfterTextChanged {
                if (updating) return@doAfterTextChanged
                model.profile?.name = it?.toString().orEmpty()
                markDirty()
            }
            binding.url.doAfterTextChanged {
                if (updating) return@doAfterTextChanged
                model.profile?.remote_url = it?.toString().orEmpty()
                markDirty()
            }
            binding.autoUpdate.setOnCheckedChangeListener { _, checked ->
                if (updating) return@setOnCheckedChangeListener
                model.profile?.auto_update = checked
                markDirty()
            }
            binding.defaultOutbound.setOnCheckedStateChangeListener { _, ids ->
                if (updating) return@setOnCheckedStateChangeListener
                val outbound = ids.firstOrNull()?.let { chips[it] } ?: return@setOnCheckedStateChangeListener
                model.profile?.default_outbound_id = outbound
                markDirty()
            }
            binding.fetch.setOnClickListener { fetch() }
        }

        fun bind() {
            val p = model.profile ?: return
            updating = true
            if (binding.name.text?.toString() != p.name) binding.name.setText(p.name)
            val chip = chips.entries.firstOrNull { it.value == p.default_outbound_id }?.key ?: R.id.outbound_proxy
            binding.defaultOutbound.check(chip)
            binding.remoteGroup.isVisible = p.is_remote
            if (binding.url.text?.toString() != p.remote_url) binding.url.setText(p.remote_url)
            binding.autoUpdate.isChecked = p.auto_update
            updating = false
            bindRemoteState()
            bindReadOnly(p)
        }

        /** A desktop raw profile is shown, never edited. */
        private fun bindReadOnly(p: RouteProfile) {
            binding.name.isEnabled = !p.is_raw
            for (i in 0 until binding.defaultOutbound.childCount) binding.defaultOutbound.getChildAt(i).isEnabled = !p.is_raw
        }

        fun bindRemoteState() {
            val p = model.profile ?: return
            binding.lastUpdate.text = if (p.remote_last_update > 0) {
                getString(R.string.route_profile_last_update, RouteTexts.dateTime(this@RouteProfileActivity, p.remote_last_update))
            } else {
                getString(R.string.route_profile_never_updated)
            }
            binding.fetch.isEnabled = !model.fetching
            binding.fetch.setText(if (model.fetching) R.string.route_profile_fetching else R.string.route_profile_fetch)
            binding.warnings.isVisible = model.warnings.isNotEmpty()
            binding.warnings.text = model.warnings.joinToString("\n")
        }
    }

    /** The routing profile's DNS: the global settings, or its own servers and behaviour. */
    private inner class DnsAdapter : RecyclerView.Adapter<DnsHolder>() {
        var holder: DnsHolder? = null

        override fun getItemCount() = if (model.profile == null || model.profile?.is_raw == true) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            DnsHolder(LayoutRouteDnsBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: DnsHolder, position: Int) {
            this.holder = holder
            holder.bind()
        }
    }

    private inner class DnsHolder(val binding: LayoutRouteDnsBinding) : RecyclerView.ViewHolder(binding.root) {
        private var updating = false
        private val finalChips: Map<Int, String>

        init {
            val entries = resources.getStringArray(R.array.dns_final_out_entries)
            val values = resources.getStringArray(R.array.dns_final_out_values)
            binding.finalRemote.text = entries[0]
            binding.finalDirect.text = entries[1]
            finalChips = mapOf(R.id.final_remote to values[0], R.id.final_direct to values[1])

            binding.mode.setOnCheckedChangeListener { _, id ->
                if (!updating) setCustomDns(id == R.id.mode_custom)
            }
            bindServer(binding.remoteDnsLayout, binding.remoteDns, model.remoteSuggestions, remote = true)
            bindServer(binding.directDnsLayout, binding.directDns, model.directSuggestions, remote = false)
            binding.remoteDnsDisableIpv6.onToggle { dns, on -> dns.copy(remoteDnsDisableIpv6 = on) }
            binding.directDnsDisableIpv6.onToggle { dns, on -> dns.copy(directDnsDisableIpv6 = on) }
            binding.enableDnsRouting.onToggle { dns, on -> dns.copy(enableDnsRouting = on) }
            binding.enableFakedns.onToggle { dns, on -> dns.copy(fakeDns = on) }
            binding.fakeipDisableIpv6.onToggle { dns, on -> dns.copy(fakeIpDisableIpv6 = on) }
            binding.finalOut.setOnCheckedStateChangeListener { _, ids ->
                if (updating) return@setOnCheckedStateChangeListener
                val value = ids.firstOrNull()?.let { finalChips[it] } ?: return@setOnCheckedStateChangeListener
                editDns { it.copy(dnsFinalOut = value) }
            }
        }

        private fun bindServer(layout: TextInputLayout, field: AutoCompleteTextView, suggestions: List<String>, remote: Boolean) {
            DnsServerSuggestions.attach(layout, field, suggestions)
            field.doAfterTextChanged {
                if (updating) return@doAfterTextChanged
                val text = it?.toString().orEmpty()
                editDns { dns -> if (remote) dns.copy(remoteDns = text) else dns.copy(directDns = text) }
                bindErrors()
            }
        }

        private fun CompoundButton.onToggle(change: (RouteDns, Boolean) -> RouteDns) {
            setOnCheckedChangeListener { _, checked ->
                if (updating) return@setOnCheckedChangeListener
                editDns { change(it, checked) }
                bindMode()
            }
        }

        fun bind() {
            val draft = model.dnsDraft ?: return
            updating = true
            binding.mode.check(if (model.dnsCustom) R.id.mode_custom else R.id.mode_global)
            if (binding.remoteDns.text.toString() != draft.remoteDns) binding.remoteDns.setText(draft.remoteDns, false)
            binding.remoteDnsDisableIpv6.isChecked = draft.remoteDnsDisableIpv6
            if (binding.directDns.text.toString() != draft.directDns) binding.directDns.setText(draft.directDns, false)
            binding.directDnsDisableIpv6.isChecked = draft.directDnsDisableIpv6
            binding.finalOut.check(finalChips.entries.firstOrNull { it.value == draft.dnsFinalOut }?.key ?: R.id.final_remote)
            binding.enableDnsRouting.isChecked = draft.enableDnsRouting
            binding.enableFakedns.isChecked = draft.fakeDns
            binding.fakeipDisableIpv6.isChecked = draft.fakeIpDisableIpv6
            updating = false
            bindMode()
            bindErrors()
        }

        fun bindMode() {
            val custom = model.dnsCustom
            binding.fields.isVisible = custom
            binding.globalServers.isVisible = !custom
            binding.objectNote.isVisible = custom && model.dnsObject
            model.dnsGlobal?.let { binding.globalServers.text = getString(R.string.route_dns_global_servers, it.remoteDns, it.directDns) }
            // FakeIP's IPv6 switch only means something with FakeIP on.
            binding.fakeipDisableIpv6.isEnabled = model.dnsDraft?.fakeDns == true
        }

        /** Empty servers are flagged once Apply was tried. */
        fun bindErrors() {
            val draft = model.dnsDraft ?: return
            val check = model.dnsChecked && model.dnsCustom
            binding.remoteDnsLayout.showError(if (check && draft.remoteDns.isBlank()) getString(R.string.route_dns_server_empty) else "")
            binding.directDnsLayout.showError(if (check && draft.directDns.isBlank()) getString(R.string.route_dns_server_empty) else "")
        }

        private fun TextInputLayout.showError(message: String) {
            if (message.isEmpty()) isErrorEnabled = false else error = message
        }
    }

    /** The desktop raw route, read-only, or a note on the desktop endpoints Android does not run. */
    private inner class RawAdapter : RecyclerView.Adapter<RawHolder>() {
        override fun getItemCount(): Int {
            val p = model.profile ?: return 0
            return if (p.is_raw || p.endpointCount() > 0) 1 else 0
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            RawHolder(LayoutRouteRawBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: RawHolder, position: Int) {
            val p = model.profile ?: return
            holder.binding.notice.text = if (p.is_raw) {
                getString(R.string.route_raw_notice)
            } else {
                resources.getQuantityString(R.plurals.route_endpoints_notice, p.endpointCount(), p.endpointCount())
            }
            holder.binding.rawScroll.isVisible = p.is_raw
            holder.binding.rawRoute.text = if (p.is_raw) p.raw_route else ""
        }
    }

    private class RawHolder(val binding: LayoutRouteRawBinding) : RecyclerView.ViewHolder(binding.root)

    private inner class TitleAdapter : RecyclerView.Adapter<TitleHolder>() {
        override fun getItemCount() = if (model.profile == null || model.profile?.is_raw == true) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            TitleHolder(LayoutRouteRulesTitleBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: TitleHolder, position: Int) {
            holder.binding.title.text = getString(R.string.route_profile_rules, model.profile?.rules?.size ?: 0)
        }
    }

    private class TitleHolder(val binding: LayoutRouteRulesTitleBinding) : RecyclerView.ViewHolder(binding.root)

    private inner class RulesAdapter : RecyclerView.Adapter<RuleHolder>(), UndoSnackbarManager.Interface<RouteRule> {

        override fun getItemCount() = model.profile?.rules?.size ?: 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            RuleHolder(LayoutRouteRuleItemBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: RuleHolder, position: Int) {
            holder.bind(model.profile!!.rules[position])
        }

        override fun undo(actions: List<Pair<Int, RouteRule>>) {
            val rules = model.profile?.rules ?: return
            for ((index, rule) in actions) {
                val at = index.coerceIn(0, rules.size)
                rules.add(at, rule)
                notifyItemInserted(at)
            }
            countChanged()
            markDirty()
        }

        override fun commit(actions: List<Pair<Int, RouteRule>>) {
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class RuleHolder(val binding: LayoutRouteRuleItemBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val index = bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) editRule(index)
            }
            binding.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(this)
                false
            }
            binding.ruleEnabled.setOnClickListener {
                val rule = model.profile?.rules?.getOrNull(bindingAdapterPosition) ?: return@setOnClickListener
                rule.disabled = !binding.ruleEnabled.isChecked
                markDirty()
                bindDisabled(rule)
            }
        }

        fun bind(rule: RouteRule) {
            binding.ruleName.text = rule.name
            binding.ruleAction.text = RouteTexts.ruleAction(this@RouteProfileActivity, rule, model.servers)
            binding.ruleSummary.text = RouteTexts.ruleConditions(this@RouteProfileActivity, rule, model.appLabels)
            // Endpoint rules are never emitted on Android, so they get no switch.
            binding.ruleEnabled.isInvisible = rule.type == RuleType.ENDPOINT_PREFERRED_BY.id
            binding.ruleEnabled.isChecked = !rule.disabled
            bindDisabled(rule)
        }

        /** A disabled rule is muted and says what that means. */
        private fun bindDisabled(rule: RouteRule) {
            val disabled = rule.disabled && rule.type != RuleType.ENDPOINT_PREFERRED_BY.id
            val alpha = if (disabled) DISABLED_ALPHA else 1f
            binding.ruleName.alpha = alpha
            binding.ruleAction.alpha = alpha
            binding.ruleSummary.alpha = alpha
            binding.ruleDisabled.isVisible = disabled
        }
    }

    private inner class FooterAdapter : RecyclerView.Adapter<FooterHolder>() {
        override fun getItemCount() = if (model.profile == null || model.profile?.is_raw == true) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            FooterHolder(LayoutRouteAddRuleBinding.inflate(layoutInflater, parent, false)).also { holder ->
                holder.binding.root.setOnClickListener { addRule() }
            }

        override fun onBindViewHolder(holder: FooterHolder, position: Int) {
        }
    }

    private class FooterHolder(val binding: LayoutRouteAddRuleBinding) : RecyclerView.ViewHolder(binding.root)

    private inner class TouchCallback : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START or ItemTouchHelper.END
    ) {
        override fun isLongPressDragEnabled() = false

        override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) =
            if (viewHolder is RuleHolder) super.getDragDirs(recyclerView, viewHolder) else 0

        override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) =
            if (viewHolder is RuleHolder) super.getSwipeDirs(recyclerView, viewHolder) else 0

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            if (target !is RuleHolder) return false
            val rules = model.profile?.rules ?: return false
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            rules.add(to, rules.removeAt(from))
            rulesAdapter.notifyItemMoved(from, to)
            markDirty()
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val rules = model.profile?.rules ?: return
            val index = viewHolder.bindingAdapterPosition
            if (index == RecyclerView.NO_POSITION) return
            val removed = rules.removeAt(index)
            rulesAdapter.notifyItemRemoved(index)
            countChanged()
            markDirty()
            undoManager.remove(index to removed)
        }
    }
}
