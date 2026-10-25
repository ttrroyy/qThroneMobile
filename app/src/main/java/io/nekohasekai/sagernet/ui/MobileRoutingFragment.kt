package app.qthrone.mobile.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import android.graphics.Color
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.qthrone.mobile.ui.MobileAlertDialogBuilder as MaterialAlertDialogBuilder
import app.qthrone.mobile.R
import app.qthrone.mobile.database.RouteManager
import app.qthrone.mobile.database.SagerDatabase
import app.qthrone.mobile.ktx.needReload
import app.qthrone.mobile.route.OutboundIds
import app.qthrone.mobile.route.RouteProfile
import app.qthrone.mobile.route.RouteRule
import app.qthrone.mobile.route.MobileRouteInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A small editor over the existing route repository, preserving advanced rules. */
class MobileRoutingFragment : ToolbarFragment() {
    private var column: LinearLayout? = null
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun line(label: String, action: (() -> Unit)? = null) = TextView(requireContext()).apply {
        text = label; textSize = 16f; setTextColor(MobilePalette.accent(context))
        setPadding(dp(16),dp(16),dp(16),dp(16)); minHeight = dp(48)
        if (action != null) { isFocusable = true; setOnClickListener { action() } }
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(MobilePalette.background)
        }
        return ScrollView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isFillViewport = true
            setBackgroundColor(MobilePalette.background)
            addView(column, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
    }
    override fun onViewCreated(view: View, state: Bundle?) { super.onViewCreated(view,state); render() }
    override fun onDestroyView() { column = null; super.onDestroyView() }
    private fun render() {
        viewLifecycleOwner.lifecycleScope.launch {
            val route = withContext(Dispatchers.IO) { RouteManager.current().copy() }
            val profiles = withContext(Dispatchers.IO) { SagerDatabase.proxyDao.getAll() }
            fun target(id: Long): String = when (id) {
                OutboundIds.PROXY -> getString(R.string.qmobile_route_main)
                OutboundIds.DIRECT -> getString(R.string.qmobile_route_direct)
                else -> profiles.firstOrNull { it.id == id }?.displayName() ?: "#$id"
            }
            val root = column ?: return@launch
            root.removeAllViews()
            root.addView(line("\u2039  " + getString(R.string.qmobile_routing)) { requireActivity().onBackPressedDispatcher.onBackPressed() })
            root.addView(line(getString(R.string.qmobile_route_default) + "\n" + target(route.default_outbound_id)) {
                chooseTarget(route.default_outbound_id) { id -> save(route) { it.default_outbound_id=id } }
            })
            root.addView(line(getString(R.string.qmobile_route_add)) { newRule(route) })
            for ((index, rule) in route.rules.withIndex()) {
                val matches = rule.domain_suffix + rule.domain + rule.ip_cidr + rule.package_name
                val title = rule.name.ifBlank { matches.joinToString(", ").ifBlank { getString(R.string.qmobile_route_advanced) } }
                val state = if (rule.disabled) " · " + getString(R.string.route_rule_off_mark) else ""
                root.addView(line(title + "\n" + target(rule.outbound_id) + state) {
                    val toggle = getString(if (rule.disabled) R.string.route_rule_turn_on else R.string.route_rule_turn_off)
                    MaterialAlertDialogBuilder(requireContext()).setTitle(title)
                        .setItems(arrayOf(getString(R.string.qmobile_route_target),toggle,getString(R.string.qmobile_route_remove))) { _,action ->
                            if (action==0) chooseTarget(rule.outbound_id) { id -> save(route) { it.rules[index].outbound_id=id } }
                            else if (action==1) save(route) { it.rules[index].disabled=!rule.disabled }
                            else MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.qmobile_route_remove)
                                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok) { _,_ -> save(route) { it.rules.removeAt(index) } }.show()
                        }.show()
                })
            }
            root.addView(line(getString(R.string.qmobile_route_advanced)) { (requireActivity() as MainActivity).displayFragment(SettingsFragment.forScreen("app.qthrone.mobile.ui.settings.RoutingSettingsFragment",getString(R.string.qmobile_route_advanced))) })
        }
    }
    private fun save(snapshot: RouteProfile, change: (RouteProfile) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val current = RouteManager.get(snapshot.id) ?: return@withContext false
                // Avoid overwriting a subscription update or changes from another editor.
                if (current.is_remote || current.prevent_modifications) return@withContext false
                if (current.rules.map { it.toRuleJson(false).toString() } != snapshot.rules.map { it.toRuleJson(false).toString() }
                    || current.default_outbound_id != snapshot.default_outbound_id) return@withContext false
                change(current); RouteManager.save(current); true
            }
            if (saved) needReload()
            render()
        }
    }
    private fun chooseTarget(selected: Long, chosen: (Long) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            val profiles = withContext(Dispatchers.IO) { SagerDatabase.proxyDao.getAll() }
            val ids = listOf(OutboundIds.PROXY,OutboundIds.DIRECT) + profiles.map { it.id }
            val labels = listOf(getString(R.string.qmobile_route_main),getString(R.string.qmobile_route_direct)) + profiles.map { it.displayName() }
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.qmobile_route_target)
                .setSingleChoiceItems(labels.toTypedArray(),ids.indexOf(selected)) { dialog,index -> dialog.dismiss(); chosen(ids[index]) }
                .setNegativeButton(android.R.string.cancel,null).show()
        }
    }
    private fun newRule(route: RouteProfile) {
        if (route.is_remote || route.prevent_modifications) return
        val kinds = arrayOf(getString(R.string.qmobile_route_domains),getString(R.string.qmobile_route_ips),getString(R.string.qmobile_route_apps))
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.qmobile_route_add).setItems(kinds) { _,kind ->
            if (kind==2) { chooseApps(route); return@setItems }
            val field = EditText(requireContext()).apply { hint=kinds[kind]; minLines=3; maxLines=8 }
            val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(kinds[kind]).setView(field)
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,null).create()
            dialog.setOnShowListener {
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val values=runCatching {
                        if(kind==0) MobileRouteInput.domains(field.text.toString()) else MobileRouteInput.networks(field.text.toString())
                    }.getOrElse { field.error=getString(R.string.qmobile_route_invalid); return@setOnClickListener }
                    dialog.dismiss()
                    chooseTarget(OutboundIds.DIRECT) { target ->
                        val rule=RouteRule().apply {
                            name=values.joinToString(", "); outbound_id=target
                            when(kind) { 0 -> domain_suffix.addAll(values); 1 -> ip_cidr.addAll(values); else -> package_name.addAll(values) }
                        }
                        save(route) { it.rules.add(rule) }
                    }
                }
            }
            dialog.show()
        }.show()
    }
    private fun chooseApps(route: RouteProfile) {
        viewLifecycleOwner.lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm=requireContext().packageManager
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0).filter { it.packageName != requireContext().packageName }
                    .map { it.packageName to pm.getApplicationLabel(it).toString() }.sortedBy { it.second.lowercase() }
            }
            val checked=BooleanArray(apps.size)
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.qmobile_route_apps)
                .setMultiChoiceItems(apps.map { "${it.second}\n${it.first}" }.toTypedArray(),checked) { _,index,value -> checked[index]=value }
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok) { _,_ ->
                    val selected=apps.filterIndexed { index,_ -> checked[index] }
                    if(selected.isNotEmpty()) chooseTarget(OutboundIds.DIRECT) { target ->
                        save(route) { it.rules.add(RouteRule().apply { name=selected.joinToString(", ") { app -> app.second }; package_name.addAll(selected.map { app -> app.first }); outbound_id=target }) }
                    }
                }.show()
        }
    }
}
