package app.rawline.mobile.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.rawline.mobile.R
import app.rawline.mobile.ui.MobileAlertDialogBuilder
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

object ShortcutAuthorization {
    private const val EXTRA = "app.rawline.mobile.shortcut_signature"

    private fun key(context: Context): SecretKey = synchronized(this) {
        val lock = File(context.noBackupFilesDir, "shortcut-auth.lock")
        RandomAccessFile(lock, "rw").use { file ->
            file.channel.lock().use {
                val alias = context.packageName + ".shortcut.v1"
                val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore"
                ).apply {
                    init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setDigests(KeyProperties.DIGEST_SHA256).build())
                }.generateKey()
            }
        }
    }

    private fun signature(context: Context, intent: Intent): ByteArray {
        val target = requireNotNull(intent.component)
        require(target.packageName == context.packageName)
        val message = listOf(target.packageName, target.className, intent.action.orEmpty(),
            intent.getLongExtra("profile", -1L).toString()).joinToString("\u0000")
        return Mac.getInstance("HmacSHA256").apply { init(key(context)) }.doFinal(message.toByteArray(Charsets.UTF_8))
    }

    fun sign(context: Context, intent: Intent): Intent = intent.apply {
        putExtra(EXTRA, Base64.encodeToString(signature(context, intent), Base64.NO_WRAP))
    }

    fun isAuthorized(context: Context, intent: Intent): Boolean = try {
        val supplied = intent.getStringExtra(EXTRA)
        supplied != null && MessageDigest.isEqual(Base64.decode(supplied, Base64.NO_WRAP), signature(context, intent))
    } catch (_: Exception) { false }

    fun run(activity: Activity, intent: Intent, command: () -> Unit) {
        if (isAuthorized(activity, intent)) {
            command()
        } else {
            MobileAlertDialogBuilder(activity)
                .setTitle(R.string.shortcut_permission_title)
                .setMessage(R.string.shortcut_permission_message)
                .setPositiveButton(android.R.string.ok) { _, _ -> command() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> activity.finish() }
                .setOnCancelListener { activity.finish() }
                .show()
        }
    }
}
