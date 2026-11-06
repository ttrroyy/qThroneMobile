package app.rawline.mobile.ui

import androidx.preference.PreferenceDataStore
import app.rawline.mobile.Key
import app.rawline.mobile.R
import app.rawline.mobile.bg.autoselector.AutoSelectorClient
import app.rawline.mobile.bg.autoselector.AutoSelectorProfiles
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.database.ProfileManager
import app.rawline.mobile.database.preference.OnPreferenceDataStoreChangeListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

/**
 * The profiles screen's status line (the Data view summary, mainwindow_view.cpp:89-97): what the running auto-selector
 * does, or "stopped" while the selected profile is a selector and nothing runs. A tap opens [AutoSelectorStatusActivity].
 */
object AutoSelectorStatusLine {

    private const val TICK_MS = 30_000L

    /** Keeps [fragment]'s line current until the calling coroutine is cancelled (the view stops). */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun follow(fragment: ConfigurationFragment) {
        if (fragment.select) return
        val selection = MutableStateFlow(DataStore.selectedProxy)
        val listener = object : OnPreferenceDataStoreChangeListener {
            override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
                if (key == Key.PROFILE_ID) selection.value = DataStore.selectedProxy
            }
        }
        DataStore.configurationStore.registerChangeListener(listener)
        try {
            val selectedSelector = selection.mapLatest { id ->
                withContext(Dispatchers.IO) {
                    runCatching { ProfileManager.getProfile(id)?.takeIf { it.type == AutoSelectorProfiles.TYPE }?.id }
                        .getOrNull()
                }
            }
            val ticks = flow {
                while (true) {
                    emit(Unit)
                    delay(TICK_MS)
                }
            }
            combine(AutoSelectorClient.status, selectedSelector, ticks) { status, selectorId, _ ->
                val context = fragment.context ?: return@combine null
                val text = AutoSelectorTexts.summary(context, status)
                when {
                    text != null -> text to status.selectorId
                    selectorId != null && !DataStore.serviceState.started ->
                        context.getString(R.string.autosel_status_stopped) to selectorId

                    else -> null
                }
            }.collect { line ->
                if (line == null) {
                    fragment.setRuntimeStatus(null)
                } else {
                    val (text, selectorId) = line
                    fragment.setRuntimeStatus(text) {
                        fragment.context?.let { fragment.startActivity(AutoSelectorStatusActivity.intent(it, selectorId)) }
                    }
                }
            }
        } finally {
            DataStore.configurationStore.unregisterChangeListener(listener)
        }
    }
}
