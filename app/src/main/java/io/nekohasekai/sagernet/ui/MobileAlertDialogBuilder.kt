package app.qthrone.mobile.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.ContextThemeWrapper
import app.qthrone.mobile.utils.Theme
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Use the same surface and shape for editors, confirmations and preference dialogs. */
class MobileAlertDialogBuilder(context: Context) : MaterialAlertDialogBuilder(
    ContextThemeWrapper(context, Theme.getTheme()).apply { Theme.apply(this) }
) {
    override fun create(): androidx.appcompat.app.AlertDialog = super.create().also { style(it) }

    companion object {
        fun style(dialog: androidx.appcompat.app.AlertDialog) {
            val density = dialog.context.resources.displayMetrics.density
            val surface = GradientDrawable().apply {
                setColor(MobilePalette.surface)
                cornerRadius = 12 * density
            }
            dialog.window?.setBackgroundDrawable(InsetDrawable(surface, (24 * density).toInt()))
            val colors=android.content.res.ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),
                intArrayOf(MobilePalette.muted,MobilePalette.accent(dialog.context)))
            for(which in listOf(-1,-2,-3)) dialog.getButton(which)?.setTextColor(colors)
            fun tintInputs(view: android.view.View) {
                if (view is android.widget.EditText) MobilePalette.tintCursor(view)
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount) tintInputs(view.getChildAt(index))
            }
            // Builder dialogs inflate their inputs during show(), after create() has styled the window.
            dialog.window?.decorView?.let { decor -> decor.post { tintInputs(decor) } }
        }
    }
}
