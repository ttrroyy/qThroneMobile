package app.rawline.mobile.appwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.text.format.Formatter
import android.view.View
import android.widget.RemoteViews
import androidx.preference.PreferenceDataStore
import app.rawline.mobile.Key
import app.rawline.mobile.utils.ShortcutAuthorization
import app.rawline.mobile.QuickToggleShortcut
import app.rawline.mobile.R
import app.rawline.mobile.bg.BaseService
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.database.GroupRepo
import app.rawline.mobile.database.ProfileOrder
import app.rawline.mobile.database.SagerDatabase
import app.rawline.mobile.database.preference.OnPreferenceDataStoreChangeListener
import app.rawline.mobile.ktx.Logs
import app.rawline.mobile.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Home-screen widgets. Only :bg knows the service state, so every render happens there from that process's state;
 * the main process can only ask the providers to render ([requestUpdate]).
 */
object Widgets {

    const val ACTION_NEXT = "app.rawline.mobile.appwidget.NEXT"
    const val ACTION_PREVIOUS = "app.rawline.mobile.appwidget.PREVIOUS"

    private const val SPEED_INTERVAL_MS = 3000L
    private const val SMALL_WIDTH_DP = 100

    private val lock = Mutex()

    @Volatile
    private var statusIds: IntArray? = null
    private var lastSpeedPush = 0L
    private var lastGroupName: String? = null

    private class Snapshot(
        val state: BaseService.State,
        val profileName: String?,
        val groupName: String?,
        val canCycle: Boolean,
    )

    /** Main process: the providers in :bg re-render; nothing happens without widgets. */
    fun requestUpdate(context: Context) {
        try {
            val manager = AppWidgetManager.getInstance(context) ?: return
            for (provider in listOf(ToggleWidgetProvider::class.java, StatusWidgetProvider::class.java)) {
                val ids = manager.getAppWidgetIds(ComponentName(context, provider))
                if (ids.isEmpty()) continue
                // One provider is enough: a render covers every widget.
                context.sendBroadcast(
                    Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                        .setClass(context, provider)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                )
                return
            }
        } catch (e: Throwable) {
            Logs.w(e)
        }
    }

    /** Main process: a new selection shows on the widgets while the service is stopped. */
    fun watchSelection(context: Context) {
        val app = context.applicationContext
        DataStore.configurationStore.registerChangeListener(object : OnPreferenceDataStoreChangeListener {
            override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
                if (key == Key.PROFILE_ID) runOnDefaultDispatcher { requestUpdate(app) }
            }
        })
    }

    /** :bg: renders every widget from the current state. */
    fun push(context: Context) {
        val app = context.applicationContext
        runOnDefaultDispatcher { render(app) }
    }

    /** :bg receivers: renders before the broadcast is finished. */
    fun push(context: Context, pending: BroadcastReceiver.PendingResult) {
        val app = context.applicationContext
        runOnDefaultDispatcher {
            try {
                render(app)
            } finally {
                pending.finish()
            }
        }
    }

    fun forgetIds() {
        statusIds = null
    }

    /** At most every few seconds and only onto existing status widgets; the looper calls it with the screen on only. */
    suspend fun pushSpeed(context: Context, txRate: Long, rxRate: Long) {
        val ids = statusIds ?: return
        if (ids.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSpeedPush < SPEED_INTERVAL_MS) return
        lastSpeedPush = now
        lock.withLock {
            if (DataStore.serviceState != BaseService.State.Connected) return
            try {
                val speed = context.getString(
                    R.string.traffic,
                    context.getString(R.string.speed, Formatter.formatFileSize(context, txRate)),
                    context.getString(R.string.speed, Formatter.formatFileSize(context, rxRate)),
                )
                val views = RemoteViews(context.packageName, R.layout.widget_status)
                views.setTextViewText(R.id.widget_subtitle, subtitle(lastGroupName, speed))
                AppWidgetManager.getInstance(context)?.partiallyUpdateAppWidget(ids, views)
            } catch (e: Throwable) {
                Logs.w(e)
            }
        }
    }

    suspend fun render(context: Context) = lock.withLock {
        try {
            val manager = AppWidgetManager.getInstance(context) ?: return@withLock
            val toggle = manager.getAppWidgetIds(ComponentName(context, ToggleWidgetProvider::class.java))
            val status = manager.getAppWidgetIds(ComponentName(context, StatusWidgetProvider::class.java))
            statusIds = status
            if (toggle.isEmpty() && status.isEmpty()) return@withLock
            val snapshot = snapshot()
            for (id in toggle) {
                val width = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                manager.updateAppWidget(id, toggleViews(context, snapshot, width < SMALL_WIDTH_DP))
            }
            if (status.isNotEmpty()) {
                lastGroupName = snapshot.groupName
                manager.updateAppWidget(status, statusViews(context, snapshot))
            }
        } catch (e: Throwable) {
            Logs.w(e)
        }
    }

    private fun snapshot(): Snapshot {
        val state = DataStore.serviceState.takeIf { it != BaseService.State.Idle } ?: BaseService.State.Stopped
        val profile = DataStore.baseService?.data?.proxy?.profile
            ?: SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
        return Snapshot(
            state = state,
            profileName = profile?.displayName(),
            groupName = profile?.let { GroupRepo.get(it.groupId)?.displayName() },
            canCycle = profile != null && ProfileOrder.canCycle(profile.id),
        )
    }

    private fun toggleViews(context: Context, s: Snapshot, small: Boolean): RemoteViews {
        val views = RemoteViews(
            context.packageName, if (small) R.layout.widget_toggle_small else R.layout.widget_toggle
        )
        views.setImageViewResource(R.id.widget_icon, icon(s.state))
        views.setTextViewText(R.id.widget_state, stateText(context, s.state))
        if (!small) views.setTextViewText(R.id.widget_title, s.profileName ?: context.getString(R.string.widget_no_profile))
        views.setOnClickPendingIntent(android.R.id.background, togglePendingIntent(context))
        return views
    }

    private fun statusViews(context: Context, s: Snapshot): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_status)
        views.setImageViewResource(R.id.widget_icon, icon(s.state))
        views.setTextViewText(R.id.widget_title, s.profileName ?: context.getString(R.string.widget_no_profile))
        views.setTextViewText(R.id.widget_subtitle, subtitle(s.groupName, stateText(context, s.state)))
        val cycle = if (s.canCycle) View.VISIBLE else View.INVISIBLE
        views.setViewVisibility(R.id.widget_previous, cycle)
        views.setViewVisibility(R.id.widget_next, cycle)
        views.setOnClickPendingIntent(R.id.widget_toggle, togglePendingIntent(context))
        views.setOnClickPendingIntent(R.id.widget_previous, actionPendingIntent(context, ACTION_PREVIOUS, 1))
        views.setOnClickPendingIntent(R.id.widget_next, actionPendingIntent(context, ACTION_NEXT, 2))
        return views
    }

    private fun subtitle(group: String?, text: String) = if (group.isNullOrBlank()) text else "$group · $text"

    private fun icon(state: BaseService.State) = when (state) {
        BaseService.State.Connected -> R.drawable.ic_widget_on
        BaseService.State.Connecting, BaseService.State.Stopping -> R.drawable.ic_widget_busy
        else -> R.drawable.ic_widget_off
    }

    private fun stateText(context: Context, state: BaseService.State) = context.getString(
        when (state) {
            BaseService.State.Connected -> R.string.widget_connected
            BaseService.State.Connecting -> R.string.connecting
            BaseService.State.Stopping -> R.string.stopping
            else -> R.string.widget_stopped
        }
    )

    // An activity start is allowed from a widget tap and binds, starts (asking for VPN consent) or stops.
    private fun togglePendingIntent(context: Context) = PendingIntent.getActivity(
        context, 0,
        Intent(context, QuickToggleShortcut::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .let { ShortcutAuthorization.sign(context, it) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun actionPendingIntent(context: Context, action: String, requestCode: Int) = PendingIntent.getBroadcast(
        context, requestCode,
        Intent(context, WidgetActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
