package moe.matsuri.nb4a.proxy

import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.ktx.Logs
import app.qthrone.mobile.ktx.readableMessage
import app.qthrone.mobile.outbound.common.MuxStateModel
import java.lang.reflect.Field
import java.lang.reflect.ParameterizedType

object Type {
    const val Text = 0
    const val TextToInt = 1
    const val Int = 2
    const val Bool = 3

    /**
     * A "keep default / on / off" menu stored as "0" / "1" / "2" in the cache, backed by a Boolean field plus the
     * Boolean companion named by [PreferenceBinding.unspecifiedField] that is true while the value is unspecified.
     */
    const val TriState = 4

    /** The multiplex menu of [app.qthrone.mobile.outbound.common.MuxStateModel] stored as "0" to "3"; bind it last. */
    const val MuxState = 5
}

/**
 * Binds one preference (key = [cacheName], value in [DataStore.profileCacheStore]) to a public `@JvmField` member of
 * an outbound. [fieldName] may be a dotted path ("tls.server_name", "tls.utls.fingerPrint", "peer.public_key"): every
 * segment but the last is read as a public field and walked into. String, Int, Long and Boolean members bind as
 * today; `List<String>` and `List<Int>` members bind as newline-joined text.
 */
class PreferenceBinding(
    val type: Int = Type.Text,
    var fieldName: String,
    var bean: Any? = null,
    var pf: PreferenceFragmentCompat? = null,
    var unspecifiedField: String? = null,
) {

    var cacheName = fieldName
    var disable = false

    fun readStringFromCache(): String {
        return DataStore.profileCacheStore.getString(cacheName) ?: ""
    }

    fun readBoolFromCache(): Boolean {
        return DataStore.profileCacheStore.getBoolean(cacheName, false)
    }

    fun readIntFromCache(): Int {
        return DataStore.profileCacheStore.getInt(cacheName, 0)
    }

    fun readStringToIntFromCache(): Int {
        return DataStore.profileCacheStore.getString(cacheName)?.trim()?.toIntOrNull() ?: 0
    }

    private class Target(val owner: Any, val field: Field)

    private fun resolve(path: String): Target? {
        var owner: Any = bean ?: return null
        val segments = path.split('.')
        try {
            for (i in 0 until segments.size - 1) {
                owner = owner.javaClass.getField(segments[i]).get(owner) ?: return null
            }
            return Target(owner, owner.javaClass.getField(segments.last()))
        } catch (e: Exception) {
            Logs.d("binding no field $path: ${e.readableMessage}")
            return null
        }
    }

    private fun listElementIsInt(field: Field): Boolean {
        val arg = (field.genericType as? ParameterizedType)?.actualTypeArguments?.firstOrNull()
        return arg == Integer::class.java || arg == Int::class.javaObjectType
    }

    private fun textToValue(text: String, field: Field): Any? = when {
        field.type == String::class.java -> text
        field.type == Int::class.javaPrimitiveType || field.type == Integer::class.java -> text.trim().toIntOrNull() ?: 0
        field.type == Long::class.javaPrimitiveType || field.type == java.lang.Long::class.java -> text.trim().toLongOrNull() ?: 0L
        field.type == Boolean::class.javaPrimitiveType || field.type == java.lang.Boolean::class.java -> text.trim() == "true"
        List::class.java.isAssignableFrom(field.type) -> {
            val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (listElementIsInt(field)) ArrayList(lines.mapNotNull { it.toIntOrNull() }) else ArrayList(lines)
        }
        else -> null
    }

    private fun valueToText(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is List<*> -> value.joinToString("\n") { it.toString() }
        else -> value.toString()
    }

    fun fromCache() {
        if (disable) return
        val target = resolve(fieldName) ?: return
        val f = target.field
        when (type) {
            Type.Text -> textToValue(readStringFromCache(), f)?.let { f.set(target.owner, it) }
            Type.TextToInt -> textToValue(readStringFromCache(), f)?.let { f.set(target.owner, it) }
            Type.Int -> {
                if (f.type == Long::class.javaPrimitiveType) f.set(target.owner, readIntFromCache().toLong())
                else f.set(target.owner, readIntFromCache())
            }

            Type.Bool -> f.set(target.owner, readBoolFromCache())
            Type.TriState -> {
                val state = readStringToIntFromCache()
                f.set(target.owner, state == 1)
                unspecifiedField?.let { resolve(it) }?.let { it.field.set(it.owner, state == 0) }
            }

            Type.MuxState -> (f.get(target.owner) as? MuxStateModel)?.saveMuxState(readStringToIntFromCache())
        }
    }

    fun writeToCache() {
        if (disable) return
        val target = resolve(fieldName) ?: return
        val value = target.field.get(target.owner)
        when (type) {
            Type.Text, Type.TextToInt -> DataStore.profileCacheStore.putString(cacheName, valueToText(value))
            Type.Int -> when (value) {
                is Int -> DataStore.profileCacheStore.putInt(cacheName, value)
                is Long -> DataStore.profileCacheStore.putInt(cacheName, value.toInt())
            }

            Type.Bool -> if (value is Boolean) DataStore.profileCacheStore.putBoolean(cacheName, value)
            Type.TriState -> {
                val enabled = value == true
                val unspecified = unspecifiedField?.let { resolve(it) }?.let { it.field.get(it.owner) == true } ?: false
                val state = if (enabled) 1 else if (unspecified) 0 else 2
                DataStore.profileCacheStore.putString(cacheName, state.toString())
            }

            Type.MuxState -> (value as? MuxStateModel)?.let {
                DataStore.profileCacheStore.putString(cacheName, it.getMuxState().toString())
            }
        }
    }

    val preference by lazy {
        pf!!.findPreference<Preference>(cacheName)!!
    }
}
