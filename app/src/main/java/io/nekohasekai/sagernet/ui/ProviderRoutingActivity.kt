package app.qthrone.mobile.ui

import android.os.Bundle
import android.content.Intent
import android.text.InputType
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import app.qthrone.mobile.R
import app.qthrone.mobile.database.*
import app.qthrone.mobile.ktx.needReload
import app.qthrone.mobile.ktx.readableMessage
import app.qthrone.mobile.TrafficFormat
import app.qthrone.mobile.route.ProviderGeoAssets
import app.qthrone.mobile.outbound.json.*
import app.qthrone.mobile.route.ProviderRouting
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class ProviderRoutingActivity : ThemedActivity() {
    private lateinit var messageHost: androidx.coordinatorlayout.widget.CoordinatorLayout

    override fun snackbarInternal(text: CharSequence): com.google.android.material.snackbar.Snackbar =
        com.google.android.material.snackbar.Snackbar.make(messageHost,text,com.google.android.material.snackbar.Snackbar.LENGTH_LONG)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(MobilePalette.background) }
        val toolbar = Toolbar(this).apply {
            setTitleTextColor(MobilePalette.text); setNavigationIcon(R.drawable.baseline_arrow_back_24)
            setNavigationOnClickListener { finish() }
        }
        val content = FrameLayout(this).apply { id = android.R.id.content + 1 }
        root.addView(toolbar); root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        messageHost = androidx.coordinatorlayout.widget.CoordinatorLayout(this).apply {
            setBackgroundColor(MobilePalette.background)
            addView(root,androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(-1,-1))
        }
        setContentView(messageHost)
        ViewCompat.setOnApplyWindowInsetsListener(messageHost) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        lifecycleScope.launch {
            val group = withContext(Dispatchers.IO) { GroupRepo.get(intent.getLongExtra("groupId",0)) }
            if (group == null) { finish(); return@launch }
            toolbar.title = if (intent.getBooleanExtra("details",false)) getString(R.string.provider_rules_title) else getString(R.string.provider_routing_title)
            if (state == null) supportFragmentManager.beginTransaction().replace(content.id,Screen().apply {
                arguments = Bundle().apply { putLong("groupId",group.id); putBoolean("details",intent.getBooleanExtra("details",false)); putString("section",intent.getStringExtra("section")) }
            }).commit()
        }
    }

    class Screen : PreferenceFragmentCompat() {
        private var group: ProxyGroup? = null
        private var document = JsonObject()
        private val controls = mutableListOf<Preference>()
        private var saving: Job? = null
        private val saveLock = Mutex()
        private var fullJson = false
        override fun onCreatePreferences(state: Bundle?, rootKey: String?) {
            preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
            lifecycleScope.launch {
                group = withContext(Dispatchers.IO) {
                    val gid = requireArguments().getLong("groupId")
                    val profiles = SagerDatabase.proxyDao.getByGroup(gid)
                    fullJson = profiles.isNotEmpty() && profiles.all { it.outbound.isXrayFullConfig() }
                    GroupRepo.get(gid)
                }
                if (group == null) return@launch
                build()
            }
        }
        private fun build() {
            val options = group!!.subOptions
            document = JsonInput.parseObjectOrNull(options.providerRouting)?.let {
                app.qthrone.mobile.route.ProviderRouteProfiles.tagged(it,app.qthrone.mobile.route.ProviderRouteProfiles.source(it,options.routingProvider))
            } ?: JsonObject()
            preferenceScreen.removeAll(); controls.clear()
            val details = requireArguments().getBoolean("details")
            fun category(label: Int) { preferenceScreen.addPreference(PreferenceCategory(requireContext()).apply { layoutResource = R.layout.provider_preference_text; isIconSpaceReserved = false; title = getString(label) }) }
            if (!details) {
            category(R.string.provider_general)
            preferenceScreen.addPreference(Preference(requireContext()).apply { layoutResource = R.layout.provider_preference; isIconSpaceReserved = false; title = getString(R.string.provider_subscription_name); summary = group!!.displayName(); isSelectable = false })
            val enabled = SwitchPreferenceCompat(requireContext()).apply {
                key = "providerEnabled"; isPersistent = false; isSingleLineTitle = false
                layoutResource = R.layout.provider_preference
                title = getString(R.string.provider_routing_use,options.routingProvider)
                summary = getString(R.string.provider_routing_use_summary)
                isChecked = options.providerRoutingEnabled
                isIconSpaceReserved = false
                setOnPreferenceChangeListener { _, value ->
                    options.providerRoutingEnabled = value as Boolean
                    save(); true
                }
            }
            preferenceScreen.addPreference(enabled)
            preferenceScreen.addPreference(SwitchPreferenceCompat(requireContext()).apply {
                key = "providerIgnoreImport"; layoutResource = R.layout.provider_preference; isIconSpaceReserved = false
                isPersistent = false; isSingleLineTitle = false; title = getString(R.string.provider_ignore_import)
                isChecked = options.providerIgnoreImport
                setOnPreferenceChangeListener { _, value -> options.providerIgnoreImport = value as Boolean; save(); true }
            })
            fun add(preference: Preference) { preference.isSingleLineTitle = false; preference.layoutResource = R.layout.provider_preference; preference.isPersistent = false; controls.add(preference); preferenceScreen.addPreference(preference) }
            val profiles = (JsonInput.parseValue(options.providerRoutingProfiles) as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
            val choices=app.qthrone.mobile.route.ProviderRouteProfiles.choices(profiles,document,options.routingProvider)
            val selectedChoice=choices.firstOrNull {
                app.qthrone.mobile.route.ProviderRouteProfiles.key(document,options.routingProvider) in it.keys
            } ?: choices.firstOrNull()
            add(Preference(requireContext()).apply {
                isIconSpaceReserved = false
                key = "providerProfile"; title = getString(R.string.provider_routing_profile)
                val selected=choices.indexOfFirst { app.qthrone.mobile.route.ProviderRouteProfiles.key(document,options.routingProvider) in it.keys }.coerceAtLeast(0)
                summary = selectedChoice?.label
                    ?: document.string("Name").ifBlank { "Default" }
                setOnPreferenceClickListener {
                    val adapter=object:android.widget.BaseAdapter() {
                        override fun getCount()=choices.size
                        override fun getItem(position:Int)=choices[position]
                        override fun getItemId(position:Int)=position.toLong()
                        override fun getView(position:Int,reused:View?,parent:android.view.ViewGroup):View {
                            val density=resources.displayMetrics.density
                            val choice=choices[position]
                            return LinearLayout(requireContext()).apply {
                                orientation=LinearLayout.VERTICAL
                                setPadding((24*density).toInt(),(12*density).toInt(),(24*density).toInt(),(12*density).toInt())
                                addView(android.widget.TextView(context).apply {
                                    text=choice.label;textSize=17f;setTextColor(MobilePalette.text)
                                    setTypeface(typeface,if(position==selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                                })
                                addView(android.widget.TextView(context).apply {
                                    text=getString(R.string.provider_profile_source,choice.providers);textSize=13f;setTextColor(MobilePalette.muted)
                                })
                            }
                        }
                    }
                    MobileAlertDialogBuilder(requireContext()).setTitle(R.string.provider_routing_profile)
                        .setAdapter(adapter) { _,index ->
                            document=choices[index].document.copy();options.providerRouting=document.toCompact()
                            options.routingProvider=app.qthrone.mobile.route.ProviderRouteProfiles.source(document,options.routingProvider)
                            options.providerRoutingManual=true
                            save();build()
                        }.show()
                    true
                }
                isEnabled = choices.isNotEmpty()
            })
            category(R.string.provider_profile_settings)
            add(Preference(requireContext()).apply {
                isIconSpaceReserved = false
                widgetLayoutResource = R.layout.qmobile_preference_arrow
                title = document.string("Name").ifBlank { "Default" }
                summary = getString(R.string.provider_profile_source,selectedChoice?.providers
                    ?: app.qthrone.mobile.route.ProviderRouteProfiles.source(document,options.routingProvider))
                setOnPreferenceClickListener {
                    startActivity(Intent(requireContext(),ProviderRoutingActivity::class.java).putExtra("groupId",group!!.id).putExtra("details",true)); true
                }
            })
            if (fullJson) preferenceScreen.addPreference(Preference(requireContext()).apply { layoutResource = R.layout.provider_preference_text; isIconSpaceReserved = false; key = "providerJsonWarning"; summary = getString(R.string.provider_json_notice,options.routingProvider); isSelectable = false; isPersistent = false })
            return
            }
            fun add(preference: Preference) {
                preference.isSingleLineTitle = false
                preference.layoutResource = if (preference.key?.startsWith("providerWarning") == true) R.layout.provider_preference_text else R.layout.provider_preference
                preference.isPersistent = false
                preference.icon = null; preference.isIconSpaceReserved = false
                preferenceScreen.addPreference(preference)
            }
            fun toggle(keyName: String, label: Int, fallback: Boolean) = add(SwitchPreferenceCompat(requireContext()).apply {
                key = keyName; title = getString(label); isChecked = ProviderRouting.truth(document,keyName,fallback)
                setOnPreferenceChangeListener { _, next -> document[keyName] = (next as Boolean).toString(); update(); true }
            })
            fun choice(keyName: String,label: Int,values: Array<String>,fallback: String) = add(SimpleMenuPreference(requireContext()).apply {
                key = keyName; title = getString(label); entries = if (keyName == "RouteOrder") values.map { it.replace("-"," → ") }.toTypedArray() else values; entryValues = values
                value = document.string(keyName).ifBlank { fallback }; summary = if (keyName == "RouteOrder") value?.replace("-"," → ") else value
                setOnPreferenceChangeListener { _, next -> document[keyName] = next as String; summary = if (keyName == "RouteOrder") next.replace("-"," → ") else next; update(); true }
            })
            fun field(keyName: String,label: Int,default: String = "",array: Boolean = false,hosts: Boolean = false) = add(EditTextPreference(requireContext()).apply {
                key = keyName; title = getString(label)
                text = when { array -> document.array(keyName).strings().joinToString("\n"); hosts -> document.obj(keyName).toCompact(); else -> document.string(keyName).ifBlank { default } }
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
                setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or if (array || hosts) InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_TEXT_VARIATION_URI; it.setHorizontallyScrolling(false) }
                setOnPreferenceChangeListener { _, next ->
                    val candidate = document.copy()
                    candidate[keyName] = when { array -> JsonArray().apply { (next as String).lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) } }; hosts -> JsonInput.parseObjectOrNull(next as String) ?: return@setOnPreferenceChangeListener false; else -> next as String }
                    if (runCatching { ProviderRouting.profile(candidate,options.routingProvider) }.isFailure) return@setOnPreferenceChangeListener false
                    document = candidate; update(); true
                }
            })
            val section = requireArguments().getString("section").orEmpty()
            if (section in listOf("Proxy","Direct","Block")) {
                val labels = when(section) {
                    "Proxy" -> R.string.provider_routing_proxy_sites to R.string.provider_routing_proxy_ips
                    "Direct" -> R.string.provider_routing_direct_sites to R.string.provider_routing_direct_ips
                    else -> R.string.provider_routing_block_sites to R.string.provider_routing_block_ips
                }
                field(section + "Sites",labels.first,array = true)
                field(section + "Ip",labels.second,array = true)
                return
            }
            if (section == "manual") {
                add(EditTextPreference(requireContext()).apply {
                    key = "providerManual"; title = getString(R.string.provider_edit_rules)
                    text = app.qthrone.mobile.route.ProviderRouteProfiles.externalDocument(document).toCompact()
                    setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; it.setHorizontallyScrolling(false) }
                    setOnPreferenceChangeListener { _, next ->
                        val candidate = runCatching { ProviderRouting.parse(next as String,options.routingProvider).document }.getOrNull() ?: return@setOnPreferenceChangeListener false
                        if (runCatching { ProviderRouting.profile(candidate,options.routingProvider) }.isFailure) return@setOnPreferenceChangeListener false
                        document = app.qthrone.mobile.route.ProviderRouteProfiles.tagged(candidate,
                            app.qthrone.mobile.route.ProviderRouteProfiles.source(document,options.routingProvider)); update(); true
                    }
                })
                field("DnsHosts",R.string.provider_routing_hosts,hosts = true)
                return
            }
            if (fullJson) add(Preference(requireContext()).apply { title = getString(R.string.provider_profile_heading); summary = document.string("Name").ifBlank { "Default" }; isSelectable = false })
            else field("Name",R.string.provider_profile_heading,"Default")
            category(R.string.provider_geo_files)
            for ((kind,label) in listOf("geosite" to R.string.provider_routing_geosite,"geoip" to R.string.provider_routing_geoip)) {
                add(object : EditTextPreference(requireContext()) {
                    override fun onBindViewHolder(holder: PreferenceViewHolder) {
                        super.onBindViewHolder(holder)
                        holder.findViewById(R.id.provider_geo_refresh)?.apply {
                            isEnabled = true
                            (this as? android.widget.ImageButton)?.imageTintList = android.content.res.ColorStateList.valueOf(MobilePalette.accent(requireContext()))
                            setOnClickListener {
                                isEnabled = false
                                val refreshButton = this
                                startAnimation(android.view.animation.RotateAnimation(
                                    0f,360f,android.view.animation.Animation.RELATIVE_TO_SELF,0.5f,
                                    android.view.animation.Animation.RELATIVE_TO_SELF,0.5f
                                ).apply {
                                    duration = 900
                                    repeatCount = android.view.animation.Animation.INFINITE
                                    interpolator = android.view.animation.LinearInterpolator()
                                })
                                val snapshot = document.copy()
                                lifecycleScope.launch {
                                    val error = try {
                                        withContext(Dispatchers.IO) { runCatching { ProviderGeoAssets.refresh(group!!.id,snapshot,kind) }.exceptionOrNull() }
                                    } finally {
                                        refreshButton.clearAnimation()
                                        refreshButton.isEnabled = true
                                    }
                                    if (error != null) android.widget.Toast.makeText(requireContext(),error.readableMessage,android.widget.Toast.LENGTH_LONG).show()
                                    build()
                                }
                            }
                        }
                    }
                }.apply {
                    key = "geo-$kind"; title = getString(label)
                    widgetLayoutResource = R.layout.provider_geo_refresh
                    val file = ProviderGeoAssets.file(document,kind)
                    text = ProviderGeoAssets.url(document,kind)
                    summary = text + if (file.isFile) "\n" + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(file.lastModified())) + ", " + TrafficFormat.format(file.length()) else ""
                    isSelectable = !fullJson
                    setOnPreferenceChangeListener { _, next ->
                        val value = next as String
                        if (!value.startsWith("https://") && !value.startsWith("http://")) return@setOnPreferenceChangeListener false
                        document[if (kind == "geoip") "Geoipurl" else "Geositeurl"] = value; summary = value; update(); true
                    }
                })
            }
            fun link(label: Int, destination: String) = add(Preference(requireContext()).apply {
                title = getString(label); widgetLayoutResource = R.layout.qmobile_preference_arrow
                setOnPreferenceClickListener {
                    startActivity(Intent(requireContext(),ProviderRoutingActivity::class.java).putExtra("groupId",group!!.id).putExtra("details",true).putExtra("section",destination)); true
                }
            })
            if (!fullJson) {
                category(R.string.provider_manual)
                link(R.string.provider_edit_rules,"manual")
                category(R.string.provider_domain_settings)
                toggle("FakeDNS",R.string.provider_fake_dns,false)
                choice("DomainStrategy",R.string.provider_domain_settings,arrayOf("AsIs","IPIfNonMatch","IPOnDemand"),"AsIs")
            }
            category(if (fullJson) R.string.provider_tunnel_dns else R.string.provider_remote_dns)
            choice("RemoteDNSType",if (fullJson) R.string.provider_tunnel_type else R.string.provider_routing_remote_type,arrayOf("DoH","DoU"),"DoH")
            field("RemoteDNSDomain",if (fullJson) R.string.provider_tunnel_domain else R.string.provider_routing_remote_domain,"https://cloudflare-dns.com/dns-query")
            field("RemoteDNSIP",if (fullJson) R.string.provider_tunnel_ip else R.string.provider_routing_remote_ip,"1.1.1.1")
            if (fullJson) return
            category(R.string.provider_direct_dns)
            choice("DomesticDNSType",R.string.provider_routing_direct_type,arrayOf("DoH","DoU"),"DoU")
            field("DomesticDNSDomain",R.string.provider_routing_direct_domain)
            field("DomesticDNSIP",R.string.provider_routing_direct_ip,"8.8.8.8")
            category(R.string.provider_proxy_settings)
            toggle("GlobalProxy",R.string.provider_global_proxy,true)
            add(Preference(requireContext()).apply { key = "providerWarningProxy"; layoutResource = R.layout.provider_preference_text; summary = getString(R.string.provider_proxy_notice); isSelectable = false })
            category(R.string.provider_routing_settings)
            link(R.string.provider_proxy,"Proxy"); link(R.string.provider_direct,"Direct"); link(R.string.provider_block,"Block")
            category(R.string.provider_order_title)
            choice("RouteOrder",R.string.provider_order,arrayOf("block-proxy-direct","block-direct-proxy","proxy-block-direct","proxy-direct-block","direct-block-proxy","direct-proxy-block"),"block-proxy-direct")
        }
        private fun update() {
            val options = group!!.subOptions
            val previous = JsonInput.parseObjectOrNull(options.providerRouting)
            val profiles = (JsonInput.parseValue(options.providerRoutingProfiles) as? JsonArray)?.filterIsInstance<JsonObject>()?.toMutableList() ?: mutableListOf()
            val index = profiles.indexOfFirst { previous!=null &&
                app.qthrone.mobile.route.ProviderRouteProfiles.key(it,options.routingProvider)==app.qthrone.mobile.route.ProviderRouteProfiles.key(previous,options.routingProvider) }
            if (index >= 0) profiles[index] = document.copy() else profiles.add(document.copy())
            options.providerRoutingProfiles = JsonArray().apply { profiles.forEach { add(it) } }.toCompact()
            options.providerRouting = document.toCompact(); options.providerRoutingManual=true; save()
        }
        private fun save() {
            val gid = group!!.id; val options = group!!.subOptions.copy()
            saving = lifecycleScope.launch {
                saveLock.withLock {
                val reload=withContext(Dispatchers.IO) {
                    var changed=false
                    SagerDatabase.instance.runInTransaction {
                        val latest = GroupRepo.get(gid) ?: return@runInTransaction
                        changed=app.qthrone.mobile.route.ProviderRouteProfiles.requiresReload(
                            latest.subOptions.providerRoutingEnabled,options.providerRoutingEnabled,
                            latest.subOptions.providerRouting,options.providerRouting)
                        latest.subOptions.routingProvider = options.routingProvider
                        latest.subOptions.providerRoutingEnabled = options.providerRoutingEnabled
                        latest.subOptions.providerRouting = options.providerRouting
                        latest.subOptions.providerRoutingProfiles = options.providerRoutingProfiles
                        latest.subOptions.providerIgnoreImport = options.providerIgnoreImport
                        latest.subOptions.providerRoutingManual = options.providerRoutingManual
                        SagerDatabase.groupDao.update(latest)
                    }
                    changed
                }
                if(reload) (activity as? ProviderRoutingActivity)?.needReload()
                }
            }
        }
        override fun onDisplayPreferenceDialog(preference: Preference) {
            if (preference is DialogPreference && preference.dialogTitle.isNullOrBlank()) {
                preference.dialogTitle = preference.title
            }
            if (preference is EditTextPreference) MobilePreferenceDialog().apply {
                arguments = Bundle().apply { putString("key",preference.key) }; setTargetFragment(this@Screen,0)
            }.show(parentFragmentManager,"provider-input") else super.onDisplayPreferenceDialog(preference)
        }
    }
}
