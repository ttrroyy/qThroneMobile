package app.rawline.mobile.ui.json.engine

import app.rawline.mobile.ui.json.engine.SchemaDocument.Companion.list
import app.rawline.mobile.ui.json.engine.SchemaDocument.Companion.obj
import app.rawline.mobile.ui.json.engine.SchemaDocument.Companion.scalarText
import java.util.IdentityHashMap

/**
 * One completion: [insert] replaces the range [caret - before, caret + after) of the text it was computed for; the
 * selection afterwards is [selectStart, selectEnd) relative to the start of [insert].
 */
class Suggestion(
    @JvmField val label: String,
    @JvmField val detail: String,
    @JvmField val insert: String,
    @JvmField val before: Int,
    @JvmField val after: Int,
    @JvmField val selectStart: Int,
    @JvmField val selectEnd: Int,
    /** Value suggestions are worth asking for right after this key was inserted. */
    @JvmField val chain: Boolean,
    @JvmField val isKey: Boolean,
)

/**
 * Schema-driven completion (the desktop has only the raw-route outbound completer, RawRouteItem.cpp:27-102): the caret
 * context comes from a tolerant scan of the whole text, the schema node from the path of containers around it with
 * unions narrowed by the constants the document already holds (type, action, ...).
 */
class SchemaCompletion(
    private val doc: SchemaDocument,
    private val roots: List<String>,
    private val typeAliases: Map<String, String> = emptyMap(),
    private val builtinTags: Map<String, List<String>> = emptyMap(),
) {

    private enum class State { KEY, COLON, VALUE, AFTER_VALUE }

    private class Frame(@JvmField val isObject: Boolean, @JvmField val segment: String?, @JvmField val parent: Frame?) {
        @JvmField var state = if (isObject) State.KEY else State.VALUE
        @JvmField var pendingKey: String? = null
        @JvmField var index = 0
        @JvmField val keys = LinkedHashSet<String>()
        @JvmField val scalars = HashMap<String, Any?>()
        @JvmField val values = HashSet<String>()
    }

    private class Capture(
        @JvmField val frames: List<Frame>,
        @JvmField val state: State?,
        @JvmField val pendingKey: String?,
        @JvmField val start: Int,
        @JvmField val end: Int,
        @JvmField val inString: Boolean,
        @JvmField val prefix: String,
    )

    private class Scan(@JvmField val capture: Capture?, @JvmField val tags: Map<String, List<String>>)

    fun suggest(text: String, caret: Int): List<Suggestion> {
        if (caret < 0 || caret > text.length) return emptyList()
        val scan = scan(text, caret)
        val capture = scan.capture ?: return emptyList()
        val top = capture.frames.lastOrNull() ?: return emptyList()
        val typed = capture.inString || capture.prefix.isNotEmpty()
        val suggestions = when {
            top.isObject && (capture.state == State.KEY || capture.state == State.AFTER_VALUE) -> {
                if (!typed) return emptyList()
                keySuggestions(text, capture, top)
            }

            top.isObject && capture.state == State.VALUE -> {
                val key = capture.pendingKey ?: return emptyList()
                val schemas = propertySchemas(capture.frames, key)
                valueSuggestions(text, capture, schemas, top.values, scan.tags)
            }

            !top.isObject && capture.state == State.VALUE -> {
                if (!typed) return emptyList()
                val schemas = elementSchemas(capture.frames)
                valueSuggestions(text, capture, schemas, top.values, scan.tags)
            }

            else -> return emptyList()
        }
        return filter(suggestions, capture.prefix)
    }

    // ---- scanning

    private fun scan(text: String, caret: Int): Scan {
        val stack = ArrayList<Frame>()
        val tags = HashMap<String, MutableList<String>>()
        var capture: Capture? = null
        var editing = -1
        val n = text.length

        fun top() = stack.lastOrNull()

        fun capture(start: Int, end: Int, inString: Boolean, prefix: String) {
            val frame = top()
            capture = Capture(ArrayList(stack), frame?.state, frame?.pendingKey, start, end, inString, prefix)
            editing = start
        }

        fun collectTag(frame: Frame) {
            val parent = frame.parent ?: return
            if (!frame.isObject || parent.isObject) return
            val kind = DEFINITION_ARRAYS[parent.segment] ?: return
            val tag = frame.scalars["tag"] as? String
            if (!tag.isNullOrEmpty()) tags.getOrPut(kind) { ArrayList() }.add(tag)
        }

        fun onScalar(value: Any?, start: Int, isString: Boolean, raw: String) {
            val frame = top() ?: return
            if (frame.isObject) {
                when (frame.state) {
                    State.KEY, State.AFTER_VALUE -> {
                        if (isString && start != editing) frame.keys.add(value as String)
                        frame.pendingKey = if (isString) value as String else raw
                        frame.state = State.COLON
                    }

                    State.COLON -> frame.state = State.AFTER_VALUE
                    State.VALUE -> {
                        val key = frame.pendingKey
                        if (key != null && start != editing) frame.scalars[key] = value
                        frame.state = State.AFTER_VALUE
                    }
                }
            } else {
                if (isString && start != editing) frame.values.add(value as String)
                frame.state = State.AFTER_VALUE
            }
        }

        var i = 0
        while (i < n) {
            if (capture == null && i >= caret) capture(caret, caret, false, "")
            val c = text[i]
            when {
                c == ' ' || c == '\t' || c == '\n' || c == '\r' -> i++
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    var end = i + 2
                    while (end < n && text[end] != '\n') end++
                    if (capture == null && caret in i + 1..end) return Scan(null, tags)
                    i = end
                }

                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val close = text.indexOf("*/", i + 2)
                    val end = if (close < 0) n else close + 2
                    if (capture == null && caret > i && caret < end) return Scan(null, tags)
                    i = end
                }

                c == '"' -> {
                    var end = i + 1
                    var terminated = false
                    while (end < n) {
                        val ch = text[end]
                        if (ch == '\\' && end + 1 < n && text[end + 1] != '\n') {
                            end += 2
                            continue
                        }
                        if (ch == '\n') break
                        end++
                        if (ch == '"') {
                            terminated = true
                            break
                        }
                    }
                    end = minOf(end, n)
                    val contentEnd = if (terminated) end - 1 else end
                    if (capture == null && caret > i && caret <= contentEnd) {
                        // an unclosed string runs to the line end; replace only the word being typed
                        var replaceEnd = end
                        if (!terminated) {
                            replaceEnd = caret
                            while (replaceEnd < contentEnd && isWordChar(text[replaceEnd])) replaceEnd++
                        }
                        capture(i, replaceEnd, true, text.substring(i + 1, caret))
                    }
                    val raw = text.substring(i + 1, contentEnd)
                    val decoded = if ('\\' !in raw) raw else JsonTree.parse("\"$raw\"").root?.string ?: raw
                    onScalar(decoded, i, true, decoded)
                    i = end
                }

                c == '{' || c == '[' -> {
                    val parent = top()
                    val segment = when {
                        parent == null -> null
                        parent.isObject -> parent.pendingKey ?: ""
                        else -> parent.index.toString()
                    }
                    stack.add(Frame(c == '{', segment, parent))
                    i++
                }

                c == '}' || c == ']' -> {
                    if (stack.isNotEmpty()) {
                        collectTag(stack.removeAt(stack.size - 1))
                        top()?.state = State.AFTER_VALUE
                    }
                    i++
                }

                c == ':' -> {
                    top()?.let { if (it.isObject && it.state == State.COLON) it.state = State.VALUE }
                    i++
                }

                c == ',' -> {
                    top()?.let {
                        if (it.isObject) {
                            it.state = State.KEY
                            it.pendingKey = null
                        } else {
                            it.index++
                            it.state = State.VALUE
                        }
                    }
                    i++
                }

                isWordChar(c) -> {
                    var end = i + 1
                    while (end < n && isWordChar(text[end])) end++
                    if (capture == null && caret > i && caret <= end) capture(i, end, false, text.substring(i, caret))
                    val word = text.substring(i, end)
                    val value: Any? = when (word) {
                        "true" -> true
                        "false" -> false
                        "null" -> null
                        else -> word.toDoubleOrNull() ?: word
                    }
                    onScalar(value, i, false, word)
                    i = end
                }

                else -> i++
            }
        }
        if (capture == null) capture(caret, caret, false, "")
        for (frame in stack.asReversed()) collectTag(frame)
        return Scan(capture, tags)
    }

    // ---- schema resolution

    /** Every way an instance can satisfy [node]: lists of schema parts whose direct keywords apply together. */
    private fun alternatives(node: Map<String, Any?>, depth: Int = 0): List<List<Map<String, Any?>>> {
        val n = doc.deref(node)
        if (depth > 8) return listOf(listOf(n))
        var result: List<List<Map<String, Any?>>> = listOf(listOf(n))
        for (sub in list(n["allOf"])) {
            result = product(result, alternatives(obj(sub) ?: continue, depth + 1))
        }
        val union = list(n["oneOf"]) + list(n["anyOf"])
        if (union.isNotEmpty()) {
            result = product(result, union.flatMap { alternatives(obj(it) ?: SchemaDocument.EMPTY, depth + 1) })
        }
        return result
    }

    private fun product(
        a: List<List<Map<String, Any?>>>,
        b: List<List<Map<String, Any?>>>,
    ): List<List<Map<String, Any?>>> {
        if (b.isEmpty()) return a
        val out = ArrayList<List<Map<String, Any?>>>()
        for (x in a) for (y in b) {
            if (out.size >= MAX_ALTERNATIVES) return out
            out.add(x + y)
        }
        return out
    }

    private fun alternativesOf(schemas: List<Map<String, Any?>>) = schemas.flatMap { alternatives(it) }

    private fun properties(alt: List<Map<String, Any?>>): Map<String, Map<String, Any?>> {
        val out = LinkedHashMap<String, Map<String, Any?>>()
        for (part in alt) {
            val props = obj(part["properties"]) ?: continue
            for ((key, rule) in props) if (key !in out) out[key] = doc.deref(obj(rule) ?: SchemaDocument.EMPTY)
        }
        return out
    }

    private fun required(alt: List<Map<String, Any?>>): Set<String> {
        val out = LinkedHashSet<String>()
        for (part in alt) for (entry in list(part["required"])) if (entry is String) out.add(entry)
        return out
    }

    private fun types(alt: List<Map<String, Any?>>): Set<String> {
        val out = LinkedHashSet<String>()
        for (part in alt) out.addAll(SchemaDocument.types(part["type"]))
        return out
    }

    private fun isObjectShape(alt: List<Map<String, Any?>>): Boolean {
        val types = types(alt)
        if (types.isNotEmpty()) return "object" in types
        return alt.any { it.containsKey("properties") || it.containsKey("additionalProperties") }
    }

    private fun isArrayShape(alt: List<Map<String, Any?>>): Boolean {
        val types = types(alt)
        if (types.isNotEmpty()) return "array" in types
        return alt.any { it.containsKey("items") }
    }

    /** Narrows object alternatives by the scalars the instance holds (desktop union narrowing, tolerant). */
    private fun discriminate(
        alts: List<List<Map<String, Any?>>>,
        frame: Frame,
        exclude: String?,
    ): List<List<Map<String, Any?>>> {
        var candidates = alts.filter { isObjectShape(it) }
        if (candidates.isEmpty()) return candidates
        val tried = HashSet<String>()
        for (round in 0 until 4) {
            if (candidates.size <= 1) break
            val key = discriminator(candidates, tried) ?: break
            tried.add(key)
            if (key == exclude) continue
            val narrowed = if (frame.scalars.containsKey(key)) {
                val given = instanceScalar(frame, key)
                candidates.filter { alt ->
                    SchemaDocument.pinnedValues(properties(alt)[key] ?: return@filter false)
                        .any { scalarText(it).isNotEmpty() && samePlain(it, given) }
                }
            } else {
                candidates.filter { key !in required(it) }
            }
            if (narrowed.isNotEmpty()) candidates = narrowed
        }
        return candidates
    }

    private fun discriminator(candidates: List<List<Map<String, Any?>>>, tried: Set<String>): String? {
        val first = properties(candidates.first())
        val ordered = PREFERRED_DISCRIMINATORS.filter { it in first } + first.keys
        for (key in ordered) {
            if (key in tried) continue
            if (candidates.all { alt ->
                    properties(alt)[key]?.let { SchemaDocument.pinnedValues(it).isNotEmpty() } == true
                }) return key
        }
        return null
    }

    private fun instanceScalar(frame: Frame, key: String): Any? {
        val value = frame.scalars[key]
        if (frame.parent == null && key == "type" && value is String) return typeAliases[value] ?: value
        return value
    }

    /** Schemas of the container [frames] ends with. */
    private fun containerSchemas(frames: List<Frame>): List<Map<String, Any?>> {
        var schemas = roots.mapNotNull { doc.lookup(it) }
        for (i in 0 until frames.size - 1) {
            val frame = frames[i]
            val segment = frames[i + 1].segment ?: return emptyList()
            schemas = if (frame.isObject) childOfObject(schemas, frame, segment, null) else childOfArray(schemas)
            if (schemas.isEmpty()) return schemas
        }
        return schemas
    }

    private fun childOfObject(
        schemas: List<Map<String, Any?>>,
        frame: Frame,
        key: String,
        exclude: String?,
    ): List<Map<String, Any?>> {
        val out = IdentityHashMap<Map<String, Any?>, Unit>()
        val alts = discriminate(alternativesOf(schemas), frame, exclude)
        for (alt in alts) {
            val rule = properties(alt)[key]
            if (rule != null) {
                out[rule] = Unit
                continue
            }
            for (part in alt) obj(part["additionalProperties"])?.let { out[doc.deref(it)] = Unit }
        }
        return out.keys.toList()
    }

    private fun childOfArray(schemas: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val out = IdentityHashMap<Map<String, Any?>, Unit>()
        for (alt in alternativesOf(schemas)) {
            if (!isArrayShape(alt)) continue
            for (part in alt) obj(part["items"])?.let { out[doc.deref(it)] = Unit }
        }
        return out.keys.toList()
    }

    private fun propertySchemas(frames: List<Frame>, key: String): List<Map<String, Any?>> =
        childOfObject(containerSchemas(frames), frames.last(), key, key)

    private fun elementSchemas(frames: List<Frame>): List<Map<String, Any?>> = childOfArray(containerSchemas(frames))

    // ---- suggestions

    private fun keySuggestions(text: String, capture: Capture, frame: Frame): List<Suggestion> {
        val alts = discriminate(alternativesOf(containerSchemas(capture.frames)), frame, null)
        if (alts.isEmpty()) return emptyList()

        var keys = LinkedHashSet<String>()
        val required = LinkedHashSet<String>()
        val rules = LinkedHashMap<String, MutableList<Map<String, Any?>>>()
        for (alt in alts) {
            for ((key, rule) in properties(alt)) {
                keys.add(key)
                rules.getOrPut(key) { ArrayList() }.add(rule)
            }
        }
        val discriminator = if (alts.size > 1) discriminator(alts, emptySet()) else null
        if (discriminator != null && !frame.scalars.containsKey(discriminator)) {
            // no variant chosen yet: the discriminator first, then what every variant accepts
            val common = keys.filterTo(LinkedHashSet()) { key -> alts.all { key in properties(it) } }
            keys = LinkedHashSet<String>().apply {
                add(discriminator)
                addAll(common)
            }
            required.add(discriminator)
        }
        for (alt in alts) required.addAll(required(alt).filter { it in keys })

        val ordered = required.filter { it in keys } +
            keys.filter { it !in required }.sortedWith(String.CASE_INSENSITIVE_ORDER)
        val following = nextSignificant(text, capture.end)
        val hasColon = following == ':'
        val needsComma = following == '"'
        val out = ArrayList<Suggestion>()
        for (key in ordered) {
            if (key in frame.keys) continue
            val keyRules = rules[key] ?: emptyList()
            val quoted = JsonFormatter.quote(key)
            val detail = describe(keyRules) + if (key in required) " *" else ""
            if (hasColon) {
                out.add(suggestion(key, detail, quoted, capture, quoted.length, quoted.length, false, true))
                continue
            }
            val template = template(keyRules)
            val insert = "$quoted: ${template.text}" + if (needsComma) "," else ""
            val base = quoted.length + 2
            out.add(
                suggestion(
                    key, detail, insert, capture,
                    base + template.selectStart, base + template.selectEnd, template.chain, true,
                )
            )
        }
        return out
    }

    private fun valueSuggestions(
        text: String,
        capture: Capture,
        schemas: List<Map<String, Any?>>,
        present: Set<String>,
        documentTags: Map<String, List<String>>,
    ): List<Suggestion> {
        val choices = choices(schemas, documentTags)
        val needsComma = nextSignificant(text, capture.end) == '"'
        val comma = if (needsComma) "," else ""
        val out = ArrayList<Suggestion>()
        val seen = HashSet<String>()
        for (choice in choices) {
            val value = choice.value
            if (capture.inString && value !is String) continue
            if (value is String && value in present) continue
            val insert = when {
                choice.snippet != null -> choice.snippet
                value is String -> JsonFormatter.quote(value)
                else -> scalarText(value)
            }
            if (!seen.add(insert)) continue
            val caretAt = if (choice.snippet != null) 1 else insert.length
            out.add(suggestion(choice.label, choice.detail, insert + comma, capture, caretAt, caretAt, false, false))
        }
        return out
    }

    private class Choice(val label: String, val detail: String, val value: Any?, val snippet: String? = null)

    private fun choices(schemas: List<Map<String, Any?>>, documentTags: Map<String, List<String>>): List<Choice> {
        val out = ArrayList<Choice>()
        var objects = false
        var arrays = false
        for (alt in alternativesOf(schemas)) {
            for (part in alt) {
                if (part.containsKey("const")) out.add(Choice(scalarText(part["const"]), "const", part["const"]))
                for (value in list(part["enum"])) out.add(Choice(scalarText(value), "enum", value))
                val types = SchemaDocument.types(part["type"])
                if ("boolean" in types) {
                    out.add(Choice("true", "boolean", true))
                    out.add(Choice("false", "boolean", false))
                }
                if ("null" in types) out.add(Choice("null", "null", null))
                if ("object" in types) objects = true
                if ("array" in types) arrays = true
                for (value in list(part["examples"])) out.add(Choice(scalarText(value), "example", value))
                (part["x-tag-reference"] as? String)?.let { kind ->
                    val tags = LinkedHashSet<String>()
                    builtinTags[kind]?.let { tags.addAll(it) }
                    documentTags[kind]?.let { tags.addAll(it) }
                    for (tag in tags) out.add(Choice(tag, "tag", tag))
                }
            }
        }
        if (objects) out.add(Choice("{}", "object", null, "{}"))
        if (arrays) out.add(Choice("[]", "array", null, "[]"))
        return out
    }

    private class Template(val text: String, val selectStart: Int, val selectEnd: Int, val chain: Boolean)

    private fun template(rules: List<Map<String, Any?>>): Template {
        val alts = alternativesOf(rules)
        var first: String? = null
        for (alt in alts) {
            for (part in alt) {
                first = when {
                    part.containsKey("const") -> kindOf(part["const"])
                    part.containsKey("enum") -> list(part["enum"]).firstOrNull()?.let { kindOf(it) }
                    else -> SchemaDocument.types(part["type"]).firstOrNull()
                } ?: continue
                break
            }
            if (first != null) break
        }
        return when (first) {
            "string" -> Template("\"\"", 1, 1, choices(rules, emptyMap()).any { it.value is String })
            "integer", "number" -> Template("0", 0, 1, false)
            "boolean" -> Template("false", 0, 5, false)
            "object" -> Template("{}", 1, 1, false)
            "array" -> Template("[]", 1, 1, false)
            "null" -> Template("null", 0, 4, false)
            else -> Template("", 0, 0, false)
        }
    }

    private fun kindOf(value: Any?): String = when (value) {
        is String -> "string"
        is Boolean -> "boolean"
        is Number -> "number"
        null -> "null"
        else -> "string"
    }

    private fun describe(rules: List<Map<String, Any?>>): String {
        val names = LinkedHashSet<String>()
        for (alt in alternativesOf(rules)) {
            for (part in alt) {
                when {
                    part.containsKey("const") || part.containsKey("enum") -> names.add("enum")
                    else -> names.addAll(SchemaDocument.types(part["type"]))
                }
            }
        }
        return names.joinToString(" | ")
    }

    private fun suggestion(
        label: String,
        detail: String,
        insert: String,
        capture: Capture,
        selectStart: Int,
        selectEnd: Int,
        chain: Boolean,
        isKey: Boolean,
    ): Suggestion {
        val caret = capture.start + (if (capture.inString) 1 else 0) + capture.prefix.length
        return Suggestion(
            label, detail, insert,
            before = caret - capture.start,
            after = capture.end - caret,
            selectStart = selectStart,
            selectEnd = selectEnd,
            chain = chain,
            isKey = isKey,
        )
    }

    /** As built while nothing is typed; alphabetical once a prefix narrows the list, prefix matches first. */
    private fun filter(items: List<Suggestion>, prefix: String): List<Suggestion> {
        if (prefix.isEmpty()) return items.take(MAX_SUGGESTIONS)
        val byLabel = compareBy<Suggestion, String>(String.CASE_INSENSITIVE_ORDER) { it.label }
        val starts = items.filter { it.label.startsWith(prefix, ignoreCase = true) }.sortedWith(byLabel)
        val contains = items.filter {
            !it.label.startsWith(prefix, ignoreCase = true) && it.label.contains(prefix, ignoreCase = true)
        }.sortedWith(byLabel)
        return (starts + contains).take(MAX_SUGGESTIONS)
    }

    private fun nextSignificant(text: String, from: Int): Char? {
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') return c
            i++
        }
        return null
    }

    private fun samePlain(option: Any?, given: Any?): Boolean = when (option) {
        is String -> given is String && option == given
        is Boolean -> given is Boolean && option == given
        is Number -> given is Number && SchemaDocument.sameNumber(option.toDouble(), given.toDouble())
        null -> given == null
        else -> false
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-' || c == '+' || c == '.'

    companion object {
        private const val MAX_ALTERNATIVES = 256
        private const val MAX_SUGGESTIONS = 200

        private val PREFERRED_DISCRIMINATORS = listOf("type", "action")

        /** Arrays whose object elements define tags, by the x-tag-reference kind the tags answer. */
        private val DEFINITION_ARRAYS = mapOf(
            "outbounds" to "outbound",
            "endpoints" to "outbound",
            "inbounds" to "inbound",
            "servers" to "dns_server",
            "rule_set" to "rule_set",
            "certificate_providers" to "certificate_provider",
            "http_clients" to "http_client",
            "network_namespaces" to "network_namespace",
        )
    }
}
