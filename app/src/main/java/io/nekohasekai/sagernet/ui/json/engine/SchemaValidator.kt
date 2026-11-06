package app.rawline.mobile.ui.json.engine

import app.rawline.mobile.ui.json.engine.SchemaDocument.Companion.list
import app.rawline.mobile.ui.json.engine.SchemaDocument.Companion.obj
import app.rawline.mobile.ui.json.engine.SchemaDocument.Companion.scalarText

enum class Severity { Error, Warning }

class JsonIssue(
    @JvmField val severity: Severity,
    @JvmField val message: String,
    @JvmField val pointer: String,
    @JvmField val span: JsonSpan,
    /** Set when a union rejected the value outright: the wrong variant, not a bad field. */
    @JvmField val variantMismatch: Boolean = false,
)

/**
 * The JSON Schema subset of JsonSchemaValidator.cpp with its messages. A oneOf is resolved through its discriminating
 * constant (type/action), so errors name the variant. Relaxations are per editor: [allowExtraType] (desktop
 * AllowExtraType), [allowExtraKey] for keys only Rawline writes, [aliasType] for a root `type` Rawline names differently
 * and [unknownKeysAsWarnings].
 */
class SchemaValidator private constructor(
    private val doc: SchemaDocument,
    private val root: Map<String, Any?>,
) {

    private val extraTypes = HashMap<String, MutableSet<JsonType>>()
    private val extraKeys = ArrayList<Pair<List<String>, String>>()
    private val typeAliases = HashMap<String, String>()
    private val patterns = HashMap<String, Regex?>()

    var unknownKeysAsWarnings = false

    fun allowExtraType(propertyName: String, type: JsonType) {
        extraTypes.getOrPut(propertyName) { HashSet() }.add(type)
    }

    /** [pointer] addresses the instance object: "" is the root, "/tls" its TLS object; a `*` segment matches any. */
    fun allowExtraKey(pointer: String, key: String) {
        extraKeys.add(segments(pointer) to key)
    }

    fun aliasType(type: String, schemaType: String) {
        typeAliases[type] = schemaType
    }

    fun validate(root: JsonValue): List<JsonIssue> {
        val issues = ArrayList<JsonIssue>()
        validate(this.root, aliased(root), "", 0, issues, HashSet())
        issues.sortBy { it.span.offset }
        return issues
    }

    private fun aliased(value: JsonValue): JsonValue {
        if (typeAliases.isEmpty() || value.type != JsonType.Object) return value
        val index = value.indexOfKey("type")
        if (index < 0) return value
        val type = value.members[index]
        if (type.type != JsonType.String) return value
        val alias = typeAliases[type.string] ?: return value
        val copy = JsonValue(JsonType.Object)
        copy.span = value.span
        copy.keys.addAll(value.keys)
        copy.keySpans.addAll(value.keySpans)
        copy.members.addAll(value.members)
        copy.members[index] = JsonValue(JsonType.String).also {
            it.span = type.span
            it.string = alias
        }
        return copy
    }

    private fun relaxed(key: String, type: JsonType) = extraTypes[key]?.contains(type) == true

    private fun extraKey(pointer: String, key: String): Boolean {
        if (extraKeys.isEmpty()) return false
        val at = segments(pointer)
        return extraKeys.any { (path, name) ->
            name == key && path.size == at.size && path.indices.all { path[it] == "*" || path[it] == at[it] }
        }
    }

    private fun unknownField(key: String, pointer: String, span: JsonSpan) = JsonIssue(
        if (unknownKeysAsWarnings) Severity.Warning else Severity.Error,
        "Unknown field \"$key\"", pointer, span,
    )

    private fun validate(
        schema: Map<String, Any?>,
        value: JsonValue,
        pointer: String,
        depth: Int,
        issues: MutableList<JsonIssue>,
        evaluated: MutableSet<String>?,
    ) {
        if (depth > MAX_SCHEMA_DEPTH || issues.size >= MAX_ISSUES) return

        val local = HashSet<String>()
        fun propagate() {
            evaluated?.addAll(local)
        }

        val ref = schema["\$ref"] as? String
        if (!ref.isNullOrEmpty()) doc.lookup(ref)?.let { validate(it, value, pointer, depth + 1, issues, local) }

        if (schema.containsKey("type")) {
            val types = SchemaDocument.types(schema["type"])
            if (types.none { matchesType(it, value) }) {
                issues.add(
                    JsonIssue(
                        Severity.Error, "Expected ${types.joinToString(" or ")}, got ${JsonTree.typeName(value.type)}",
                        pointer, value.span,
                    )
                )
                propagate()
                return
            }
        }

        if (schema.containsKey("const")) {
            val expected = schema["const"]
            if (!equalsJson(value, expected)) {
                issues.add(JsonIssue(Severity.Error, "Expected ${scalarText(expected)} here", pointer, value.span))
                propagate()
                return
            }
        }

        if (schema.containsKey("enum")) {
            val allowed = list(schema["enum"])
            if (allowed.none { equalsJson(value, it) }) {
                issues.add(
                    JsonIssue(
                        Severity.Error, "${describeValue(value)} is not valid here (expected: ${joinScalars(allowed)})",
                        pointer, value.span,
                    )
                )
                propagate()
                return
            }
        }

        if (schema["deprecated"] == true) {
            issues.add(JsonIssue(Severity.Warning, "This option is deprecated", pointer, value.span))
        }

        if (value.type == JsonType.String && schema.containsKey("pattern")) {
            val pattern = pattern(schema["pattern"] as? String)
            if (pattern != null && !pattern.containsMatchIn(value.string)) {
                issues.add(
                    JsonIssue(Severity.Error, "\"${value.string}\" is not in the expected format", pointer, value.span)
                )
            }
        }

        if (value.type == JsonType.Number) {
            (schema["minimum"] as? Number)?.toDouble()?.let { minimum ->
                if (value.number < minimum) issues.add(
                    JsonIssue(
                        Severity.Error, "Value must be at least ${SchemaDocument.formatNumber(minimum)}",
                        pointer, value.span,
                    )
                )
            }
            (schema["maximum"] as? Number)?.toDouble()?.let { maximum ->
                if (value.number > maximum) issues.add(
                    JsonIssue(
                        Severity.Error, "Value must be at most ${SchemaDocument.formatNumber(maximum)}",
                        pointer, value.span,
                    )
                )
            }
        }

        if (value.type == JsonType.Array && schema.containsKey("items")) {
            val items = obj(schema["items"]) ?: SchemaDocument.EMPTY
            for (i in value.elements.indices) {
                validate(items, value.elements[i], "$pointer/$i", depth + 1, issues, null)
            }
        }

        if (value.type == JsonType.Object) validateObject(schema, value, pointer, depth, issues, local)

        for (branch in list(schema["allOf"])) {
            validate(obj(branch) ?: SchemaDocument.EMPTY, value, pointer, depth + 1, issues, local)
        }
        if (schema.containsKey("anyOf")) validateUnion(list(schema["anyOf"]), false, value, pointer, depth, issues, local)
        if (schema.containsKey("oneOf")) validateUnion(list(schema["oneOf"]), true, value, pointer, depth, issues, local)

        if (value.type == JsonType.Object && schema["unevaluatedProperties"] == false) {
            for (i in value.keys.indices) {
                val key = value.keys[i]
                if (key in local || relaxed(key, value.members[i].type) || extraKey(pointer, key)) continue
                issues.add(unknownField(key, pointer, value.keySpans[i]))
            }
        }

        propagate()
    }

    private fun validateObject(
        schema: Map<String, Any?>,
        value: JsonValue,
        pointer: String,
        depth: Int,
        issues: MutableList<JsonIssue>,
        evaluated: MutableSet<String>,
    ) {
        val properties = obj(schema["properties"]) ?: SchemaDocument.EMPTY
        val additional = schema["additionalProperties"]
        val propertyNames = obj(schema["propertyNames"])

        for (i in value.keys.indices) {
            val key = value.keys[i]
            val member = value.members[i]

            if (relaxed(key, member.type) || extraKey(pointer, key)) {
                evaluated.add(key)
                continue
            }

            if (!propertyNames.isNullOrEmpty()) {
                val name = JsonValue(JsonType.String)
                name.string = key
                name.span = value.keySpans[i]
                validate(propertyNames, name, pointer, depth + 1, issues, null)
            }

            if (properties.containsKey(key)) {
                evaluated.add(key)
                validate(obj(properties[key]) ?: SchemaDocument.EMPTY, member, "$pointer/$key", depth + 1, issues, null)
                continue
            }
            if (additional is Map<*, *>) {
                evaluated.add(key)
                validate(obj(additional)!!, member, "$pointer/$key", depth + 1, issues, null)
                continue
            }
            if (additional == false) issues.add(unknownField(key, pointer, value.keySpans[i]))
        }

        for (entry in list(schema["required"])) {
            val name = entry as? String ?: continue
            if (value.indexOfKey(name) < 0) {
                issues.add(
                    JsonIssue(Severity.Error, "Missing required field \"$name\"", pointer, JsonSpan(value.span.offset, 1))
                )
            }
        }
    }

    private fun validateUnion(
        branches: List<Any?>,
        exclusive: Boolean,
        value: JsonValue,
        pointer: String,
        depth: Int,
        issues: MutableList<JsonIssue>,
        evaluated: MutableSet<String>,
    ) {
        if (branches.isEmpty()) return

        val resolved = doc.flattenUnion(branches)
        if (resolved.isEmpty()) return
        var candidates: List<Int> = resolved.indices.toList()

        // Narrow repeatedly by the constants branches pin their properties to: "type" first, then variants of it.
        if (value.type == JsonType.Object) {
            val tried = HashSet<String>()
            var round = 0
            while (round < 4 && candidates.size > 1) {
                round++
                var key = ""
                var allowedPerBranch: List<List<Any?>> = emptyList()
                for (name in doc.branchKeys(resolved[candidates.first()], sorted = true)) {
                    if (name in tried) continue
                    val allowed = ArrayList<List<Any?>>()
                    var usable = true
                    for (index in candidates) {
                        val rule = doc.branchProperty(resolved[index], name)
                        val pinned = rule?.let { SchemaDocument.pinnedValues(it) }
                        if (pinned.isNullOrEmpty()) {
                            usable = false
                            break
                        }
                        allowed.add(pinned)
                    }
                    if (usable) {
                        key = name
                        allowedPerBranch = allowed
                        break
                    }
                }
                if (key.isEmpty()) break
                tried.add(key)

                val narrowed = ArrayList<Int>()
                val keyIndex = value.indexOfKey(key)
                if (keyIndex >= 0) {
                    val given = value.members[keyIndex]
                    for (n in candidates.indices) {
                        // the empty string marks "may be omitted", not a variant name
                        if (allowedPerBranch[n].any { scalarText(it).isNotEmpty() && equalsJson(given, it) }) {
                            narrowed.add(candidates[n])
                        }
                    }
                    if (narrowed.isEmpty()) {
                        val names = ArrayList<Any?>()
                        val seen = HashSet<String>()
                        for (options in allowedPerBranch) for (option in options) {
                            val text = scalarText(option)
                            if (text.isEmpty() || !seen.add(text)) continue
                            names.add(option)
                        }
                        evaluated.add(key)
                        issues.add(
                            JsonIssue(
                                Severity.Error,
                                "Unknown $key ${describeValue(given)} (expected: ${joinScalars(names)})",
                                pointer, given.span, variantMismatch = true,
                            )
                        )
                        return
                    }
                } else {
                    for (index in candidates) if (!doc.branchRequires(resolved[index], key)) narrowed.add(index)
                    if (narrowed.isEmpty()) break
                }
                candidates = narrowed
            }
        }

        if (candidates.size == 1) {
            validate(resolved[candidates.first()], value, pointer, depth + 1, issues, evaluated)
            return
        }

        var bestIndex = -1
        var bestScore = 0
        var bestIssues: List<JsonIssue> = emptyList()
        var bestEvaluated: Set<String> = emptySet()
        for (index in candidates) {
            val trial = ArrayList<JsonIssue>()
            val trialEvaluated = HashSet<String>()
            validate(resolved[index], value, pointer, depth + 1, trial, trialEvaluated)
            val errors = trial.count { it.severity == Severity.Error }
            if (errors == 0) {
                evaluated.addAll(trialEvaluated)
                issues.addAll(trial)
                return
            }
            val score = errors + if (trial.any { it.variantMismatch }) VARIANT_PENALTY else 0
            if (bestIndex < 0 || score < bestScore) {
                bestIndex = index
                bestScore = score
                bestIssues = trial
                bestEvaluated = trialEvaluated
            }
        }

        if (exclusive && bestIssues.isNotEmpty()) {
            evaluated.addAll(bestEvaluated)
            issues.addAll(bestIssues.take(MAX_UNION_ISSUES))
            return
        }

        val forms = ArrayList<String>()
        for (branch in resolved) {
            for (name in SchemaDocument.types(branch["type"])) if (name.isNotEmpty() && name !in forms) forms.add(name)
        }
        if (forms.isEmpty()) {
            issues.add(JsonIssue(Severity.Error, "Value does not match any accepted form", pointer, value.span))
        } else {
            issues.add(
                JsonIssue(
                    Severity.Error, "Expected ${forms.joinToString(" or ")}, got ${JsonTree.typeName(value.type)}",
                    pointer, value.span,
                )
            )
        }
    }

    private fun pattern(source: String?): Regex? {
        if (source == null) return null
        return patterns.getOrPut(source) {
            try {
                Regex(source)
            } catch (_: Exception) {
                null
            }
        }
    }

    companion object {
        private const val MAX_SCHEMA_DEPTH = 64
        private const val MAX_ISSUES = 200
        private const val MAX_LISTED = 10
        private const val MAX_UNION_ISSUES = 6

        // a branch the value simply is not (wrong "type"/"action") ranks below one it almost matches
        private const val VARIANT_PENALTY = 1000

        /** [rootRef] is a JSON pointer into the schema ("#/$defs/RouteOptions"); empty is the document root. */
        fun create(schema: SchemaDocument, rootRef: String = ""): SchemaValidator? {
            if (schema.root.isEmpty()) return null
            val root = if (rootRef.isEmpty()) schema.root else schema.lookup(rootRef) ?: return null
            return SchemaValidator(schema, root)
        }

        fun createForNode(schema: SchemaDocument, root: Map<String, Any?>) = SchemaValidator(schema, root)

        fun segments(pointer: String): List<String> =
            if (pointer.isEmpty() || pointer == "/") emptyList() else pointer.removePrefix("/").split('/')

        fun matchesType(name: String, value: JsonValue): Boolean = when (name) {
            "object" -> value.type == JsonType.Object
            "array" -> value.type == JsonType.Array
            "string" -> value.type == JsonType.String
            "boolean" -> value.type == JsonType.Bool
            "null" -> value.type == JsonType.Null
            "number" -> value.type == JsonType.Number
            "integer" -> value.type == JsonType.Number && value.integral
            else -> true
        }

        fun equalsJson(value: JsonValue, json: Any?): Boolean = when (value.type) {
            JsonType.String -> json is String && json == value.string
            JsonType.Bool -> json is Boolean && json == value.bool
            JsonType.Number -> json is Number && SchemaDocument.sameNumber(json.toDouble(), value.number)
            JsonType.Null -> json == null
            else -> false
        }

        fun describeValue(value: JsonValue): String = when (value.type) {
            JsonType.String -> "\"${value.string}\""
            JsonType.Bool -> if (value.bool) "true" else "false"
            JsonType.Number -> SchemaDocument.formatNumber(value.number)
            JsonType.Null -> "null"
            else -> JsonTree.typeName(value.type)
        }

        private fun joinScalars(values: List<Any?>): String {
            val text = ArrayList<String>()
            for (value in values) {
                if (text.size >= MAX_LISTED) {
                    text.add("...")
                    break
                }
                text.add(scalarText(value))
            }
            return text.joinToString(", ")
        }
    }
}
