package app.rawline.mobile.ui.profile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.rawline.mobile.ui.MobileAlertDialogBuilder as MobileAlertDialogBuilder
import app.rawline.mobile.R
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.database.ProfileManager
import app.rawline.mobile.database.ProxyEntity
import app.rawline.mobile.databinding.LayoutAddEntityBinding
import app.rawline.mobile.databinding.LayoutProfileBinding
import app.rawline.mobile.ktx.*
import app.rawline.mobile.outbound.types.Chain
import app.rawline.mobile.ui.ProfileSelectActivity
import app.rawline.mobile.widget.applyInsetPadding
import app.rawline.mobile.widget.applyListInsets
import moe.matsuri.nb4a.Protocols.getProtocolColor
import moe.matsuri.nb4a.proxy.PreferenceBindingManager

class ChainSettingsActivity : ProfileSettingsActivity<Chain>(R.layout.layout_chain_settings) {

    override fun createEntity() = Chain()
    override val supportsRawJson = false

    private val pbm = PreferenceBindingManager().apply { text("name") }

    private companion object {
        const val KEY_CHAIN_IDS = "chainProxyIds"
    }

    val proxyList = ArrayList<ProxyEntity>()

    override fun Chain.init() {
        pbm.writeToCacheAll(this)
        DataStore.profileCacheStore.putString(KEY_CHAIN_IDS, list.joinToString(","))
    }

    override fun Chain.serialize() {
        pbm.fromCacheAll(this)
        list = (DataStore.profileCacheStore.getString(KEY_CHAIN_IDS) ?: "").split(",")
            .mapNotNullTo(ArrayList()) { it.toLongOrNull() }
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.name_preferences)
        preferenceScreen.addPreference(androidx.preference.Preference(requireContext()).apply {
            summary="Сверху — первый сервер подключения, снизу — выход в интернет. После сохранения выберите профиль цепочки на главном экране."
            isSelectable=false
        })
        pbm.setPreferenceFragment(this)
    }

    lateinit var configurationList: RecyclerView
    lateinit var configurationAdapter: ProxiesAdapter
    lateinit var layoutManager: LinearLayoutManager

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar!!.setTitle(R.string.chain_settings)
        configurationList = findViewById(R.id.configuration_list)
        configurationList.applyListInsets()
        layoutManager = FixedLinearLayoutManager(configurationList)
        configurationList.layoutManager = layoutManager
        configurationAdapter = ProxiesAdapter()
        configurationList.adapter = configurationAdapter

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
        ) {
            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is ProfileHolder) {
                super.getSwipeDirs(recyclerView, viewHolder)
            } else 0

            override fun getDragDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is ProfileHolder) {
                super.getDragDirs(recyclerView, viewHolder)
            } else 0

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                return if (target !is ProfileHolder) false else {
                    configurationAdapter.move(
                        viewHolder.bindingAdapterPosition, target.bindingAdapterPosition
                    )
                    true
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                configurationAdapter.remove(viewHolder.bindingAdapterPosition)
            }

        }).attachToRecyclerView(configurationList)
    }

    override fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        // the profile list below takes the bottom inset
        listView.applyInsetPadding(horizontal = true)
        view.rootView.findViewById<RecyclerView>(R.id.recycler_view).apply {
            (layoutParams ?: LinearLayout.LayoutParams(-1, -2)).apply {
                height = -2
                layoutParams = this
            }
        }

        runOnDefaultDispatcher {
            configurationAdapter.reload()
        }
    }

    inner class ProxiesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        suspend fun reload() {
            val idList = (DataStore.profileCacheStore.getString(KEY_CHAIN_IDS) ?: "").split(",")
                .mapNotNull { it.takeIf { it.isNotBlank() }?.toLongOrNull() }
            val loaded=ArrayList<ProxyEntity>()
            if (idList.isNotEmpty()) {
                val profiles = ProfileManager.getProfiles(idList).map { it.id to it }.toMap()
                for (id in idList) {
                    loaded.add(profiles[id] ?: continue)
                }
            }
            onMainDispatcher {
                proxyList.clear();proxyList.addAll(loaded)
                notifyDataSetChanged()
            }
        }

        fun move(from: Int, to: Int) {
            if(from !in 1..proxyList.size || to !in 1..proxyList.size) return
            proxyList.add(to-1,proxyList.removeAt(from-1))
            cacheChain()
            notifyItemMoved(from, to)
        }

        fun remove(index: Int) {
            if(index !in 1..proxyList.size) return
            proxyList.removeAt(index - 1)
            cacheChain()
            notifyItemRemoved(index)
        }

        override fun getItemId(position: Int): Long {
            return if (position == 0) 0 else proxyList[position - 1].id
        }

        override fun getItemViewType(position: Int): Int {
            return if (position == 0) 0 else 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                AddHolder(LayoutAddEntityBinding.inflate(layoutInflater, parent, false))
            } else {
                ProfileHolder(LayoutProfileBinding.inflate(layoutInflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is AddHolder) {
                holder.bind()
            } else if (holder is ProfileHolder) {
                holder.bind(proxyList[position - 1])
            }
        }

        override fun getItemCount(): Int {
            return proxyList.size + 1
        }

    }

    private fun cacheChain() {
        DataStore.profileCacheStore.putString(KEY_CHAIN_IDS,proxyList.joinToString(",") { it.id.toString() })
    }

    fun testProfileAllowed(profile: ProxyEntity): Boolean {
        if (profile.id == DataStore.editingId) return false

        for (entity in proxyList) {
            if (testProfileContains(entity, profile)) return false
        }

        return true
    }

    fun testProfileContains(profile: ProxyEntity, anotherProfile: ProxyEntity): Boolean {
        if (!profile.isChain() || !anotherProfile.isChain()) return false
        if (profile.id == anotherProfile.id) return true
        val proxies = profile.requireChain().list
        if (proxies.contains(anotherProfile.id)) return true
        if (proxies.isNotEmpty()) {
            for (entity in ProfileManager.getProfiles(proxies)) {
                if (testProfileContains(entity, anotherProfile)) {
                    return true
                }
            }
        }
        return false
    }

    var replacing = 0

    val selectProfileForAdd =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { (resultCode, data) ->
            if (resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {

                val profile = ProfileManager.getProfile(
                    data?.getLongExtra(
                        ProfileSelectActivity.EXTRA_PROFILE_ID, 0
                    ) ?: 0L
                ) ?: return@runOnDefaultDispatcher

                if (!testProfileAllowed(profile)) {
                    onMainDispatcher {
                        MobileAlertDialogBuilder(this@ChainSettingsActivity).setTitle(R.string.circular_reference)
                            .setMessage(R.string.circular_reference_sum)
                            .setPositiveButton(android.R.string.ok, null).show()
                    }
                } else {
                    configurationList.post {
                        if (replacing in 1..proxyList.size) {
                            proxyList[replacing - 1] = profile
                            configurationAdapter.notifyItemChanged(replacing)
                        } else {
                            proxyList.add(profile)
                            configurationAdapter.notifyItemInserted(proxyList.size)
                        }
                        cacheChain()
                    }
                }
            }
        }

    inner class AddHolder(val binding: LayoutAddEntityBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            binding.root.setOnClickListener {
                replacing = 0
                selectProfileForAdd.launch(
                    Intent(
                        this@ChainSettingsActivity, ProfileSelectActivity::class.java
                    )
                )
            }
        }
    }

    inner class ProfileHolder(binding: LayoutProfileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        val profileName = binding.profileName
        val profileType = binding.profileType
        val trafficText: TextView = binding.trafficText
        val editButton = binding.edit
        val shareLayout = binding.share

        fun bind(proxyEntity: ProxyEntity) {

            profileName.text = proxyEntity.displayName()
            profileType.text = proxyEntity.displayType()
            profileType.setTextColor(getProtocolColor(proxyEntity.type))

            val rx = proxyEntity.rx
            val tx = proxyEntity.tx

            val showTraffic = rx + tx != 0L
            trafficText.isVisible = showTraffic
            if (showTraffic) {
                trafficText.text = itemView.context.getString(
                    R.string.traffic,
                    Formatter.formatFileSize(itemView.context, tx),
                    Formatter.formatFileSize(itemView.context, rx)
                )
            }

            editButton.setOnClickListener {
                replacing = bindingAdapterPosition
                selectProfileForAdd.launch(Intent(
                    this@ChainSettingsActivity, ProfileSelectActivity::class.java
                ).apply {
                    putExtra(ProfileSelectActivity.EXTRA_SELECTED, proxyEntity)
                })
            }

            shareLayout.isVisible = false
        }

    }

}
