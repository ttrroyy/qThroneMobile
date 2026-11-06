package app.rawline.mobile.bg.carrier

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.rawline.mobile.ktx.app
import app.rawline.mobile.database.SagerDatabase
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.database.GroupRepo
import app.rawline.mobile.outbound.types.Qwdtt
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Account credentials stay outside profiles, exports, backups and logs, shared with :bg. */
object VkRelayAccount {
    const val DEFAULT_SOURCE = "vkRelayDefaultHashSource"
    private const val ALIAS = "Rawline.vk.relay"
    private val hashCreationLock = Any()
    private fun storage() = AtomicFile(File(app.noBackupFilesDir,"vk-relay-account"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized private fun read(): JSONObject = runCatching {
        val bytes = storage().readFully()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
    }.getOrElse { JSONObject() }
    @Synchronized private fun write(json: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val bytes = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val file = storage(); val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch(error: Exception) { file.failWrite(output); throw error }
    }
    @Synchronized private fun writeCurrent(session: JSONObject) {
        check(read().optJSONObject("cookies")?.toString()==session.optJSONObject("cookies")?.toString()) { "Привязка VK была изменена" }
        write(session)
    }
    fun linked() = read().optJSONObject("cookies")?.toString()?.contains("remixsid=") == true
    fun needsRelink() = read().optString("authClient") != "qwdtt-account-turn"
    fun sessionCookies(): Map<String,String> {
        val cookies=read().optJSONObject("cookies") ?: return emptyMap()
        return cookies.keys().asSequence().associateWith { cookies.optString(it) }
    }
    fun sessionUserAgent() = read().optString("userAgent")
    @Synchronized fun linkSession(cookies: Map<String,String>, userAgent: String) {
        require(cookies.values.any { value -> value.split(';').any { it.trim().startsWith("remixsid=") && it.substringAfter('=').isNotBlank() } })
        val previous=read()
        val oldCookies=previous.optJSONObject("cookies")
        val sameSession=oldCookies!=null && cookies.any { (host,value) -> value==oldCookies.optString(host) && value.contains("remixsid=") }
        val session=if(sameSession) previous else JSONObject()
        session.put("cookies",JSONObject(cookies)).put("userAgent",userAgent).put("authClient","qwdtt-account-turn")
        write(session)
    }
    fun userId() = read().optString("user")
    fun displayName() = read().optString("name").ifBlank { "VK ID: ${userId()}" }
    fun avatarUrl() = read().optString("avatar")
    data class UiInfo(val linked: Boolean,val needsRelink: Boolean,val displayName: String,val hashes: Int)
    fun uiInfo(): UiInfo {
        val session=read()
        val stored=session.optJSONObject("cookies")
        val id=session.optString("user").ifBlank { VkRelayOAuth.userIdFromCookies(stored?.keys()?.asSequence()?.map { stored.optString(it) }?.toList().orEmpty()) }
        return UiInfo(linked(),needsRelink(),id,session.optJSONArray("hashes")?.length() ?: 0)
    }
    fun refreshIdentity() {
        val session = read()
        if(session.optString("token").isBlank() || session.optString("name").isNotBlank()) return
        runCatching {
            val request = Request.Builder().url("https://api.vk.ru/method/users.get")
                .post(FormBody.Builder().add("access_token",session.getString("token")).add("v","5.199").add("fields","photo_100").build()).build()
            val person = client.newCall(request).execute().use { response ->
                check(response.isSuccessful)
                JSONObject(response.body?.string().orEmpty()).getJSONArray("response").getJSONObject(0)
            }
            synchronized(this) {
                val latest=read()
                // Never resurrect credentials if the account was unlinked during the request.
                if(latest.optString("token")==session.optString("token")) {
                    latest.put("name",listOf(person.optString("first_name"),person.optString("last_name")).filter { it.isNotBlank() }.joinToString(" "))
                    latest.put("avatar",person.optString("photo_100"))
                    write(latest)
                }
            }
        }
    }
    suspend fun unlinkWithManualHashes() {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val groups = mutableSetOf<Long>()
            val db = SagerDatabase.instance
            db.runInTransaction {
                for(profile in SagerDatabase.proxyDao.getAll()) {
                    val outbound = profile.outbound as? Qwdtt ?: continue
                    outbound.hashSource = "manual"
                    outbound.hashSourceChosen = true
                    profile.putOutbound(outbound)
                    SagerDatabase.proxyDao.updateOutbound(profile.id,profile.type,profile.name,profile.outboundJson)
                    groups.add(profile.groupId)
                }
            }
            DataStore.configurationStore.putString(DEFAULT_SOURCE,"manual")
            unlink()
            for(group in groups) GroupRepo.postReload(group)
        }
    }
    // The login screen signs in on m.vk.ru; oauth.vk.com would not see that session and ask to sign in again.
    fun authorizeUrl(state: String): String = "https://oauth.vk.ru/authorize?client_id=6287487&display=mobile&redirect_uri=https%3A%2F%2Foauth.vk.ru%2Fblank.html&response_type=token&scope=messages&v=5.199&state=$state"
    fun link(token: String,user: String,expiresIn: Long,cookies: Map<String,String>,userAgent: String) {
        require(token.isNotBlank())
        require(cookies.values.any { value -> value.split(';').any { it.trim().startsWith("remixsid=") && it.substringAfter('=').isNotBlank() } })
        val previous = read()
        val account = JSONObject().put("token",token).put("user",user).put("cookies",JSONObject(cookies)).put("userAgent",userAgent)
            .put("authClient","6287487")
            .put("expires",if(expiresIn > 0) System.currentTimeMillis()+expiresIn*1000 else Long.MAX_VALUE)
        if(user.isNotBlank() && previous.optString("user") == user && previous.optString("authClient") == "6287487") {
            previous.optJSONArray("hashes")?.let { account.put("hashes",it) }
        }
        write(account)
        refreshIdentity()
    }
    @Synchronized fun unlink() { storage().delete() }
    private val client = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS)
        .readTimeout(20,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS).build()
    // The background process reads an encrypted snapshot, never the UI process's CookieManager.
    private fun refreshToken(session: JSONObject): String? {
        val cookies = session.optJSONObject("cookies") ?: return null
        val state = java.util.UUID.randomUUID().toString()
        val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        var url = authorizeUrl(state)
        val fresh = LinkedHashMap<String,String>()
        // qWDTT avoids non-local returns from inline loops here because of ART verifier failures.
        for(step in 0 until 12) {
            val uri = java.net.URI(url)
            if(!VkRelayOAuth.trustedHttps(uri)) return null
            val request = Request.Builder().url(url)
                .header("User-Agent",session.optString("userAgent"))
                .header("Accept","text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            val saved = VkRelayRequests.cookieHeader(cookies.keys().asSequence().associateWith { cookies.optString(it) })
            val header = (saved.split("; ").filter { it.isNotBlank() && it.substringBefore('=') !in fresh } + fresh.map { "${it.key}=${it.value}" }).joinToString("; ")
            if(header.isNotBlank()) request.header("Cookie",header)
            val next = http.newCall(request.build()).execute().use { response ->
                for(cookie in response.headers("Set-Cookie")) cookie.substringBefore(';').let { pair ->
                    if(pair.contains('=')) fresh[pair.substringBefore('=').trim()] = pair.substringAfter('=')
                }
                val location = response.header("Location")
                if(location != null) response.request.url.resolve(location)?.toString()
                else if(response.isSuccessful) VkRelayOAuth.nextAuthorizeUrl(response.body?.string().orEmpty())
                else null
            } ?: return null
            val fields = VkRelayOAuth.tokenFields(next,state)
            if(fields != null) {
                VkRelayOAuth.userId(next)?.let { session.put("user",it) }
                session.put("token",fields.first)
                session.put("expires",if(fields.second > 0) System.currentTimeMillis()+fields.second*1000 else Long.MAX_VALUE)
                writeCurrent(session)
                return fields.first
            }
            url = next
        }
        return null
    }
    fun tokenForCalls(interactive: Boolean=true): String = synchronized(hashCreationLock) {
        val session=read()
        check(session.optString("authClient")=="qwdtt-account-turn") { "Перепривяжите аккаунт VK" }
        tokenForCalls(session, interactive).also { check(it.isNotBlank()) { "Откройте привязку VK для подтверждения входа" } }
    }
    // Without [interactive] an expired session fails at once instead of waiting for a sign-in nobody sees (a ping).
    private fun tokenForCalls(session: JSONObject, interactive: Boolean=true): String = VkRelayOAuth.obtainToken(
        session.optString("token"),session.optLong("expires"),System.currentTimeMillis(),
        { refreshToken(session) }, { if(!interactive) null else VkTokenRequest.obtain()?.let { (token,expires) ->
            session.put("token",token).put("expires",if(expires>0) System.currentTimeMillis()+expires*1000 else Long.MAX_VALUE)
            writeCurrent(session)
            token
        } }
    ).orEmpty()

    const val MAX_HASHES = 30
    fun cachedHashes(): List<String> = read().optJSONArray("hashes")?.let { list -> (0 until list.length()).map(list::getString) }.orEmpty()
    /** Which created hashes a server's profiles use; kept here so they stay out of profiles, exports and share links. */
    fun selectionKey(type: String,server: String,port: Int) = "$type|$server:$port"
    fun selectedHashes(key: String): List<String> {
        val session=read()
        val created=session.optJSONArray("hashes")?.let { list -> (0 until list.length()).map(list::getString) }.orEmpty()
        return session.optJSONObject("selectedHashes")?.optJSONArray(key)?.let { list -> (0 until list.length()).map(list::getString) }.orEmpty().filter { it in created }
    }
    @Synchronized fun selectHashes(key: String,hashes: List<String>) {
        val session=read()
        session.put("selectedHashes",(session.optJSONObject("selectedHashes") ?: JSONObject()).put(key,org.json.JSONArray(hashes)))
        writeCurrent(session)
    }
    /**
     * Removes hashes and ends the VK calls behind them. A call VK refuses to end, or one created before call ids
     * were kept, is still removed here; returns how many were left running.
     */
    fun deleteHashes(drop: Collection<String>): Int = synchronized(hashCreationLock) {
        val session=read()
        val ids=session.optJSONObject("callIds") ?: org.json.JSONObject()
        val kept=org.json.JSONArray()
        var unfinished=0
        var token: String?=null
        for(hash in cachedHashes()) {
            if(hash !in drop) { kept.put(hash); continue }
            val id=ids.optString(hash); ids.remove(hash)
            if(id.isEmpty()) { unfinished++; continue }
            val current=token ?: runCatching { tokenForCalls(session) }.getOrDefault("").also { token=it }
            val ended=current.isNotEmpty() && runCatching {
                client.newCall(VkRelayRequests.finishCall(current,id)).execute().use { response ->
                    response.isSuccessful && JSONObject(response.body?.string().orEmpty()).has("response")
                }
            }.getOrDefault(false)
            if(!ended) unfinished++
        }
        session.put("hashes",kept).put("callIds",ids)
        writeCurrent(session)
        unfinished
    }
    fun hashes(count: Int, interactive: Boolean=true, onProgress: (List<String>) -> Unit={}): List<String> {
      synchronized(hashCreationLock) {
        require(count in 1..MAX_HASHES)
        val session = read()
        check(session.optString("authClient") == "qwdtt-account-turn") { "Перепривяжите аккаунт VK: способ авторизации обновлён" }
        val cached = session.optJSONArray("hashes")
        if(cached != null && cached.length() >= count) return (0 until count).map { cached.getString(it) }
        var token = tokenForCalls(session, interactive)
        check(token.isNotBlank()) { "Откройте привязку VK для подтверждения входа" }
        check(session.optLong("retryAfter") <= System.currentTimeMillis()) { "VK ограничил запросы. Повторите позже или используйте ручные хеши" }
        val hashes = mutableListOf<String>()
        if(cached != null) for(i in 0 until cached.length()) hashes.add(cached.getString(i))
        var refreshedToken=false
        while(hashes.size < count) {
            if(hashes.isNotEmpty()) Thread.sleep(2000)
            val request = VkRelayRequests.startCall(token)
            val json = client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Не удалось создать звонок VK: HTTP ${response.code}" }
                JSONObject(response.body?.string() ?: error("Пустой ответ VK"))
            }
            val vkError = json.optJSONObject("error")
            if(vkError != null) {
                val code = vkError.optInt("error_code")
                if(code in listOf(4,5,27,28)) session.put("expires",0L)
                if(code in listOf(6,9,14,29)) session.put("retryAfter",System.currentTimeMillis()+60*60*1000L)
                writeCurrent(session)
                if(code in listOf(4,5,27,28) && !refreshedToken) {
                    refreshedToken=true
                    token=tokenForCalls(session, interactive)
                    check(token.isNotBlank()) { "Откройте привязку VK для подтверждения входа" }
                    continue
                }
                error("Не удалось создать звонок VK: код ${vkError.optInt("error_code")}")
            }
            val call = json.optJSONObject("response") ?: error("VK не вернул ссылку звонка")
            val hash = call.optString("join_link").substringAfterLast('/').substringBefore('?').substringBefore('#')
            check(hash.length >= 16 && hash.none { it.isWhitespace() || it == '|' || it == ',' }) { "VK вернул некорректный хеш" }
            hashes.add(hash)
            call.optString("call_id").takeIf { it.isNotEmpty() }?.let { id ->
                session.put("callIds",(session.optJSONObject("callIds") ?: JSONObject()).put(hash,id))
            }
            session.put("hashes",org.json.JSONArray(hashes)); writeCurrent(session); onProgress(hashes.toList())
        }
        return hashes.take(count)
      }
    }
}
