package moe.matsuri.nb4a.ui

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import app.rawline.mobile.Key
import app.rawline.mobile.R
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.ktx.app
import app.rawline.mobile.ui.json.JsonEditorActivity
import app.rawline.mobile.ui.json.JsonRelaxations

/**
 * Opens the JSON editor on a profile-cache entry: the preference's own key by default (`serverConfig` when it has
 * none), or a configuration-store key through [useConfigStore]. `app:jsonSchema` (`|`-separated schema pointers, `#` =
 * the whole config), `app:jsonAllowEmpty` and `app:jsonRequireObject` configure the editor; [schemaProvider] replaces
 * the schema when it depends on other fields. The Intent is built at click time. The summary is the line count.
 */
class EditConfigPreference : Preference {

    constructor(
        context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes) {
        readAttributes(context, attrs, defStyleAttr, defStyleRes)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        readAttributes(context, attrs, defStyleAttr, 0)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        readAttributes(context, attrs, 0, 0)
    }

    constructor(context: Context) : super(context)

    var configKey = key ?: Key.SERVER_CONFIG
    var useConfigStore = false
    var schemaRoots: List<String> = emptyList()
    var schemaProvider: (() -> List<String>)? = null
    var allowEmpty = false
    var requireObject = true
    var relaxations: JsonRelaxations? = null

    private fun readAttributes(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) {
        val array = context.obtainStyledAttributes(attrs, R.styleable.EditConfigPreference, defStyleAttr, defStyleRes)
        try {
            array.getString(R.styleable.EditConfigPreference_jsonSchema)?.let { schema ->
                schemaRoots = schema.split('|').map { it.trim().removePrefix("#").let { root -> if (root.isEmpty()) "" else "#$root" } }
            }
            allowEmpty = array.getBoolean(R.styleable.EditConfigPreference_jsonAllowEmpty, false)
            requireObject = array.getBoolean(R.styleable.EditConfigPreference_jsonRequireObject, true)
        } finally {
            array.recycle()
        }
    }

    fun useConfigStore(key: String) {
        configKey = key
        useConfigStore = true
    }

    override fun onClick() {
        intent = JsonEditorActivity.intent(
            context,
            configKey,
            useConfigStore = useConfigStore,
            schemaRoots = schemaProvider?.invoke() ?: schemaRoots,
            allowEmpty = allowEmpty,
            requireObject = requireObject,
            relaxations = relaxations,
            title = title,
        )
    }

    override fun getSummary(): CharSequence {
        val config =
            (if (useConfigStore) DataStore.configurationStore.getString(configKey) else DataStore.profileCacheStore.getString(configKey))
                ?: ""
        return if (config.isBlank()) {
            app.resources.getString(androidx.preference.R.string.not_set)
        } else {
            app.resources.getString(R.string.lines, config.split('\n').size)
        }
    }

    public override fun notifyChanged() {
        super.notifyChanged()
    }

}
