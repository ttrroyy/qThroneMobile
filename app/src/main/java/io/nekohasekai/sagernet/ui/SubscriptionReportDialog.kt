package app.qthrone.mobile.ui

import android.content.Context
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.qthrone.mobile.ui.MobileAlertDialogBuilder as MobileAlertDialogBuilder
import app.qthrone.mobile.R
import app.qthrone.mobile.SagerNet
import app.qthrone.mobile.database.GroupRepo
import app.qthrone.mobile.group.SubscriptionClient
import app.qthrone.mobile.ktx.dp2px
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The change window of a manual subscription update (GroupUpdater's MessageBoxScrollable "Change of <group>"),
 * and the observer that shows the queue's reports while the main window is started.
 */
object SubscriptionReportDialog {

    fun show(context: Context, title: CharSequence, text: String) {
        val body = text.trim().ifEmpty { context.getString(R.string.grp_nothing) }
        val textView = TextView(context).apply {
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2)
            setText(body)
            setTextIsSelectable(true)
            setPadding(dp2px(24), dp2px(8), dp2px(24), dp2px(8))
        }
        val scroll = NestedScrollView(context).apply { addView(textView) }
        MobileAlertDialogBuilder(context)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.action_copy) { _, _ -> SagerNet.trySetPrimaryClip(body) }
            .show()
    }

    /**
     * While [activity] is started: popup reports open the change window; errors of the requests made from the UI and
     * untitled status lines (the result of "Add profiles to this group") show a snackbar. Titled reports without a
     * popup (automatic refreshes, URL-test follow-ups) are only logged, as on the desktop.
     */
    fun observe(activity: MainActivity) {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                SubscriptionClient.reports.collect { report -> handle(activity, report) }
            }
        }
    }

    @Volatile var refreshEpoch = 0L
        private set

    private suspend fun handle(activity: MainActivity, report: SubscriptionClient.Report) {
        val group = withContext(Dispatchers.IO) { GroupRepo.get(report.gid) }
        if (!report.error && group != null && report.title == activity.getString(R.string.subs_change_of, group.name)) {
            val ids = withContext(Dispatchers.IO) { app.qthrone.mobile.database.SagerDatabase.proxyDao.getByGroup(group.id).map { it.id } }
            app.qthrone.mobile.ui.test.TestSessionClient.clearResults(ids)
            refreshEpoch++
            MobileTopNotice.show(activity, activity.getString(R.string.qmobile_subscription_updated, group.displayName()))
        }

        when {
            report.error -> {
                val message = report.text.ifBlank { report.title }
                val groupName = withContext(Dispatchers.IO) { GroupRepo.get(report.gid)?.displayName() }
                MobileTopNotice.show(activity, if (groupName != null) activity.getString(R.string.qmobile_subscription_failed, groupName, message) else message, error = true)
            }

            report.popup -> {
                val title = report.title.ifBlank {
                    val groupName = withContext(Dispatchers.IO) { GroupRepo.get(report.gid)?.displayName() }
                    activity.getString(R.string.grp_change_of, groupName.orEmpty())
                }
                show(activity, title, report.text)
            }

            report.title.isEmpty() && report.text.isNotBlank() -> activity.snackbar(report.text).show()
        }
    }
}
