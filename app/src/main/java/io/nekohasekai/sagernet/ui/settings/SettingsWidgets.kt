package app.rawline.mobile.ui.settings

import android.content.Context
import android.util.AttributeSet
import androidx.core.content.res.TypedArrayUtils
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import app.rawline.mobile.R
import app.rawline.mobile.database.SettingValidators
import app.rawline.mobile.database.preference.SettingsStore

/**
 * A multi-line editor over a string-list setting: one item per line on screen, a compact JSON array in the
 * [SettingsStore] (the desktop's encoding). Blank lines are dropped.
 */
class StringListPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = TypedArrayUtils.getAttr(
        context, androidx.preference.R.attr.editTextPreferenceStyle, android.R.attr.editTextPreferenceStyle
    ),
    defStyleRes: Int = 0,
) : EditTextPreference(context, attrs, defStyleAttr, defStyleRes) {

    init {
        setOnBindEditTextListener { editText ->
            editText.setHorizontallyScrolling(true)
            editText.setSelection(editText.text.length)
        }
        summaryProvider = LinesSummaryProvider(maxLines = 3)
    }

    override fun getPersistedString(defaultReturnValue: String?): String? {
        val store = preferenceDataStore as? SettingsStore ?: return super.getPersistedString(defaultReturnValue)
        return store.getStringList(key, emptyList()).joinToString("\n")
    }

    override fun persistString(value: String?): Boolean {
        if (!shouldPersist()) return false
        val store = preferenceDataStore as? SettingsStore ?: return super.persistString(value)
        val lines = SettingValidators.lines(value)
        if (lines == SettingValidators.lines(getPersistedString(null))) return true
        store.putStringList(key, lines)
        return true
    }
}

/** The first [maxLines] non-blank lines of an [EditTextPreference], "…" when there are more. */
class LinesSummaryProvider(private val maxLines: Int) : Preference.SummaryProvider<EditTextPreference> {

    override fun provideSummary(preference: EditTextPreference): CharSequence {
        val lines = preference.text.orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return preference.context.getString(androidx.preference.R.string.not_set)
        return if (lines.size > maxLines) {
            lines.take(maxLines).joinToString("\n", postfix = "\n…")
        } else {
            lines.joinToString("\n")
        }
    }
}

/** The text of an [EditTextPreference], or "Default (…)" naming what an empty value means. */
class DefaultSummaryProvider(private val defaultText: CharSequence) :
    Preference.SummaryProvider<EditTextPreference> {

    override fun provideSummary(preference: EditTextPreference): CharSequence {
        val text = preference.text.orEmpty()
        return text.ifEmpty { preference.context.getString(R.string.setting_default_value, defaultText) }
    }
}
