package app.rawline.mobile.outbound.json

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * INPUT side only: parses JSON text through org.json and converts it into [JsonObject]/[JsonArray].
 * org.json output (`toString()`) must never be used for contract strings because it escapes `/`.
 */
object JsonInput {

    /** QString2QJsonObject (Utils.cpp:100-104): an empty object on any failure or when the text is not an object. */
    @JvmStatic
    fun parseObject(text: String): JsonObject = parseObjectOrNull(text) ?: JsonObject()

    @JvmStatic
    fun parseObjectOrNull(text: String): JsonObject? = try {
        fromOrgJson(JSONObject(text))
    } catch (e: Exception) {
        null
    }

    /** The top-level value of [text] (object, array, string, number, boolean or [JsonNull]); null when unparsable. */
    @JvmStatic
    fun parseValue(text: String): Any? = try {
        fromOrgJsonValue(JSONTokener(text).nextValue())
    } catch (e: Exception) {
        null
    }

    /** Keys are inserted in sorted order because a QJsonObject is a sorted map and the desktop iterates it as such. */
    @JvmStatic
    fun fromOrgJson(obj: JSONObject): JsonObject {
        val out = JsonObject()
        val keys = ArrayList<String>()
        val it = obj.keys()
        while (it.hasNext()) keys.add(it.next())
        keys.sort()
        for (key in keys) out[key] = fromOrgJsonValue(obj.opt(key))
        return out
    }

    @JvmStatic
    fun fromOrgJson(arr: JSONArray): JsonArray {
        val out = JsonArray()
        for (i in 0 until arr.length()) out.add(fromOrgJsonValue(arr.opt(i)))
        return out
    }

    @JvmStatic
    fun fromOrgJsonValue(value: Any?): Any = when {
        value == null || value === JSONObject.NULL -> JsonNull
        value is JSONObject -> fromOrgJson(value)
        value is JSONArray -> fromOrgJson(value)
        else -> JsonValues.normalize(value)
    }
}
