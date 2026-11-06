package app.rawline.mobile.group

import com.google.gson.JsonParser
import app.rawline.mobile.outbound.link.Base64Strict
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Offline interoperability codecs. Key material is public compatibility data, not user secrets.
 * Protocol references and redistribution notices: subscription_crypto/NOTICE.txt in Java resources.
 * This Kotlin implementation validates sizes and authentication before exposing any plaintext.
 */
internal object SubscriptionCrypto {
    const val MAX_LINK_CHARS = 128 * 1024
    private const val MAX_BODY_CHARS = 64 * 1024 * 1024
    private val data by lazy {
        val input = checkNotNull(javaClass.getResourceAsStream("/subscription_crypto/keys.json"))
        input.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
    }
    private val privateKeys = java.util.concurrent.ConcurrentHashMap<String, RSAPrivateKey>()

    data class Decoded(val content: String, val name: String = "")

    fun decrypt(provider: String, format: String, payload: String): Decoded {
        require(payload.length in 1..MAX_LINK_CHARS)
        return when {
            provider == "incy" && format == "crypt1" -> {
                val wire = base64(payload.trimEnd('/'))
                require(wire.size > 28)
                val key = base64(data.get("incyKey").asString)
                val clear = aesGcm(key, wire.copyOfRange(0, 12), wire.copyOfRange(12, wire.size))
                val json = JsonParser.parseString(utf8(clear)).asJsonObject
                require(json.get("v")?.asInt == 1)
                val url = json.get("url")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
                require(isHttpUrl(url))
                val name = json.get("n")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty().take(128)
                Decoded(url, name)
            }
            provider == "happ" && format == "crypt5" -> Decoded(crypt5(payload))
            provider == "happ" && format in listOf("crypt", "crypt2", "crypt3", "crypt4") -> {
                val ordinal = if (format == "crypt") 0 else format.last().digitToInt() - 1
                val cipher = base64(payload)
                val key = privateKey("legacy$ordinal", data.getAsJsonArray("happLegacy")[ordinal].asString)
                val block = (key.modulus.bitLength() + 7) / 8
                require(cipher.isNotEmpty() && cipher.size % block == 0 && cipher.size / block <= 256)
                val out = ByteArrayOutputStream()
                for (offset in cipher.indices step block) out.write(rsa(key, cipher.copyOfRange(offset, offset + block)))
                Decoded(utf8(out.toByteArray()))
            }
            else -> throw IllegalArgumentException("Unsupported encrypted subscription format")
        }
    }

    /** Happ response layer: base64 ciphertext, detached base64 Encrypt-Tag, ?key=key01..key90. */
    fun decryptResponse(url: String, body: String, encryptTag: String, originalUrl: String = ""): String {
        if (encryptTag.isBlank()) return body
        require(body.length <= MAX_BODY_CHARS && encryptTag.length <= 64)
        fun keyNames(address: String): List<String> = URI(address).rawQuery.orEmpty().split('&').mapNotNull { item ->
            val pair = item.split('=', limit = 2)
            if (pair.size == 2 && URLDecoder.decode(pair[0], "UTF-8") == "key") URLDecoder.decode(pair[1], "UTF-8") else null
        }
        // Redirects may add a key or omit the original query. An explicit final key takes precedence.
        val names = keyNames(url).ifEmpty { keyNames(originalUrl) }
        require(names.size == 1)
        val key = data.getAsJsonObject("happResponse").get(names.single())?.asString
            ?: throw IllegalArgumentException("Unsupported encrypted response key")
        val tag = base64(encryptTag)
        require(tag.size == 16)
        return utf8(aesGcm(key.toByteArray(Charsets.US_ASCII), "kkkkkkkkkkkk".toByteArray(Charsets.US_ASCII), base64(body) + tag))
    }

    fun isHttpUrl(text: String): Boolean = runCatching {
        val uri = URI(text)
        uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            text.none { it == '\r' || it == '\n' || it == '\u0000' }
    }.getOrDefault(false)

    private fun crypt5(payload: String): String {
        require(payload.all { it.code in 33..126 })
        val shuffled = payload.toByteArray(Charsets.US_ASCII).copyOf()
        for (offset in 0 until (shuffled.size - shuffled.size % 4) step 4) {
            val a = shuffled[offset]; val b = shuffled[offset + 1]
            shuffled[offset] = shuffled[offset + 2]; shuffled[offset + 1] = shuffled[offset + 3]
            shuffled[offset + 2] = a; shuffled[offset + 3] = b
        }
        require(shuffled.size >= 21)
        val marker = String(shuffled.copyOfRange(0, 4) + shuffled.copyOfRange(shuffled.size - 4, shuffled.size), Charsets.US_ASCII)
        val keyData = data.getAsJsonObject("happ5").get(marker)?.asString
            ?: throw IllegalArgumentException("Unsupported Happ key generation")
        val key = privateKey(marker, keyData)
        val body = shuffled.copyOfRange(4, shuffled.size - 4)
        // Some nonce bytes resemble a legacy header; try both layouts, accepting only an authenticated result.
        for (salted in listOf(body[12].toInt() !in 48..57, body[12].toInt() in 48..57)) {
            runCatching { crypt5Body(body, key, salted) }.getOrNull()?.let { return it }
        }
        throw IllegalArgumentException("Encrypted subscription authentication failed")
    }

    private fun crypt5Body(body: ByteArray, key: RSAPrivateKey, salted: Boolean): String {
        val start = if (salted) 22 else 12
        require(body.size > start)
        var end = start
        while (end < body.size && body[end].toInt() in 48..57) end++
        require(end > start && end - start <= 7)
        val size = String(body, start, end - start, Charsets.US_ASCII).toInt()
        require(size > 0 && size < body.size - end - 1)
        val cipher = base64(String(body, end + 1, size, Charsets.US_ASCII))
        val rsaCipher = base64(String(body, end + 1 + size, body.size - end - 1 - size, Charsets.US_ASCII))
        val secret = base64(utf8(swapPairs(rsa(key, rsaCipher))))
        require(secret.size == 32 && cipher.size >= 16)
        if (salted) for (i in secret.indices) secret[i] = (secret[i].toInt() xor body[14 + i % 8].toInt()).toByte()
        val chacha = ChaCha20Poly1305()
        chacha.init(false, AEADParameters(KeyParameter(secret), 128, body.copyOfRange(0, 12)))
        val clear = ByteArray(chacha.getOutputSize(cipher.size))
        var count = chacha.processBytes(cipher, 0, cipher.size, clear, 0)
        count += chacha.doFinal(clear, count)
        return utf8(base64(utf8(swapPairs(clear.copyOf(count)))))
    }

    private fun privateKey(id: String, encoded: String): RSAPrivateKey = privateKeys.getOrPut(id) {
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(base64(encoded))) as RSAPrivateKey
    }

    private fun rsa(key: RSAPrivateKey, wire: ByteArray): ByteArray {
        require(wire.size == (key.modulus.bitLength() + 7) / 8)
        return Cipher.getInstance("RSA/ECB/PKCS1Padding").run { init(Cipher.DECRYPT_MODE, key); doFinal(wire) }
    }

    private fun aesGcm(key: ByteArray, nonce: ByteArray, wire: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            doFinal(wire)
        }

    private fun base64(text: String): ByteArray {
        val clean = text.filterNot { it.isWhitespace() }.replace('-', '+').replace('_', '/')
        require(clean.length <= MAX_BODY_CHARS && clean.length % 4 != 1)
        return Base64Strict.decode(clean) ?: throw IllegalArgumentException("Invalid encrypted payload encoding")
    }

    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private fun swapPairs(bytes: ByteArray): ByteArray = bytes.copyOf().apply {
        for (i in 0 until (size - 1) step 2) { val a = this[i]; this[i] = this[i + 1]; this[i + 1] = a }
    }
}
