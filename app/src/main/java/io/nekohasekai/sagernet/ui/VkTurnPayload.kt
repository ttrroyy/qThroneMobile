package app.rawline.mobile.ui

import org.json.JSONObject
import org.json.JSONArray

/** Same private TURN_CREDS protocol as qWDTT; credentials never enter logs or profiles. */
internal object VkTurnPayload {
    fun encode(hash: String, json: String): String? = runCatching {
        require(hash.matches(Regex("[A-Za-z0-9_-]{16,512}")) && json.length<=12000)
        val input=JSONObject(json)
        val user=input.getString("username")
        val pass=input.getString("credential")
        val urls=input.getJSONArray("urls")
        require(user.isNotBlank() && pass.isNotBlank() && urls.length() in 1..16)
        val values=(0 until urls.length()).map { urls.getString(it) }
        require(values.all { it.startsWith("turn:") || it.startsWith("turns:") })
        val payload=JSONObject().put("u",user).put("p",pass).put("urls",JSONArray(values))
        hash+"|"+java.util.Base64.getEncoder().encodeToString(payload.toString().toByteArray(Charsets.UTF_8))
    }.getOrNull()
}
