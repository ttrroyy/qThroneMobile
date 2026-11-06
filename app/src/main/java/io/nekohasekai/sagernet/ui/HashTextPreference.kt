package app.rawline.mobile.ui

import android.content.Context
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceViewHolder
import app.rawline.mobile.R
import app.rawline.mobile.SagerNet
import android.content.res.ColorStateList
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.ImageButton

class HashTextPreference(context: Context) : EditTextPreference(context) {
    init { widgetLayoutResource = R.layout.qmobile_hash_copy }
    private var originalTitle: String? = null
    fun setCheckStatus(status: String?) {
        val base=originalTitle ?: title.toString().also { originalTitle=it }
        val label=when(status) {
            null, "В очереди", "Проверяется…", "Остановлено" -> null
            "TURN доступен", "Звонок доступен" -> "доступен"
            "Не удалось проверить", "Нет ответа проверки" -> "не проверен"
            else -> "недоступен"
        }
        title=if(label==null) base else SpannableString("$base $label").apply {
            setSpan(ForegroundColorSpan(MobilePalette.accent(context)),base.length+1,length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.hash_copy) as? ImageButton)?.apply {
            imageTintList=ColorStateList.valueOf(MobilePalette.accent(context))
            isEnabled = !text.isNullOrBlank()
            alpha=if(isEnabled) 1f else .35f
            setOnClickListener { text?.takeIf(String::isNotBlank)?.let { SagerNet.trySetPrimaryClip(it) } }
        }
    }
}
