package app.rawline.mobile.widget

import android.content.Context
import android.util.AttributeSet
import androidx.core.content.res.TypedArrayUtils
import androidx.preference.Preference

/**
 * A list value persisted as newline-joined text (the PreferenceBinding form of a `List<String>` member) that is
 * edited elsewhere, e.g. in a picker activity; the owner sets the click and summary behaviour.
 */
class StringLinesPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = TypedArrayUtils.getAttr(
        context, androidx.preference.R.attr.preferenceStyle, android.R.attr.preferenceStyle
    ),
    defStyleRes: Int = 0,
) : Preference(context, attrs, defStyleAttr, defStyleRes) {

    val values: List<String>
        get() = getPersistedString(null).orEmpty().lineSequence()
            .map { it.trim() }.filter { it.isNotEmpty() }.toList()

    /** Re-reads the persisted value after it was written to the data store directly. */
    fun refresh() = notifyChanged()
}
