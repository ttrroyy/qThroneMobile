package app.rawline.mobile.bg.carrier

import app.rawline.mobile.ktx.app
import app.rawline.mobile.outbound.json.JsonInput
import java.security.SecureRandom

/**
 * The session identity the original CSQTT app gives every launch. The server keeps one epoch per device:
 * a launch with a new generation and salt replaces the device's older sessions, and a disconnect ends the
 * sessions of its own epoch. Generations only grow, across restarts of the app too.
 */
internal object CsqttEpoch {
    private const val KEY = "csqttGeneration"
    private val random = SecureRandom()

    /** nextTunnelGenerationId of the original app: never below the clock, always above the last one. */
    fun next(nowSeconds: Long, last: Long): Long = maxOf(nowSeconds, if (last == Long.MAX_VALUE) last else last + 1)

    fun salt(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private var last = -1L

    @Synchronized fun reserve(): Long {
        val preferences = runCatching { app.getSharedPreferences("carriers", android.content.Context.MODE_PRIVATE) }.getOrNull()
        if (last < 0) last = preferences?.let { runCatching { it.getLong(KEY, 0L) }.getOrNull() } ?: 0L
        last = next(System.currentTimeMillis() / 1000, last)
        preferences?.let { runCatching { it.edit().putLong(KEY, last).commit() } }
        return last
    }

    fun apply(privateJson: String, generation: Long, salt: String): String =
        JsonInput.parseObject(privateJson).also { it["generation"] = generation; it["salt"] = salt }.toCompact()
}
