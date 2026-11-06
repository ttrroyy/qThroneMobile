package app.rawline.mobile.ui.json.engine

import kotlin.math.abs
import kotlin.math.min

/** A parsed JSON Schema document (plain values of [JsonTree.toPlain]) with the lookups of JsonSchemaValidator.cpp. */
class SchemaDocument(@JvmField val root: Map<String, Any?>) {

    fun lookup(pointer: String): Map<String, Any?>? {
        var path = pointer
        if (path.startsWith("#")) path = path.substring(1)
        if (path.isEmpty() || path == "/") return root
        if (!path.startsWith("/")) return null
        var current: Any? = root
        for (rawToken in path.substring(1).split('/')) {
            val token = rawToken.replace("~1", "/").replace("~0", "~")
            current = when (current) {
                is Map<*, *> -> {
                    if (!current.containsKey(token)) return null
                    current[token]
                }

                is List<*> -> {
                    val index = token.toIntOrNull() ?: return null
                    if (index < 0 || index >= current.size) return null
                    current[index]
                }

                else -> return null
            }
        }
        return obj(current)
    }

    fun deref(node: Map<String, Any?>, depth: Int = 0): Map<String, Any?> {
        if (depth > 8) return node
        val ref = node["\$ref"] as? String
        if (ref.isNullOrEmpty()) return node
        val target = lookup(ref) ?: return node
        return deref(target, depth + 1)
    }

    /** The property rule of [key] in a branch read through its allOf/$ref composition. */
    fun branchProperty(branch: Map<String, Any?>, key: String, level: Int = 0): Map<String, Any?>? {
        if (level > 6) return null
        val node = deref(branch)
        val properties = obj(node["properties"])
        if (properties != null && properties.containsKey(key)) return deref(obj(properties[key]) ?: EMPTY)
        for (sub in list(node["allOf"])) {
            val found = branchProperty(obj(sub) ?: continue, key, level + 1)
            if (found != null) return found
        }
        return null
    }

    fun branchRequires(branch: Map<String, Any?>, key: String, level: Int = 0): Boolean {
        if (level > 6) return false
        val node = deref(branch)
        if (list(node["required"]).any { it == key }) return true
        for (sub in list(node["allOf"])) {
            if (branchRequires(obj(sub) ?: continue, key, level + 1)) return true
        }
        return false
    }

    /** Property names of a branch; [sorted] follows QJsonObject::keys() (the validator's discriminator search). */
    fun branchKeys(branch: Map<String, Any?>, sorted: Boolean, level: Int = 0): List<String> {
        if (level > 6) return emptyList()
        val node = deref(branch)
        val own = obj(node["properties"])?.keys ?: emptySet()
        val keys = ArrayList<String>(if (sorted) own.sorted() else own)
        for (sub in list(node["allOf"])) {
            for (key in branchKeys(obj(sub) ?: continue, sorted, level + 1)) {
                if (key !in keys) keys.add(key)
            }
        }
        return keys
    }

    fun branchRequired(branch: Map<String, Any?>, level: Int = 0): List<String> {
        if (level > 6) return emptyList()
        val node = deref(branch)
        val required = ArrayList<String>()
        for (entry in list(node["required"])) if (entry is String && entry !in required) required.add(entry)
        for (sub in list(node["allOf"])) {
            for (key in branchRequired(obj(sub) ?: continue, level + 1)) if (key !in required) required.add(key)
        }
        return required
    }

    /** A branch that is itself a bare union contributes its own branches (JsonSchemaValidator.cpp:414-426). */
    fun flattenUnion(branches: List<Any?>): List<Map<String, Any?>> {
        val resolved = ArrayList<Map<String, Any?>>()
        fun flatten(node: Map<String, Any?>, level: Int) {
            val branch = deref(node)
            if (level < 3 && branch.size == 1) {
                for (key in UNION_KEYS) {
                    if (!branch.containsKey(key)) continue
                    for (sub in list(branch[key])) flatten(obj(sub) ?: continue, level + 1)
                    return
                }
            }
            resolved.add(branch)
        }
        for (branch in branches) flatten(obj(branch) ?: continue, 0)
        return resolved
    }

    companion object {
        @JvmField
        val EMPTY: Map<String, Any?> = emptyMap()

        private val UNION_KEYS = listOf("oneOf", "anyOf")

        @Suppress("UNCHECKED_CAST")
        fun obj(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

        fun list(value: Any?): List<Any?> = value as? List<Any?> ?: emptyList()

        fun types(type: Any?): List<String> = when (type) {
            is String -> listOf(type)
            is List<*> -> type.map { it as? String ?: "" }
            else -> emptyList()
        }

        fun pinnedValues(rule: Map<String, Any?>): List<Any?> {
            if (rule.containsKey("const")) return listOf(rule["const"])
            if (rule.containsKey("enum")) return list(rule["enum"])
            return emptyList()
        }

        /** qFuzzyCompare(a + 1, b + 1), as the desktop compares numbers. */
        fun sameNumber(a: Double, b: Double): Boolean {
            val x = a + 1.0
            val y = b + 1.0
            return abs(x - y) * 1_000_000_000_000.0 <= min(abs(x), abs(y))
        }

        fun formatNumber(value: Double): String {
            if (value == Math.rint(value) && abs(value) < 1e15) return value.toLong().toString()
            return value.toString()
        }

        fun scalarText(json: Any?): String = when (json) {
            is String -> json
            is Boolean -> if (json) "true" else "false"
            is Double -> formatNumber(json)
            is Number -> formatNumber(json.toDouble())
            null -> "null"
            else -> ""
        }
    }
}
