package app.qthrone.mobile.update

import android.content.Context
import androidx.preference.PreferenceDataStore
import app.qthrone.mobile.BuildConfig
import app.qthrone.mobile.Key
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.database.preference.OnPreferenceDataStoreChangeListener
import app.qthrone.mobile.ktx.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Quiet automatic checks feed the updates menu; a manual check uses the same serialized request and cache. */
object UpdateDiscovery : OnPreferenceDataStoreChangeListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _available = MutableStateFlow<UpdateChecker.Offer?>(null)
    val available: StateFlow<UpdateChecker.Offer?> get() = _available
    private var snapshot: UpdateOfferCache.Snapshot? = null
    private var initialized = false

    fun onAppStart(context: Context) {
        DataStore.configurationStore.registerChangeListener(this)
        // Versions before 1.9 used a daily notification worker, including work persisted by Android.
        UpdateScheduler.cancelLegacyCheck()
        UpdateNotifications.cancelAvailable(context)
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.UPDATE_CHECK_AUTO && key != "allow_beta_update" && key != Key.UPDATE_SKIPPED_VERSION_CODE) return
        scope.launch {
            mutex.withLock {
                if (key == "allow_beta_update") { snapshot = null; initialized = false }
                loadCache()
                publishOffer()
            }
        }
    }

    /** Called when home becomes visible and while it remains visible; it never opens a dialog or notification. */
    fun refresh() {
        if (!BuildConfig.IN_APP_UPDATER) return
        scope.launch {
            mutex.withLock {
                loadCache()
                publishOffer()
                if (!DataStore.updateCheckAuto) return@withLock
                val cached = snapshot
                if (UpdateDiscoveryPolicy.shouldCheck(System.currentTimeMillis(), cached?.lastAttempt ?: 0L, cached?.lastSucceeded ?: false)) {
                    performCheck()
                }
            }
        }
    }

    /** The update dialog explicitly requests a fresh result, even while automatic checks are disabled. */
    suspend fun check(): UpdateChecker.Result = mutex.withLock {
        loadCache()
        performCheck()
    }

    private fun loadCache() {
        if (initialized && snapshot?.allowBeta == DataStore.allowBetaUpdate) return
        initialized = true
        snapshot = runCatching { UpdateOfferCache.decode(cacheFile().readText(), BuildConfig.VERSION_CODE.toLong(), DataStore.allowBetaUpdate) }.getOrNull()
    }

    private fun performCheck(): UpdateChecker.Result {
        val channel = DataStore.allowBetaUpdate
        val result = UpdateChecker.check(app)
        // A channel can change while a request is in flight. Its result must not become the new channel's cache.
        if (channel != DataStore.allowBetaUpdate) { snapshot = null; initialized = false; _available.value = null; return result }
        snapshot = UpdateOfferCache.Snapshot(
            BuildConfig.VERSION_CODE.toLong(), channel, System.currentTimeMillis(), result !is UpdateChecker.Result.Failed,
            when (result) {
                is UpdateChecker.Result.Available -> result.offer
                UpdateChecker.Result.UpToDate -> null
                is UpdateChecker.Result.Failed -> snapshot?.offer
            },
        )
        runCatching {
            val file = cacheFile()
            file.parentFile?.mkdirs()
            val temp = File(file.path + ".tmp")
            temp.writeText(UpdateOfferCache.encode(snapshot!!))
            if (!temp.renameTo(file)) temp.delete()
        }
        publishOffer()
        return result
    }

    private fun publishOffer() {
        _available.value = snapshot?.offer?.takeIf {
            BuildConfig.IN_APP_UPDATER && DataStore.updateCheckAuto &&
                UpdateDiscoveryPolicy.acceptsOffer(
                    it.versionCode, it.versionName, it.release.prerelease, BuildConfig.VERSION_CODE.toLong(),
                    BuildConfig.VERSION_NAME, DataStore.allowBetaUpdate, DataStore.updateSkippedVersionCode,
                )
        }
    }

    private fun cacheFile() = File(app.cacheDir, "update/discovery.json")
}
