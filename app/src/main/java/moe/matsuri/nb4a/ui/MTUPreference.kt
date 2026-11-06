package moe.matsuri.nb4a.ui

import android.content.Context
import android.util.AttributeSet
import android.text.InputType
import android.widget.EditText
import androidx.preference.ListPreference
import app.qthrone.mobile.ui.MobileAlertDialogBuilder as MobileAlertDialogBuilder
import app.qthrone.mobile.R

class MTUPreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = R.attr.dropdownPreferenceStyle
) : SimpleMenuPreference(context, attrs, defStyle, 0) {
    init { setSummaryProvider { value.orEmpty() } }

    override fun onClick() {
        val options=arrayOf("1500", "9000", context.getString(R.string.qmobile_mtu_manual))
        val selected=when(value) { "1500" -> 0; "9000" -> 1; else -> 2 }
        app.qthrone.mobile.ui.MobileSelector.showAt(anchor ?: return,options.toList(),selected) { index ->
            if(index == 2) showCustom() else save(options[index])
        }
    }

    private fun save(raw: String) {
        if(callChangeListener(raw)) value=raw
    }

    private fun showCustom() {
        val input=EditText(context).apply {
            inputType=InputType.TYPE_CLASS_NUMBER
            setText(value.orEmpty()); selectAll()
            val vertical = (8 * context.resources.displayMetrics.density).toInt()
            val inset = android.graphics.Rect()
            background?.getPadding(inset)
            setPadding(inset.left, vertical, inset.right, vertical)
        }
        input.setTextColor(app.qthrone.mobile.ui.MobilePalette.text)
        input.backgroundTintList=android.content.res.ColorStateList.valueOf(app.qthrone.mobile.ui.MobilePalette.accent(context))
        val padding=(24*context.resources.displayMetrics.density).toInt()
        val body=android.widget.LinearLayout(context).apply { setPadding(padding,0,padding,padding/2);addView(input,android.widget.LinearLayout.LayoutParams(-1,-2)) }
        val dialog=MobileAlertDialogBuilder(context).setTitle("MTU").setView(body)
            .setPositiveButton(android.R.string.ok,null)
            .setNegativeButton(android.R.string.cancel,null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val mtu=input.text.toString().toIntOrNull()
                if(mtu==null || mtu !in 1000..10000) {
                    input.error=context.getString(R.string.qmobile_mtu_range)
                } else { save(mtu.toString()); dialog.dismiss() }
            }
            for(button in listOf(-1,-2)) dialog.getButton(button).setTextColor(app.qthrone.mobile.ui.MobilePalette.accent(context))
        }
        dialog.show()
    }
}
