package app.rawline.mobile.bg.carrier

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.rawline.mobile.ktx.app
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The MAX binding, as the original MRTT app keeps it: the token from the web client and the group's ID, nothing else
 * of the account. It stays outside profiles, exports, backups and logs, shared with :bg like the VK binding.
 */
object MaxAccount {
    data class Binding(val token: String, val group: String)
    private const val ALIAS = "Rawline.max.account"
    private fun storage() = AtomicFile(File(app.noBackupFilesDir,"max-account"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun current(): Binding? = runCatching {
        val bytes = storage().readFully()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
        Binding(json.getString("token"),json.getString("group"))
    }.getOrNull()
    @Synchronized fun save(binding: Binding) {
        require(validToken(binding.token) && validGroup(binding.group))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val bytes = cipher.iv + cipher.doFinal(JSONObject().put("token",binding.token).put("group",binding.group).toString().toByteArray(Charsets.UTF_8))
        val file = storage(); val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch(error: Exception) { file.failWrite(output); throw error }
    }
    @Synchronized fun clear() { storage().delete() }

    private val GROUP_ID = Regex("-?[0-9]{1,64}")
    private val GROUP_PARAMS = setOf("chat_id","chatId","chat","chat_id_str")
    fun validGroup(value: String) = value.length<=64 && GROUP_ID.matches(value)
    fun validToken(value: String) = value.length in 32..4096 && value.none(Char::isWhitespace)
    fun isMaxUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https",ignoreCase=true) && uri.rawUserInfo==null && (uri.port==-1 || uri.port==443) &&
            (host=="max.ru" || host.endsWith(".max.ru"))
    }
    fun groupIdFromUrl(url: String): String? {
        if(!isMaxUrl(url)) return null
        val uri = URI(url)
        val segments = uri.path.orEmpty().trim('/').split('/')
        val candidate = when(segments.firstOrNull()) { "chat","chats","c" -> segments.getOrNull(1); else -> segments.firstOrNull() }
        if(candidate!=null && validGroup(candidate)) return candidate
        return uri.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { parameter ->
            val pair = parameter.split('=',limit=2)
            if(pair.size!=2 || pair[0] !in GROUP_PARAMS) return@firstNotNullOfOrNull null
            runCatching { URLDecoder.decode(pair[1],"UTF-8") }.getOrNull()?.takeIf(::validGroup)
        }
    }
}
