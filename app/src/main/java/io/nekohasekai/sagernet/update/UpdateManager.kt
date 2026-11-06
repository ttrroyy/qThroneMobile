package app.rawline.mobile.update

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import app.rawline.mobile.BuildConfig
import app.rawline.mobile.R
import app.rawline.mobile.bg.ProxyService
import app.rawline.mobile.bg.VpnService
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.ktx.Logs
import app.rawline.mobile.ktx.USER_AGENT
import app.rawline.mobile.ktx.app
import app.rawline.mobile.ktx.readableMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Main-process updater state shared by [UpdateActivity] and the notifications: one check or download at a time
 * (the desktop's mu_download_update), download into noBackupFilesDir/update/, verify, install.
 */
object UpdateManager {

    sealed class State {
        object Idle : State()
        object Checking : State()
        object UpToDate : State()
        class Available(val offer: UpdateChecker.Offer) : State()
        class Downloading(val offer: UpdateChecker.Offer, val done: Long, val total: Long) : State()
        class Verifying(val offer: UpdateChecker.Offer) : State()
        class Installing(val offer: UpdateChecker.Offer) : State()
        class Failed(val offer: UpdateChecker.Offer?, val message: String) : State()

        // Installing only waits for the installer's answer, which may never come; it can be dismissed.
        val busy get() = this is Checking || this is Downloading || this is Verifying
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> get() = _state

    @Volatile
    private var job: Job? = null

    @Volatile
    private var call: Call? = null

    /** Set by [UpdateActivity] while it shows the state, so failures need no notification. */
    @Volatile
    var uiVisible = false

    private fun updateDir(context: Context) = File(context.noBackupFilesDir, "update")

    /** Main-process start: leftovers of a finished or interrupted update. */
    fun onAppStart(context: Context) {
        scope.launch { updateDir(context).deleteRecursively() }
        if (BuildConfig.IN_APP_UPDATER) UpdateDiscovery.onAppStart(context)
    }

    @Synchronized
    fun check() {
        if (_state.value.busy) return
        _state.value = State.Checking
        job = scope.launch {
            val result = UpdateDiscovery.check()
            ensureActive()
            _state.value = when (result) {
                is UpdateChecker.Result.UpToDate -> State.UpToDate
                is UpdateChecker.Result.Available -> State.Available(result.offer)
                is UpdateChecker.Result.Failed -> State.Failed(null, result.message)
            }
        }
    }

    /** Opens a discovered offer immediately, while an active download keeps its own dialog state. */
    @Synchronized
    fun showOffer(offer: UpdateChecker.Offer): Boolean {
        if (_state.value.busy || _state.value is State.Installing) return false
        _state.value = State.Available(offer)
        return true
    }

    @Synchronized
    fun download(offer: UpdateChecker.Offer) {
        if (_state.value.busy || !offer.installable) return
        UpdateNotifications.cancelAvailable(app)
        _state.value = State.Downloading(offer, 0, offer.expected!!.size)
        job = scope.launch {
            try {
                val file = fetch(offer)
                _state.value = State.Verifying(offer)
                UpdateVerifier.verify(app, file, offer)
                _state.value = State.Installing(offer)
                if (isServiceRunning()) DataStore.resumeAfterUpdate = DataStore.selectedProxy
                UpdateNotifications.cancelProgress(app)
                UpdateInstaller.install(app, file)
            } catch (e: Exception) {
                // cancel() aborts the HTTP call too, which surfaces as an IOException
                if (e is CancellationException || !isActive) {
                    UpdateNotifications.cancelProgress(app)
                    _state.value = State.Available(offer)
                } else {
                    Logs.w("Update failed", e)
                    fail(offer, if (e is UpdateVerifier.Rejected) e.message!! else e.readableMessage)
                }
            } finally {
                call = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
        call?.cancel()
        if (_state.value is State.Checking) _state.value = State.Idle
    }

    /** Leaves a finished state (offer shown, up to date, failure) once the UI is done with it. */
    fun dismiss() {
        if (!_state.value.busy) _state.value = State.Idle
    }

    private fun fail(offer: UpdateChecker.Offer?, message: String) {
        DataStore.resumeAfterUpdate = 0
        _state.value = State.Failed(offer, message)
        if (uiVisible) UpdateNotifications.cancelProgress(app) else UpdateNotifications.failed(app, message)
    }

    private suspend fun fetch(offer: UpdateChecker.Offer): File {
        val expected = offer.expected!!
        val dir = updateDir(app).apply { mkdirs() }
        val target = File(dir, expected.name)
        // A verified download is reused when the user retries after cancelling the system dialog.
        if (target.length() == expected.size && UpdateVerifier.sha256(target) == expected.sha256) return target
        val part = File(dir, expected.name + ".part")
        part.delete()
        val request = Request.Builder().url(offer.apk!!.url).header("User-Agent", USER_AGENT).build()
        val httpCall = UpdateChecker.httpClient(timeoutSeconds = 60).newCall(request)
        call = httpCall
        httpCall.execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val input = response.body?.byteStream() ?: throw IOException("empty response")
            part.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                var reported = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    if (done > expected.size) throw IOException(app.getString(R.string.update_error_size))
                    val now = System.currentTimeMillis()
                    if (now - reported >= 500 || done == expected.size) {
                        reported = now
                        _state.value = State.Downloading(offer, done, expected.size)
                        UpdateNotifications.progress(app, offer, done, expected.size)
                    }
                }
            }
        }
        if (!part.renameTo(target)) throw IOException("cannot rename ${part.name}")
        return target
    }

    /** [UpdateReceiver]: the PackageInstaller session result. */
    fun onInstallStatus(context: Context, intent: Intent) {
        val offer = (_state.value as? State.Installing)?.offer
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                if (isForeground()) {
                    try {
                        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        return
                    } catch (e: Exception) {
                        Logs.w("Update: cannot open the installer", e)
                    }
                }
                UpdateNotifications.installPrompt(context, confirm)
            }

            PackageInstaller.STATUS_SUCCESS -> _state.value = State.Idle

            PackageInstaller.STATUS_FAILURE_ABORTED -> fail(offer, context.getString(R.string.update_install_cancelled))

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                fail(offer, context.getString(R.string.update_install_failed, message))
            }
        }
    }

    private fun isForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    // The main process may not be bound to the service (update started from a notification), so ask the system.
    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val services = setOf(VpnService::class.java.name, ProxyService::class.java.name)
        return runCatching {
            app.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
                .any { it.service.className in services && it.started }
        }.getOrDefault(false) || DataStore.serviceState.canStop
    }
}
