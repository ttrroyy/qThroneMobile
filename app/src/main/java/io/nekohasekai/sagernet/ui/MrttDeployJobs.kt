package app.rawline.mobile.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.rawline.mobile.R
import app.rawline.mobile.deploy.DeployState
import app.rawline.mobile.deploy.MrttDeploy
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The button of the MRTT deploy window: seen only while installs run, its window shows each server's step with a cross, and «Отменить все». */
object MrttDeployJobs {
    private fun running(states: Map<String,DeployState>) = states.values.filter { it.running }
    private fun rows(context: Context,holder: LinearLayout,states: Map<String,DeployState>,titles: Map<String,String>) {
        val ui = ServerUi(context)
        holder.removeAllViews()
        running(states).forEach { state ->
            holder.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.column().apply {
                    addView(ui.text(titles[state.serverId].orEmpty(),15f,bold = true))
                    addView(ui.text(state.step,13f,MobilePalette.muted))
                    addView(ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = state.percent })
                },LinearLayout.LayoutParams(0,-2,1f))
                addView(ImageButton(context).apply {
                    setImageResource(R.drawable.ic_navigation_close)
                    imageTintList = android.content.res.ColorStateList.valueOf(MobilePalette.text)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    contentDescription = "Отменить установку"
                    setOnClickListener { MrttDeploy.cancel(state.serverId) }
                },LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)))
            },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = ui.dp(10) })
        }
    }
    private fun show(context: Context,owner: LifecycleOwner) {
        val ui = ServerUi(context)
        val holder = ui.column(16)
        val dialog: AlertDialog = MobileAlertDialogBuilder(context).setTitle("Установка серверов")
            .setView(ScrollView(context).apply { addView(holder) })
            .setPositiveButton(android.R.string.ok,null)
            .setNeutralButton("Отменить все") { _,_ -> MrttDeploy.cancelAll() }.create()
        dialog.show()
        val job = owner.lifecycleScope.launch {
            MrttDeploy.states.combine(MrttDeploy.titles) { states,titles -> states to titles }.collect { (states,titles) ->
                val active = running(states)
                // When the work ends, the button and this window go away by themselves.
                if(active.isEmpty()) { dialog.dismiss(); return@collect }
                rows(context,holder,states,titles)
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility = if(active.size>=2) View.VISIBLE else View.GONE
            }
        }
        dialog.setOnDismissListener { job.cancel() }
    }
    /** Left of the toolbar's own actions, as a turning indicator. */
    fun attach(activity: androidx.fragment.app.FragmentActivity,toolbar: Toolbar) {
        val button = ProgressBar(activity).apply {
            contentDescription = "Идёт установка: показать"
            visibility = View.GONE; isClickable = true; isFocusable = true
            setOnClickListener { show(activity,activity) }
        }
        val size = (32*activity.resources.displayMetrics.density).toInt()
        toolbar.addView(button,Toolbar.LayoutParams(size,size,Gravity.END or Gravity.CENTER_VERTICAL))
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                MrttDeploy.states.collect { button.visibility = if(running(it).isNotEmpty()) View.VISIBLE else View.GONE }
            }
        }
    }
    /** The same entry as a row, for the list inside the settings. */
    fun strip(context: Context,owner: LifecycleOwner): View {
        val ui = ServerUi(context)
        val row: TextView = ui.button("") { show(context,owner) }
        row.visibility = View.GONE
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                MrttDeploy.states.collect { states ->
                    val count = running(states).size
                    row.visibility = if(count>0) View.VISIBLE else View.GONE
                    row.text = "Идёт установка: $count"
                }
            }
        }
        return row
    }
}
