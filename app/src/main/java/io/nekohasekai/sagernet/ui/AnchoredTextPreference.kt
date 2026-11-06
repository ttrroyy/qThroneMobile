package app.rawline.mobile.ui

import android.content.Context
import android.view.View
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceViewHolder

class AnchoredTextPreference(context: Context) : EditTextPreference(context) {
    var onSelect: ((View) -> Unit)? = null
    private var anchor: View? = null

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        anchor = holder.itemView
    }

    override fun onClick() {
        val row = anchor ?: return
        onSelect?.invoke(row)
    }
}
