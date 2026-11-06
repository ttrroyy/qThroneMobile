package app.rawline.mobile.outbound.json

import app.rawline.mobile.outbound.QtStrings
import java.math.BigDecimal
import java.math.BigInteger

/** Explicit JSON null; a [JsonObject] or [JsonArray] never stores a Kotlin null. */
object JsonNull {
    override fun toString(): String = "null"
}

/**
 * Insertion-ordered JSON object whose values are normalised to String, Boolean, Long, Double,
 * [JsonObject], [JsonArray] or [JsonNull]. Serialisation always sorts keys (see [JsonWriter]).
 * The typed getters reproduce QJsonValue's conversions: a value of the wrong JSON type yields
 * the default ("" / 0 / false / empty container), never a coercion.
 */
class JsonObject : Iterable<Map.Entry<String, Any>> {
    private val map = LinkedHashMap<String, Any>()

    val size: Int get() = map.size
    fun isEmpty(): Boolean = map.isEmpty()
    fun isNotEmpty(): Boolean = map.isNotEmpty()
    fun contains(key: String): Boolean = map.containsKey(key)
    operator fun get(key: String): Any? = map[key]
    operator fun set(key: String, value: Any?) {
        map[key] = JsonValues.normalize(value)
    }

    fun remove(key: String): Any? = map.remove(key)
    fun keys(): List<String> = ArrayList(map.keys)

    /** QJsonObject iterates in sorted (UTF-16 code unit) order; use this wherever the desktop relies on that order. */
    fun sortedKeys(): List<String> = map.keys.sorted()

    /** mergeJsonObjects (utils.cpp:16-22): shallow overwrite. */
    fun merge(other: JsonObject): JsonObject {
        for ((k, v) in other.map) map[k] = v
        return this
    }

    fun copy(): JsonObject {
        val c = JsonObject()
        for ((k, v) in map) c.map[k] = JsonValues.deepCopy(v)
        return c
    }

    override fun iterator(): Iterator<Map.Entry<String, Any>> = map.entries.iterator()

    fun string(key: String): String = JsonValues.toStringValue(map[key])
    fun int(key: String): Int = JsonValues.toInt(map[key])
    fun integer(key: String): Long = JsonValues.toInteger(map[key])
    fun double(key: String): Double = JsonValues.toDouble(map[key])
    fun bool(key: String): Boolean = JsonValues.toBool(map[key])
    fun obj(key: String): JsonObject = map[key] as? JsonObject ?: JsonObject()
    fun array(key: String): JsonArray = map[key] as? JsonArray ?: JsonArray()
    fun isString(key: String): Boolean = map[key] is String
    fun isObject(key: String): Boolean = map[key] is JsonObject
    fun isArray(key: String): Boolean = map[key] is JsonArray
    fun isBool(key: String): Boolean = map[key] is Boolean
    fun isNumber(key: String): Boolean = map[key].let { it is Long || it is Double }
    fun isNull(key: String): Boolean = map[key] === JsonNull

    /** QJsonValue::toVariant().toString(). */
    fun variantString(key: String): String = JsonValues.variantToString(map[key])

    /** QJsonValue::toVariant().toLongLong(). */
    fun variantLong(key: String): Long = JsonValues.variantToLong(map[key])

    /** QJsonValue::toVariant().toInt(). */
    fun variantInt(key: String): Int = JsonValues.variantToInt(map[key])

    fun toCompact(): String = JsonWriter.write(this)
    override fun toString(): String = toCompact()
    override fun equals(other: Any?): Boolean = other is JsonObject && other.map == map
    override fun hashCode(): Int = map.hashCode()
}

class JsonArray : Iterable<Any> {
    private val list = ArrayList<Any>()

    val size: Int get() = list.size
    fun isEmpty(): Boolean = list.isEmpty()
    fun isNotEmpty(): Boolean = list.isNotEmpty()
    fun add(value: Any?): JsonArray {
        list.add(JsonValues.normalize(value))
        return this
    }

    operator fun get(index: Int): Any = list[index]

    /** QJsonArray2QListString (Utils.cpp:135-140): non-string items become "". */
    fun strings(): MutableList<String> = list.mapTo(ArrayList()) { JsonValues.toStringValue(it) }

    fun copy(): JsonArray {
        val c = JsonArray()
        for (v in list) c.list.add(JsonValues.deepCopy(v))
        return c
    }

    override fun iterator(): Iterator<Any> = list.iterator()
    fun toCompact(): String = JsonWriter.write(this)
    override fun toString(): String = toCompact()
    override fun equals(other: Any?): Boolean = other is JsonArray && other.list == list
    override fun hashCode(): Int = list.hashCode()

    companion object {
        @JvmStatic
        fun of(vararg values: Any?): JsonArray = JsonArray().also { a -> values.forEach { a.add(it) } }
    }
}

fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JsonObject =
    JsonObject().also { o -> pairs.forEach { (k, v) -> o[k] = v } }

object JsonValues {
    private const val MAX_EXACT_DOUBLE = 9007199254740992.0 // 2^53

    fun normalize(value: Any?): Any = when (value) {
        null -> JsonNull
        is JsonNull, is String, is Boolean, is JsonObject, is JsonArray -> value
        is Long -> value
        is Int, is Short, is Byte -> (value as Number).toLong()
        is Double -> normalizeDouble(value)
        is Float -> normalizeDouble(value.toDouble())
        is BigInteger -> if (value.bitLength() < 64) value.toLong() else value.toDouble()
        is BigDecimal -> normalizeDouble(value.toDouble())
        else -> throw IllegalArgumentException("unsupported JSON value type ${value::class.java.name}")
    }

    // QJsonValue(double) keeps integral values within +-2^53 as an Integer (qjsonvalue.cpp, convertDoubleTo(v, &n, false)),
    // which is what makes 443.0 print as 443 and 1e16 print as 1e+16.
    fun normalizeDouble(d: Double): Any =
        if (!d.isNaN() && !d.isInfinite() && d == Math.rint(d) && Math.abs(d) <= MAX_EXACT_DOUBLE) d.toLong() else d

    fun toStringValue(v: Any?): String = v as? String ?: ""
    fun toBool(v: Any?): Boolean = v as? Boolean ?: false

    /** QJsonValue::toInt(): only whole numbers that fit an int, anything else is 0. */
    fun toInt(v: Any?): Int = when (v) {
        is Long -> if (v >= Int.MIN_VALUE && v <= Int.MAX_VALUE) v.toInt() else 0
        is Double -> if (v == Math.rint(v) && v >= Int.MIN_VALUE && v <= Int.MAX_VALUE) v.toInt() else 0
        else -> 0
    }

    /** QJsonValue::toInteger(): whole numbers that fit a qint64. */
    fun toInteger(v: Any?): Long = when (v) {
        is Long -> v
        is Double -> if (v == Math.rint(v) && v >= Long.MIN_VALUE.toDouble() && v < Long.MAX_VALUE.toDouble()) v.toLong() else 0L
        else -> 0L
    }

    fun toDouble(v: Any?): Double = when (v) {
        is Long -> v.toDouble()
        is Double -> v
        else -> 0.0
    }

    fun variantToString(v: Any?): String = when (v) {
        is String -> v
        is Boolean -> if (v) "true" else "false"
        is Long -> v.toString()
        is Double -> JsonWriter.formatDouble(v)
        else -> ""
    }

    fun variantToLong(v: Any?): Long = when (v) {
        is Long -> v
        is Double -> qRound64(v)
        is Boolean -> if (v) 1L else 0L
        is String -> QtStrings.toLong(v)
        else -> 0L
    }

    // QVariant::toInt() narrows the 64-bit conversion without a range check.
    fun variantToInt(v: Any?): Int = variantToLong(v).toInt()

    fun qRound64(d: Double): Long = if (d >= 0) (d + 0.5).toLong() else (d - 0.5).toLong()

    fun deepCopy(v: Any): Any = when (v) {
        is JsonObject -> v.copy()
        is JsonArray -> v.copy()
        else -> v
    }

    /** QListStr2QJsonArray (Utils.cpp:110-118): blank items dropped, empty array when nothing is left. */
    @JvmStatic
    fun stringArray(list: List<String>): JsonArray {
        val out = JsonArray()
        for (item in list) {
            if (item.isBlank()) continue
            out.add(item)
        }
        return out
    }

    /** jsonObjectToQStringList (utils.cpp:24-32): [k1, v1, k2, v2, ...] in QJsonObject (sorted) key order. */
    @JvmStatic
    fun objectToPairList(obj: JsonObject): MutableList<String> {
        val out = ArrayList<String>()
        for (key in obj.sortedKeys()) {
            out.add(key)
            out.add(obj.string(key))
        }
        return out
    }

    /** qStringListToJsonObject (utils.cpp:34-47): an odd-length list gives an empty object. */
    @JvmStatic
    fun pairListToObject(list: List<String>): JsonObject {
        val out = JsonObject()
        if (list.size % 2 != 0) return out
        var i = 0
        while (i < list.size) {
            out[list[i]] = list[i + 1]
            i += 2
        }
        return out
    }
}
