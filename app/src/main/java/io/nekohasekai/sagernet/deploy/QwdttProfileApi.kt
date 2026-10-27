package app.qthrone.mobile.deploy

import app.qthrone.mobile.outbound.types.Qwdtt
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The qWDTT server's profile API, as the original app's profile list uses it: plain HTTP on the
 * DTLS port, each request signed with the access password over a one-time server nonce, so a
 * subscriber sees and frees the device slots of their own password without the admin token.
 */
object QwdttProfileApi {
    class Status(val maxDevices: Int, val boundDevices: Int, val activeDevices: Int, val currentBound: Boolean, val expiresAt: Long)

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).writeTimeout(5, TimeUnit.SECONDS).build()
    }

    private fun endpoint(profile: Qwdtt): String {
        val host = profile.server.trim().let { if (it.contains(':') && !it.startsWith('[')) "[$it]" else it }
        return "http://$host:${profile.serverPort}/api/profile"
    }

    private fun post(url: String, form: Map<String, String>): JSONObject? {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        client.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
            if (response.code != 200) return null
            return runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
        }
    }

    internal fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** The signed form of [action] for [deviceId]; an empty [deviceId] on unbind frees every device of the password. */
    internal fun proof(action: String, password: String, deviceId: String, nonce: String): Map<String, String> {
        val keyId = hex(MessageDigest.getInstance("SHA-256").digest("WDTT-PROFILE-ID-v1\u0000$password".toByteArray()))
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password.toByteArray(), "HmacSHA256")) }
        val signature = hex(mac.doFinal("$action\n$deviceId\n$nonce".toByteArray()))
        return mapOf("device_id" to deviceId, "nonce" to nonce, "key_id" to keyId, "proof" to signature)
    }

    private fun signed(profile: Qwdtt, action: String, deviceId: String): JSONObject? {
        val base = endpoint(profile)
        val nonce = post("$base/challenge", emptyMap())?.optString("nonce")?.takeIf { it.isNotBlank() } ?: return null
        return post("$base/$action", proof(action, profile.password, deviceId, nonce))
    }

    /** Device slots of the profile's password; null when the server has no profile API or refuses the password. */
    fun status(profile: Qwdtt): Status? = runCatching { signed(profile, "status", profile.deviceId) }.getOrNull()?.let {
        Status(it.optInt("max_devices", 1), it.optInt("bound_devices"), it.optInt("active_devices"),
            it.optBoolean("is_current_bound"), it.optLong("expires_at"))
    }

    /**
     * After the user deleted [deleted] by hand: frees this phone's slot of each qWDTT password that no remaining
     * profile uses, so a deleted key no longer holds a device on the server. Returns how many were freed.
     */
    fun releaseDeleted(deleted: List<app.qthrone.mobile.database.ProxyEntity>): Int {
        fun key(q: Qwdtt) = listOf(q.server, q.serverPort.toString(), q.password, q.deviceId)
        val gone = deleted.mapNotNull { (runCatching { it.outbound }.getOrNull() as? Qwdtt)?.takeIf { q -> q.type == "qwdtt" } }.distinctBy(::key)
        if (gone.isEmpty()) return 0
        val kept = app.qthrone.mobile.database.SagerDatabase.proxyDao.getAll().filter { it.type == "qwdtt" }
            .mapNotNull { runCatching { it.outbound as? Qwdtt }.getOrNull() }.map(::key).toSet()
        return gone.filter { key(it) !in kept }.count(::unbind)
    }

    /** Frees this phone's slot on the server; true when the server confirmed it. */
    fun unbind(profile: Qwdtt): Boolean = runCatching { signed(profile, "unbind", profile.deviceId)?.optBoolean("success") == true }.getOrDefault(false)
}
