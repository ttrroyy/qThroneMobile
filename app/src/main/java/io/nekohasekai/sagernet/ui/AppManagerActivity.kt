package app.qthrone.mobile.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.TextUtils
import android.util.SparseBooleanArray
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Filter
import android.widget.Filterable
import androidx.annotation.UiThread
import androidx.core.util.contains
import androidx.core.util.set
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView
import app.qthrone.mobile.BuildConfig
import app.qthrone.mobile.R
import app.qthrone.mobile.SagerNet
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.databinding.LayoutAppsBinding
import app.qthrone.mobile.databinding.LayoutAppsItemBinding
import app.qthrone.mobile.ktx.Logs
import app.qthrone.mobile.ktx.app
import app.qthrone.mobile.ktx.confirmAction
import app.qthrone.mobile.ktx.crossFadeFrom
import app.qthrone.mobile.ktx.onMainDispatcher
import app.qthrone.mobile.ktx.runOnDefaultDispatcher
import app.qthrone.mobile.utils.PackageCache
import app.qthrone.mobile.widget.applyListInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.utils.NGUtil
import kotlin.coroutines.coroutineContext

/**
 * Per-app proxy. Installed apps are selected by uid; stored packages the list cannot show (hidden, not installed,
 * restored from a backup) are kept as rows of their own and written back untouched.
 */
class AppManagerActivity : ThemedActivity() {
    companion object {
        @SuppressLint("StaticFieldLeak")
        private var instance: AppManagerActivity? = null
        private const val SWITCH = "switch"

        private fun cachedApps(cache: PackageCache.Snapshot = PackageCache.snapshot()) =
            cache.installedPackages.toMutableMap().apply {
                remove(BuildConfig.APPLICATION_ID)
            }
    }

    /** [byName] rows stand for stored packages outside [cachedApps]: they are selected by name, not by uid. */
    private class ProxiedApp(
        cache: PackageCache.Snapshot, private val appInfo: ApplicationInfo?,
        val packageName: String, val byName: Boolean = false,
    ) {
        // The listing caches labels, so reopening the screen does not load them all again.
        val name: CharSequence = if (appInfo != null) cache.loadLabel(packageName) else packageName
        val icon: Drawable get() = appInfo?.loadIcon(app.packageManager) ?: app.packageManager.defaultActivityIcon
        val uid get() = appInfo?.uid ?: -1
        val sys get() = appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val installed get() = appInfo != null
    }

    private inner class AppViewHolder(val binding: LayoutAppsItemBinding) : RecyclerView.ViewHolder(
        binding.root
    ),
        View.OnClickListener {
        private lateinit var item: ProxiedApp

        init {
            binding.root.setOnClickListener(this)
            binding.root.setCardBackgroundColor(MobilePalette.surface)
            binding.root.strokeWidth = 0
            binding.root.radius = 20 * resources.displayMetrics.density
            binding.title.setTextColor(MobilePalette.text)
            binding.title.setTypeface(null, android.graphics.Typeface.BOLD)
            binding.title.textSize = 16f
            binding.desc.setTextColor(MobilePalette.muted)
        }

        fun bind(app: ProxiedApp) {
            item = app
            binding.itemicon.setImageDrawable(app.icon)
            binding.title.text = app.name
            binding.desc.text = if (app.installed) {
                "${app.packageName} (${app.uid})"
            } else {
                getString(R.string.app_not_installed, app.packageName)
            }
            binding.itemcheck.isChecked = isProxiedApp(app)
        }

        fun handlePayload(payloads: List<String>) {
            if (payloads.contains(SWITCH)) binding.itemcheck.isChecked = isProxiedApp(item)
        }

        override fun onClick(v: View?) {
            when {
                item.byName -> if (!extraSelected.remove(item.packageName)) extraSelected.add(item.packageName)
                isProxiedApp(item) -> proxiedUids.delete(item.uid)
                else -> proxiedUids[item.uid] = true
            }
            selectionChanged()

            appsAdapter.notifyItemRangeChanged(0, appsAdapter.itemCount, SWITCH)
        }
    }

    private inner class AppsAdapter : RecyclerView.Adapter<AppViewHolder>(),
        Filterable,
        FastScrollRecyclerView.SectionedAdapter {
        var filteredApps = apps

        suspend fun reload() {
            // The listing package broadcasts keep current; a fresh query of every package only delayed the list.
            val cache = PackageCache.snapshot()
            if (!selectionLoaded) {
                initProxiedUids(cache, restoredSelection ?: DataStore.individual)
                selectionLoaded = true
            }
            val cached = cachedApps(cache)
            val list = cached.mapNotNull { (packageName, packageInfo) ->
                coroutineContext[Job]!!.ensureActive()
                packageInfo.applicationInfo?.let { ProxiedApp(cache, it, packageName) }
            }.toMutableList()
            for (packageName in extraSelected) {
                list.add(ProxiedApp(cache, cache.installedApps[packageName], packageName, byName = true))
            }
            apps = sorted(list)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) =
            holder.bind(filteredApps[position])

        override fun onBindViewHolder(holder: AppViewHolder, position: Int, payloads: List<Any>) {
            if (payloads.isNotEmpty()) {
                @Suppress("UNCHECKED_CAST") holder.handlePayload(payloads as List<String>)
                return
            }

            onBindViewHolder(holder, position)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder =
            AppViewHolder(LayoutAppsItemBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = filteredApps.size

        private val filterImpl = object : Filter() {
            override fun performFiltering(constraint: CharSequence) = FilterResults().apply {
                var filteredApps = if (constraint.isEmpty()) apps else apps.filter {
                    it.name.contains(constraint, true) || it.packageName.contains(
                        constraint, true
                    ) || it.uid.toString().contains(constraint)
                }
                if (!sysApps) filteredApps = filteredApps.filter { !it.sys || it.byName }
                count = filteredApps.size
                values = filteredApps
            }

            override fun publishResults(constraint: CharSequence, results: FilterResults) {
                @Suppress("UNCHECKED_CAST")
                filteredApps = results.values as List<ProxiedApp>
                notifyDataSetChanged()
            }
        }

        override fun getFilter(): Filter = filterImpl

        override fun getSectionName(position: Int): String {
            return filteredApps[position].name.firstOrNull()?.toString() ?: ""
        }

    }

    private val loading by lazy { findViewById<View>(R.id.loading) }

    private lateinit var binding: LayoutAppsBinding
    private val proxiedUids = SparseBooleanArray()
    private val extraSelected = LinkedHashSet<String>()
    private var selectionLoaded = false
    private var loader: Job? = null
    private var apps = emptyList<ProxiedApp>()
    private val appsAdapter = AppsAdapter()

    private fun initProxiedUids(cache: PackageCache.Snapshot, str: String = DataStore.individual) {
        proxiedUids.clear()
        extraSelected.clear()
        val apps = cachedApps(cache)
        for (line in str.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }) {
            val uid = apps[line]?.applicationInfo?.uid
            if (uid != null) proxiedUids[uid] = true else extraSelected.add(line)
        }
    }

    private fun isProxiedApp(app: ProxiedApp) =
        if (app.byName) app.packageName in extraSelected else proxiedUids[app.uid]

    /** 0: every app, 1: only the selected, 2: all but the selected. Saved only by the apply mark. */
    private var mode = 0
    private var storedMode = 0
    /** The stored selection as this screen lists it, known once the list has loaded. */
    private var storedSelection: String? = null
    /** An unsaved selection carried over a recreation of the screen. */
    private var restoredSelection: String? = null

    /** Selected listed apps, then the selected rows the list only knows by name. */
    private fun selectionText(): String {
        val listed = apps.filter { !it.byName && isProxiedApp(it) }.map { it.packageName }
        return (listed + extraSelected).distinct().joinToString("\n")
    }

    private fun dirty() = mode != storedMode || storedSelection.let { it != null && it != selectionText() }

    private fun selectionChanged() = invalidateOptionsMenu()

    private fun save() {
        DataStore.proxyApps = mode != 0
        if (mode != 0) DataStore.bypass = mode == 2
        DataStore.individual = selectionText()
        finish()
    }

    private fun sorted(list: List<ProxiedApp>) =
        list.sortedWith(compareBy({ !isProxiedApp(it) }, { it.name.toString() }))

    private fun refilter() = appsAdapter.filter.filter(binding.search.text?.toString() ?: "")

    @UiThread
    private fun loadApps() {
        loader?.cancel()
        loader = lifecycleScope.launch {
            loading.crossFadeFrom(binding.list)
            val adapter = binding.list.adapter as AppsAdapter
            withContext(Dispatchers.IO) { adapter.reload() }
            if (storedSelection == null) storedSelection = selectionText()
            invalidateOptionsMenu()
            refilter()
            if (apps.isEmpty()) {
                binding.list.visibility = View.GONE
                binding.appPlaceholder.root.crossFadeFrom(loading)
            } else {
                binding.list.crossFadeFrom(loading)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.setBackgroundColor(MobilePalette.background)
        binding.toolbar.setBackgroundColor(MobilePalette.toolbar)
        binding.toolbar.setTitleTextColor(MobilePalette.text)

        binding.appPlaceholder.openSettings.setOnClickListener {
            val intent =
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Logs.w(e)
            }
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setTitle(R.string.proxied_apps)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        storedMode = if (!DataStore.proxyApps) 0 else if (DataStore.bypass) 2 else 1
        mode = savedInstanceState?.getInt("mode", storedMode) ?: storedMode
        restoredSelection = savedInstanceState?.getString("selection")
        storedSelection = savedInstanceState?.getString("storedSelection")
        val modeIds = listOf(R.id.appProxyModeDisable, R.id.appProxyModeOn, R.id.appProxyModeBypass)
        fun updateModeDescription() {
            binding.appModeSummary.setText(when (mode) {
                0 -> R.string.qmobile_apps_all_summary
                2 -> R.string.qmobile_apps_except_summary
                else -> R.string.qmobile_apps_only_summary
            })
        }
        binding.bypassGroup.check(modeIds[mode])
        updateModeDescription()
        binding.bypassGroup.setOnCheckedChangeListener { _, checkedId ->
            mode = modeIds.indexOf(checkedId).coerceAtLeast(0)
            updateModeDescription()
            invalidateOptionsMenu()
        }
        binding.appModeSummary.setTextColor(MobilePalette.muted)
        binding.appModeCard.setCardBackgroundColor(MobilePalette.surface)
        binding.appModeCard.strokeWidth = 0
        binding.appModeCard.radius = 20 * resources.displayMetrics.density
        listOf(binding.appProxyModeDisable, binding.appProxyModeOn, binding.appProxyModeBypass).forEach {
            it.setTextColor(MobilePalette.text)
            it.buttonTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(MobilePalette.accent(this), MobilePalette.muted))
        }


        binding.list.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        binding.list.itemAnimator = null
        binding.list.adapter = appsAdapter

        // the app bar pads the status bar and the sides itself (applyTopInset); the list pads the navigation bar and the sides
        binding.list.applyListInsets(ime = true, horizontal = true)

        binding.search.addTextChangedListener {
            appsAdapter.filter.filter(it?.toString() ?: "")
        }

        binding.showSystemApps.setTextColor(MobilePalette.text)
        MobilePalette.tintCursor(binding.search)
        binding.search.setOnFocusChangeListener { _, focused -> if (focused) binding.search.post { MobilePalette.tintCursor(binding.search) } }
        binding.showSystemApps.isChecked = sysApps
        binding.showSystemApps.setOnCheckedChangeListener { _, isChecked ->
            sysApps = isChecked
            refilter()
        }

        instance = this
        loadApps()
    }

    private var sysApps = true

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.per_app_proxy_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_apply)?.isVisible = dirty()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("mode", mode)
        if (selectionLoaded) outState.putString("selection", selectionText())
        storedSelection?.let { outState.putString("storedSelection", it) }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_apply -> {
                save()
                return true
            }

            R.id.action_invert_selections -> {
                runOnDefaultDispatcher {
                    val proxiedUidsOld = proxiedUids.clone()
                    for (app in apps) {
                        if (app.byName) continue
                        if (proxiedUidsOld.contains(app.uid)) {
                            proxiedUids.delete(app.uid)
                        } else {
                            proxiedUids[app.uid] = true
                        }
                    }
                    apps = sorted(apps)
                    onMainDispatcher {
                        selectionChanged()
                        refilter()
                    }
                }

                return true
            }

            R.id.action_clear_selections -> {
                proxiedUids.clear()
                extraSelected.clear()
                selectionChanged()
                loadApps()
                return true
            }

            R.id.action_add_package -> {
                PackageNameInput.show(this) { names -> addPackages(names) }
                return true
            }

            R.id.action_export_clipboard -> {
                // What the screen shows, saved or not: the bypass flag, then the packages.
                val success = SagerNet.trySetPrimaryClip("${mode == 2}\n${selectionText()}")
                Snackbar.make(
                    binding.list,
                    if (success) R.string.action_export_msg else R.string.action_export_err,
                    Snackbar.LENGTH_LONG
                ).show()
                return true
            }

            R.id.action_import_clipboard -> {
                val proxiedAppString =
                    SagerNet.clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (!proxiedAppString.isNullOrBlank()) {
                    // The export's first line is the bypass flag; a bare package list keeps the chosen mode.
                    val flag = proxiedAppString.substringBefore('\n').trim().toBooleanStrictOrNull()
                    if (flag != null) binding.bypassGroup.check(if (flag) R.id.appProxyModeBypass else R.id.appProxyModeOn)
                    initProxiedUids(PackageCache.snapshot(), if (flag != null) proxiedAppString.substringAfter('\n', "") else proxiedAppString)
                    Snackbar.make(binding.list, R.string.action_import_msg, Snackbar.LENGTH_LONG).show()
                    selectionChanged()
                    loadApps()
                    return true
                }
                Snackbar.make(binding.list, R.string.action_import_err, Snackbar.LENGTH_LONG).show()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    /** Listed apps are selected by uid; any other name becomes a row of its own. */
    private fun addPackages(names: List<String>) {
        val listed = cachedApps()
        for (name in names) {
            val uid = listed[name]?.applicationInfo?.uid
            if (uid != null) proxiedUids[uid] = true else extraSelected.add(name)
        }
        selectionChanged()
        loadApps()
    }

    private fun selectProxyApp() {
        confirmAction(
            getString(R.string.confirm_auto_select_apps),
            getString(R.string.auto_select_proxy_apps_message),
            R.string.confirm_replace,
        ) {
            try {
                val needProxyAppsList = getAutoProxyApps("")
                val bypass = mode == 2
                proxiedUids.clear()
                for (app in cachedApps()) {
                    val needProxy =
                        needProxyAppsList.contains(app.key) || (app.value.applicationInfo?.uid
                            ?: 0) == 1000
                    if (needProxy) {
                        if (!bypass) {
                            app.value.applicationInfo?.apply {
                                proxiedUids[uid] = true
                            }
                        }
                    } else {
                        if (bypass) {
                            app.value.applicationInfo?.apply {
                                proxiedUids[uid] = true
                            }
                        }
                    }
                }
                selectionChanged()
                apps = sorted(apps)
                refilter()
            } catch (e: Exception) {
                Logs.e(e)
            }
        }
    }

    private fun getAutoProxyApps(content: String): List<String> {
        var list = listOf<String>()
        try {
            val proxyApps = if (TextUtils.isEmpty(content)) {
                NGUtil.readTextFromAssets(app, "proxy_packagename.txt")
            } else {
                content
            }
            if (!TextUtils.isEmpty(proxyApps)) {
                list = proxyApps.split("\n")
            }
        } catch (_: Exception) {
        }
        return list
    }

    override fun supportNavigateUpTo(upIntent: Intent) =
        super.supportNavigateUpTo(upIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))

    override fun onKeyUp(keyCode: Int, event: KeyEvent?) = if (keyCode == KeyEvent.KEYCODE_MENU) {
        if (binding.toolbar.isOverflowMenuShowing) binding.toolbar.hideOverflowMenu() else binding.toolbar.showOverflowMenu()
    } else super.onKeyUp(keyCode, event)

    override fun onDestroy() {
        instance = null
        loader?.cancel()
        super.onDestroy()
    }
}
