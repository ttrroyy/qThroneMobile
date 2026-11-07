package app.rawline.mobile.ui

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreferenceDialogFragmentCompat

/** Keep preference validation and input modifiers while presenting the mobile dialog surface. */
class MobilePreferenceDialog : EditTextPreferenceDialogFragmentCompat() {
    override fun onStart() {
        super.onStart()
        val alert = dialog as? AlertDialog ?: return
        val density = resources.displayMetrics.density
        MobileAlertDialogBuilder.style(alert)
        alert.findViewById<android.widget.EditText>(android.R.id.edit)?.apply {
            // The same dark well as every input of the app: tinting the theme's own field turned it into a white bar.
            setTextColor(MobilePalette.text); setHintTextColor(MobilePalette.muted)
            backgroundTintList = null
            background = android.graphics.drawable.GradientDrawable().apply { setColor(MobilePalette.well); cornerRadius = MobilePalette.wellDp * density }
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            minHeight = (48 * density).toInt()
            MobilePalette.tintCursor(this)
            if(preference is HashTextPreference) {
                val input=this
                val parent=input.parent as? android.view.ViewGroup
                if(parent!=null && parent.tag!="hash-input-row") {
                    val position=parent.indexOfChild(input)
                    val oldParams=input.layoutParams
                    parent.removeView(input)
                    val row=android.widget.LinearLayout(requireContext()).apply { tag="hash-input-row";gravity=android.view.Gravity.CENTER_VERTICAL }
                    row.addView(input,android.widget.LinearLayout.LayoutParams(0,-2,1f))
                    row.addView(android.widget.ImageButton(requireContext()).apply {
                        setImageResource(app.rawline.mobile.R.drawable.ic_mobile_copy_outline)
                        contentDescription="Скопировать хеш VK"
                        background=null
                        imageTintList=android.content.res.ColorStateList.valueOf(MobilePalette.accent(requireContext()))
                        setPadding((12*density).toInt(),(12*density).toInt(),(12*density).toInt(),(12*density).toInt())
                        setOnClickListener { input.text.toString().takeIf(String::isNotBlank)?.let { app.rawline.mobile.SagerNet.trySetPrimaryClip(it) } }
                    },android.widget.LinearLayout.LayoutParams((48*density).toInt(),(48*density).toInt()))
                    parent.addView(row,position,oldParams)
                }
            }
        }
    }

    companion object {
        fun show(fragment: androidx.preference.PreferenceFragmentCompat, preference: androidx.preference.EditTextPreference) {
            if (fragment.parentFragmentManager.findFragmentByTag("androidx.preference.PreferenceFragment.DIALOG") != null) return
            if(preference.dialogTitle.isNullOrBlank()) preference.dialogTitle = preference.title
            MobilePreferenceDialog().apply {
                arguments = Bundle().apply { putString("key", preference.key) }
                @Suppress("DEPRECATION")
                setTargetFragment(fragment, 0)
            }.show(fragment.parentFragmentManager, "androidx.preference.PreferenceFragment.DIALOG")
        }
    }
}
