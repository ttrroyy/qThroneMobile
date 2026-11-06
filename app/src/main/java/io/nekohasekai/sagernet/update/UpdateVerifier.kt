package app.rawline.mobile.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import app.rawline.mobile.BuildConfig
import app.rawline.mobile.R
import java.io.File
import java.security.MessageDigest

/** Checks a downloaded APK against the manifest and the installed app before it reaches the installer. */
object UpdateVerifier {

    class Rejected(message: String) : Exception(message)

    fun verify(context: Context, file: File, offer: UpdateChecker.Offer) {
        val expected = offer.expected!!
        if (file.length() != expected.size || file.length() != offer.apk!!.size) reject(context, R.string.update_error_size)
        if (sha256(file) != expected.sha256) reject(context, R.string.update_error_sha256)

        val pm = context.packageManager
        val archive = archiveInfo(pm, file) ?: reject(context, R.string.update_error_parse)
        if (archive.packageName != context.packageName) reject(context, R.string.update_error_package)
        val code = PackageInfoCompat.getLongVersionCode(archive)
        if (code != offer.versionCode || code <= BuildConfig.VERSION_CODE) reject(context, R.string.update_error_version)

        val archiveSigners = signers(archive) ?: reject(context, R.string.update_error_parse)
        val installedSigners = signers(installedInfo(pm, context.packageName))
        if (installedSigners == null || archiveSigners != installedSigners) reject(context, R.string.update_error_signature)
        val pin = BuildConfig.UPDATE_SIGNER_SHA256
        if (pin.isNotEmpty() && archiveSigners != setOf(pin)) reject(context, R.string.update_error_pin)
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    private fun reject(context: Context, message: Int): Nothing = throw Rejected(context.getString(message))

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private val signatureFlags
        @SuppressLint("InlinedApi")
        @Suppress("DEPRECATION")
        get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, file: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(signatureFlags.toLong()))
        } else {
            pm.getPackageArchiveInfo(file.path, signatureFlags)
        }

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager, packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(signatureFlags.toLong()))
        } else {
            pm.getPackageInfo(packageName, signatureFlags)
        }

    /** SHA-256 digests of the certificates that signed the APK contents. */
    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String>? {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures?.mapTo(HashSet()) { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex() }
            ?.takeIf { it.isNotEmpty() }
    }
}
