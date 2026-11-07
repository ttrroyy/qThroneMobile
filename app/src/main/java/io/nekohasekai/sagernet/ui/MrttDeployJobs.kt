package app.rawline.mobile.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
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

/** The circle of the MRTT deploy list: seen only while installs run, its window shows each server's step with a cross, and «Отменить все». */
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
                    addView(MobileProgress.bar(context).apply { progress = state.percent },LinearLayout.LayoutParams(-1,ui.dp(6)).apply { topMargin = ui.dp(6) })
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
    /** At the right of the toolbar, a turning circle while installs run; a tap opens their window. Gone with [owner]. */
    fun attach(activity: androidx.fragment.app.FragmentActivity,toolbar: Toolbar,owner: LifecycleOwner) {
        val button = ProgressBar(activity).apply {
            contentDescription = "Установка серверов"
            indeterminateTintList = android.content.res.ColorStateList.valueOf(MobilePalette.text)
            visibility = View.GONE; isClickable = true; isFocusable = true
            setOnClickListener { show(activity,owner) }
        }
        val size = (28*activity.resources.displayMetrics.density).toInt()
        toolbar.addView(button,Toolbar.LayoutParams(size,size,Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = size/2 })
        owner.lifecycleScope.launch {
            try {
                owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    MrttDeploy.states.collect { button.visibility = if(running(it).isNotEmpty()) View.VISIBLE else View.GONE }
                }
            } finally { toolbar.removeView(button) }
        }
    }
}
