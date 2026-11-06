package app.rawline.mobile.deploy

import android.util.Base64
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * The qWDTT server's admin API, as the original app's AdminApiClient uses it: HTTPS on the admin
 * port, the certificate pinned to the fingerprint the installer printed, the admin token as Bearer.
 */
class QwdttAdmin(private val server: DeployServer) {
    /** One access password with its limits and usage, the server's /admin/passwords entry. */
    class Access(
        val password: String, val label: String, val vkHash: String, val maxDevices: Int,
        val deviceIds: List<String>, val expiresAt: Long, val deactivated: Boolean,
        val downBytes: Long, val upBytes: Long, val activeDevices: Int,
    ) {
        val hashes: List<String> get() = vkHash.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val title: String get() = label.ifBlank { password }
    }

    val available: Boolean get() = server["installed"] == "true" && server["adminPin"].startsWith("sha256/") && server["adminToken"].isNotBlank()

    private val client: OkHttpClient by lazy {
        val pin = server["adminPin"]
        require(pin.startsWith("sha256/") && pin.length > 10) { "Отпечаток админ-панели не сохранён. Обновите сервер." }
        val expected = Base64.decode(pin.removePrefix("sha256/"), Base64.DEFAULT)
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                throw CertificateException("Client certificates are not accepted")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val certificate = chain?.firstOrNull() ?: throw CertificateException("Server certificate is missing")
                val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                if (!MessageDigest.isEqual(expected, actual)) throw CertificateException("Сертификат сервера не совпадает с сохранённым")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        OkHttpClient.Builder().sslSocketFactory(context.socketFactory, trust).hostnameVerifier { _, _ -> true }
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).writeTimeout(8, TimeUnit.SECONDS).build()
    }

    private fun url(path: String): String {
        val host = server["host"].trim().let { if (it.contains(':') && !it.startsWith('[')) "[$it]" else it }
        return "https://$host:${server.port("adminPort")}$path"
    }

    private fun call(path: String, form: Map<String, String>?): JSONObject {
        val request = Request.Builder().url(url(path)).header("Authorization", "Bearer " + server["adminToken"]).apply {
            if (form == null) get() else post(FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build())
        }.build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val error = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                throw IllegalStateException(error.ifBlank { "HTTP ${response.code}" })
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun parse(o: JSONObject) = Access(
        password = o.optString("password"), label = o.optString("label"), vkHash = o.optString("vk_hash"),
        maxDevices = o.optInt("max_devices", 1),
        deviceIds = o.optJSONArray("device_ids")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
        expiresAt = o.optLong("expires_at", 0L), deactivated = o.optBoolean("is_deactivated", false),
        downBytes = o.optLong("down_bytes", 0L), upBytes = o.optLong("up_bytes", 0L), activeDevices = o.optInt("active_devices", 0),
    )

    fun list(): List<Access> = subscriptions(call("/admin/passwords", null).optJSONArray("passwords")
        ?.let { a -> (0 until a.length()).map { parse(a.getJSONObject(it)) } }.orEmpty())

    /** Subscriptions only: the deploy's own password is the server owner's unlimited access, not a user. */
    internal fun subscriptions(all: List<Access>) = all.filterNot { it.password.isNotEmpty() && it.password == server["password"] }

    fun create(label: String, hashes: List<String>, days: Int, maxDevices: Int): Access = parse(call("/admin/passwords",
        buildMap {
            put("vk_hash", hashes.joinToString(",")); put("days", days.toString()); put("max_devices", maxDevices.toString())
            if (label.isNotBlank()) put("label", label)
        }))

    /** Only the given fields change; null leaves a field as it is on the server. */
    fun update(password: String, label: String?, hashes: List<String>?, maxDevices: Int?, days: Int?): Access = parse(call("/admin/passwords/update",
        buildMap {
            put("password", password)
            label?.let { put("label", it) }; hashes?.let { put("vk_hash", it.joinToString(",")) }
            maxDevices?.let { put("max_devices", it.toString()) }; days?.let { put("days", it.toString()) }
        }))

    fun setActive(password: String, active: Boolean): Access =
        parse(call(if (active) "/admin/passwords/activate" else "/admin/passwords/deactivate", mapOf("password" to password)))

    fun unbind(password: String, deviceId: String): Access =
        parse(call("/admin/passwords/unbind-device", mapOf("password" to password, "device_id" to deviceId)))

    fun delete(password: String) { call("/admin/passwords/delete", mapOf("password" to password)) }

    /** The links of one access: qwdtt:// like the original admin tab, and the legacy wdtt:// form. */
    fun links(access: Access): Pair<String, String> = links(server, access.title, access.password, access.hashes)

    companion object {
        fun links(server: DeployServer, name: String, password: String, hashes: List<String>): Pair<String, String> {
            val host = server["host"].trim()
            val peer = server.port("peerPort")
            val qwdtt = DeployConfig.link(org.json.JSONObject().put("type", "qwdtt").put("name", name).put("server", host)
                .put("server_port", peer).put("password", password).put("hashes", org.json.JSONArray(hashes)))
            // wdtt://<server>:<dtls_port>:<wg_port>:<local_port>:<password>:<vk_hash>
            val wdtt = "wdtt://$host:$peer:${server["wgPort"].ifBlank { "56001" }}:9000:$password:${hashes.joinToString(",")}"
            return qwdtt to wdtt
        }
    }
}
