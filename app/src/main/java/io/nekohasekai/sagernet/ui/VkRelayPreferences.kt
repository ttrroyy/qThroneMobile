package app.rawline.mobile.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.webkit.CookieManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import app.rawline.mobile.R
import app.rawline.mobile.bg.carrier.VkRelayAccount
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.ktx.needReload
import kotlinx.coroutines.*
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object VkRelayPreferences {
    fun bind(fragment: PreferenceFragmentCompat, group: PreferenceGroup) {
        if (group.preferenceCount == 0) {
            group.addPreference(Preference(fragment.requireContext()).apply {
                key="vkRelayAccount"; title="Привязать аккаунт VK"; setIcon(R.drawable.ic_mobile_vk); isSelectable=false
            })
            group.addPreference(Preference(fragment.requireContext()).apply {
                key="vkRelayDefaultHashes"; title="Хеши новых профилей qWDTT, CSQTT и WDTT Plus"
                summary="Загрузка…"; isSingleLineTitle=false; setIcon(R.drawable.ic_mobile_hash); isSelectable=false
            })
            group.addPreference(Preference(fragment.requireContext()).apply {
                key="vkRelayAutoWarning"; summary="В режиме Авто повышенные риски бана аккаунта ВК, используйте на свой страх и риск."
                isSelectable=false; isIconSpaceReserved=false
            })
        }
        fragment.lifecycleScope.launch {
            val info=withContext(Dispatchers.IO) { VkRelayAccount.uiInfo() }
            if(fragment.isAdded && group.parent===fragment.preferenceScreen) bindLoaded(fragment,group,info)
        }
    }
    private fun bindLoaded(fragment: PreferenceFragmentCompat,group: PreferenceGroup,info: VkRelayAccount.UiInfo) {
        val context = fragment.requireContext()
        group.removeAll()
        val linked = info.linked
        val account = object : Preference(context) {
            override fun onBindViewHolder(holder: PreferenceViewHolder) {
                super.onBindViewHolder(holder)
                (holder.findViewById(android.R.id.summary) as? android.widget.TextView)?.apply {
                    isSingleLine=false;maxLines=Int.MAX_VALUE;ellipsize=null
                    setTextColor(MobilePalette.muted)
                }
                (holder.findViewById(R.id.vk_unlink) as? ImageButton)?.apply {
                    imageTintList=android.content.res.ColorStateList.valueOf(MobilePalette.accent(context))
                }?.setOnClickListener { button ->
                    button.isEnabled = false
                    fragment.lifecycleScope.launch {
                        val result = runCatching { VkRelayAccount.unlinkWithManualHashes() }
                        if(!fragment.isAdded) return@launch
                        if(result.isSuccess) {
                            val cookies = CookieManager.getInstance()
                            for(domain in listOf("https://vk.ru","https://vk.com","https://oauth.vk.ru","https://oauth.vk.com","https://login.vk.ru","https://login.vk.com","https://id.vk.ru")) {
                                cookies.getCookie(domain)?.split(';')?.forEach { cookie -> cookies.setCookie(domain,cookie.substringBefore('=').trim()+"=; Max-Age=0; Path=/") }
                            }
                            cookies.flush()
                            bind(fragment,group)
                            fragment.needReload()
                        } else {
                            button.isEnabled = true
                            Toast.makeText(context,"Не удалось отвязать аккаунт VK",Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }.apply {
            key = "vkRelayAccount"
            title = if(linked) "Аккаунт VK привязан" else "Привязать аккаунт VK"
            isSingleLineTitle = false
            setIcon(R.drawable.ic_mobile_vk)
            if(linked) {
                summary = info.displayName.takeIf { it.isNotBlank() }?.let { "ID: $it" }
                widgetLayoutResource = R.layout.vk_relay_unlink
                isSelectable = true
                setOnPreferenceClickListener {
                    fragment.startActivity(Intent(context,VkRelayActivity::class.java).putExtra("login",true)); true
                }
                if(info.needsRelink) {
                    title = "Подтвердить привязку VK"
                    summary = info.displayName.takeIf { it.isNotBlank() }?.let { "ID: $it" }
                    setOnPreferenceClickListener {
                        fragment.startActivity(Intent(context,VkRelayActivity::class.java).putExtra("login",true)); true
                    }
                }
            } else setOnPreferenceClickListener {
                fragment.startActivity(Intent(context,VkRelayActivity::class.java).putExtra("login",true)); true
            }
        }
        group.addPreference(account)
        val hashesPreference = SimpleMenuPreference(context).apply {
            key = "vkRelayDefaultHashes"
            optionEnabled = { index -> index != 1 || (info.linked && !info.needsRelink) }
            title = "Хеши новых профилей qWDTT, CSQTT и WDTT Plus"
            setIcon(R.drawable.ic_mobile_hash)
            isSingleLineTitle = false; isPersistent = false
            entries = arrayOf("Вручную","Авто"); entryValues = arrayOf("manual","auto")
            setDefaultValue("manual")
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setOnPreferenceChangeListener { _,next -> if(next == "auto" && (!info.linked || info.needsRelink)) false else { DataStore.configurationStore.putString(VkRelayAccount.DEFAULT_SOURCE,next as String); true } }
        }
        group.addPreference(hashesPreference)
        hashesPreference.value = DataStore.configurationStore.getString(VkRelayAccount.DEFAULT_SOURCE,"manual").takeIf { it == "auto" } ?: "manual"
        if(linked && !info.needsRelink) group.addPreference(Preference(context).apply {
            key="vkRelayCreateHashes"; title="Генерация хешей"; isPersistent=false; isSingleLineTitle=false
            summary=if(info.hashes==0) "Создать хеши звонков VK" else "Хешей: ${info.hashes}"
            setIcon(R.drawable.ic_mobile_hash); widgetLayoutResource=R.layout.qmobile_preference_arrow
            setOnPreferenceClickListener { fragment.startActivity(Intent(context,VkHashesActivity::class.java)); true }
        })
        group.addPreference(Preference(context).apply {
            key="vkRelayAutoWarning"
            summary="В режиме Авто повышенные риски бана аккаунта ВК, используйте на свой страх и риск."
            isSelectable=false;isPersistent=false;isIconSpaceReserved=false
        })
        if(linked) fragment.lifecycleScope.launch {
            val avatar = withContext(Dispatchers.IO) {
                runCatching {
                    val url = VkRelayAccount.avatarUrl()
                    if(!url.startsWith("https://")) return@runCatching null
                    val client = OkHttpClient.Builder().callTimeout(10,TimeUnit.SECONDS).build()
                    client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if(!response.isSuccessful) return@use null
                        val stream = response.body?.byteStream() ?: return@use null
                        val bytes = stream.readBytesBounded()
                        BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                    }
                }.getOrNull()
            }
            val latest=withContext(Dispatchers.IO) { VkRelayAccount.uiInfo() }
            if(fragment.isAdded && group.findPreference<Preference>("vkRelayAccount") === account && latest.linked) {
                if(!latest.needsRelink) account.summary = latest.displayName
                if(avatar != null) account.icon = RoundedBitmapDrawableFactory.create(context.resources,avatar).apply { isCircular = true }
            }
        }
    }
    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while(true) {
            val count = read(buffer)
            if(count < 0) break
            check(output.size()+count <= 1024*1024)
            output.write(buffer,0,count)
        }
        return output.toByteArray()
    }
}
