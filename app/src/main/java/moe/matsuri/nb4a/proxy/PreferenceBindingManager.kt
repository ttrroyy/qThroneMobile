package moe.matsuri.nb4a.proxy

import androidx.preference.PreferenceFragmentCompat


class PreferenceBindingManager {
    val items = mutableListOf<PreferenceBinding>()

    fun add(b: PreferenceBinding): PreferenceBinding {
        items.add(b)
        return b
    }

    fun text(path: String, cacheName: String = path) =
        add(PreferenceBinding(Type.Text, path).apply { this.cacheName = cacheName })

    fun int(path: String, cacheName: String = path) =
        add(PreferenceBinding(Type.TextToInt, path).apply { this.cacheName = cacheName })

    fun bool(path: String, cacheName: String = path) =
        add(PreferenceBinding(Type.Bool, path).apply { this.cacheName = cacheName })

    /** [path] names the MuxStateModel member; its rows must be bound before it, as reading the state may clear them. */
    fun muxState(path: String, cacheName: String) =
        add(PreferenceBinding(Type.MuxState, path).apply { this.cacheName = cacheName })

    fun tri(path: String, unspecifiedPath: String, cacheName: String = path) =
        add(PreferenceBinding(Type.TriState, path, unspecifiedField = unspecifiedPath).apply { this.cacheName = cacheName })

    fun fromCacheAll(bean: Any) {
        items.forEach {
            it.bean = bean
            it.fromCache()
        }
    }

    fun writeToCacheAll(bean: Any) {
        items.forEach {
            it.bean = bean
            it.writeToCache()
        }
    }

    fun setPreferenceFragment(pf: PreferenceFragmentCompat) {
        items.forEach {
            it.pf = pf
        }
    }

}
