package app.rawline.mobile.outbound.import

import app.rawline.mobile.outbound.json.JsonArray
import app.rawline.mobile.outbound.json.JsonObject
import java.util.TreeMap

/**
 * One Clash `proxies:` entry with the field conversions of the desktop's clash::from_node / load_opt
 * (src/configs/sub/clash.cpp): a string field also accepts an integer or a boolean, a bool field (MyBool) accepts
 * `true`, the integer 1 or the strings "true" / "1", an int field (MyInt) accepts an integer or a string parsed
 * like std::stoi, and everything of another shape keeps its default. Nested option blocks are exposed as
 * [ClashProxy] views themselves so the per-type parsers read `ws-opts`, `smux`, `reality-opts` ... the same way.
 */
class ClashProxy(@JvmField val node: JsonObject) {

    /** The `type` field lower-cased (the desktop matches it verbatim; the Android importer is case-insensitive). */
    val type: String get() = string("type").lowercase()

    fun has(key: String): Boolean = node.contains(key)

    /** load_opt<std::string>. */
    fun string(key: String): String = when {
        node.isString(key) -> node.string(key)
        node.isNumber(key) && node[key] is Long -> node.integer(key).toString()
        node.isBool(key) -> if (node.bool(key)) "true" else "false"
        else -> ""
    }

    /** from_node(MyBool) / load_opt<bool>. */
    fun bool(key: String): Boolean = when {
        node.isBool(key) -> node.bool(key)
        node.isNumber(key) && node[key] is Long -> node.integer(key) == 1L
        node.isString(key) -> node.string(key).let { it == "true" || it == "1" }
        else -> false
    }

    /** from_node(MyInt): an integer, or a string parsed like std::stoi (leading digits, 0 on failure). */
    fun int(key: String): Int = when {
        node.isNumber(key) && node[key] is Long -> node.integer(key).toInt()
        node.isString(key) -> stoi(node.string(key))
        else -> 0
    }

    /** A string or a sequence of strings (alpn, host-key, h2 host ...); scalars of other types are stringified. */
    fun strings(key: String): List<String> = when {
        node.isString(key) -> listOf(node.string(key))
        node.isArray(key) -> scalarStrings(node.array(key))
        else -> emptyList()
    }

    /** A nested option block; an empty view when the key is absent or not a mapping. */
    fun obj(key: String): ClashProxy = ClashProxy(if (node.isObject(key)) node.obj(key) else JsonObject())

    /** std::map<std::string, std::string> (ws-opts headers), in the map's sorted key order. */
    fun stringMap(key: String): Map<String, String> {
        val out = TreeMap<String, String>()
        if (!node.isObject(key)) return out
        val obj = node.obj(key)
        for (k in obj.keys()) out[k] = ClashProxy(obj).string(k)
        return out
    }

    /** std::map<std::string, std::vector<std::string>> (http-opts headers). */
    fun stringListMap(key: String): Map<String, List<String>> {
        val out = TreeMap<String, List<String>>()
        if (!node.isObject(key)) return out
        val obj = node.obj(key)
        for (k in obj.keys()) out[k] = ClashProxy(obj).strings(k)
        return out
    }

    companion object {
        private fun scalarStrings(arr: JsonArray): List<String> = arr.mapNotNull {
            when (it) {
                is String -> it
                is Long -> it.toString()
                is Boolean -> if (it) "true" else "false"
                else -> null
            }
        }

        /** std::stoi: optional leading whitespace, an optional sign, then the longest digit run; 0 when none or overflow. */
        @JvmStatic
        fun stoi(text: String): Int {
            var i = 0
            while (i < text.length && text[i].isWhitespace()) i++
            val start = i
            if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
            val digitsStart = i
            while (i < text.length && text[i] in '0'..'9') i++
            if (i == digitsStart) return 0
            return text.substring(start, i).toIntOrNull() ?: 0
        }
    }
}
