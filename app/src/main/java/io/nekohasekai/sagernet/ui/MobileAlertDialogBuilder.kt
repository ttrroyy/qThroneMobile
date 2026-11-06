package app.qthrone.mobile.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.ContextThemeWrapper
import androidx.core.view.updateLayoutParams
import app.qthrone.mobile.utils.Theme
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Use the same surface and shape for editors, confirmations and preference dialogs. */
class MobileAlertDialogBuilder(context: Context) : MaterialAlertDialogBuilder(
    ContextThemeWrapper(context, Theme.getTheme()).apply { Theme.apply(this) }
) {
    override fun create(): androidx.appcompat.app.AlertDialog = super.create().also { style(it) }

    companion object {
        /** A plain field becomes the dark well of the cards; a field in a TextInputLayout has its box already. */
        private fun well(input: android.widget.EditText, density: Float) {
            var parent = input.parent
            while (parent != null) {
                if ((parent as? com.google.android.material.textfield.TextInputLayout)?.editText === input) return
                parent = parent.parent
            }
            input.backgroundTintList = null
            input.background = GradientDrawable().apply {
                setColor(androidx.core.graphics.ColorUtils.blendARGB(MobilePalette.surface, android.graphics.Color.BLACK, .14f)); cornerRadius = 12 * density
            }
            input.setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            input.minimumHeight = (48 * density).toInt()
            // setView() puts a bare field straight in the dialog's frame, edge to edge.
            if ((input.parent as? android.view.View)?.id == androidx.appcompat.R.id.custom) input.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                marginStart = (24 * density).toInt(); marginEnd = (24 * density).toInt(); topMargin = (8 * density).toInt()
            }
        }

        fun style(dialog: androidx.appcompat.app.AlertDialog) {
            val density = dialog.context.resources.displayMetrics.density
            val surface = GradientDrawable().apply {
                setColor(MobilePalette.surface)
                cornerRadius = 12 * density
                setStroke(1, MobilePalette.outline)
            }
            dialog.window?.setBackgroundDrawable(InsetDrawable(surface, (24 * density).toInt()))
            val colors=android.content.res.ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),
                intArrayOf(MobilePalette.muted,MobilePalette.accent(dialog.context)))
            for(which in listOf(-1,-2,-3)) dialog.getButton(which)?.setTextColor(colors)
            fun tintInputs(view: android.view.View) {
                if (view is android.widget.EditText) {
                    MobilePalette.tintCursor(view)
                    well(view, density)
                }
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount) tintInputs(view.getChildAt(index))
            }
            // Builder dialogs inflate their inputs during show(), after create() has styled the window.
            dialog.window?.decorView?.let { decor -> decor.post { tintInputs(decor) } }
        }
    }
}
