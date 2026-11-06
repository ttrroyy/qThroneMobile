package app.rawline.mobile.ktx

import com.google.gson.JsonParser
import moe.matsuri.nb4a.utils.JavaUtil.gson
import org.json.JSONObject

// JSON helper of the config export

fun JSONObject.toStringPretty(): String {
    return gson.toJson(JsonParser.parseString(this.toString()))
}
