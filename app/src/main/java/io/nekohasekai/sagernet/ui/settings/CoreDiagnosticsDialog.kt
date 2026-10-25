package app.qthrone.mobile.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import app.qthrone.mobile.BuildConfig
import app.qthrone.mobile.R
import app.qthrone.mobile.bg.CoreServiceClient
import app.qthrone.mobile.ktx.Logs
import app.qthrone.mobile.ktx.readableMessage
import app.qthrone.mobile.ui.MobileAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A 30 s CPU and heap profile of the core: started only from here, written to the cache and shared on request.
 * The archive holds runtime data of the connection, which the log redactor cannot mask, hence the warning first.
 */
object CoreDiagnosticsDialog {

    private const val DURATION_MS = 30_000
    private const val MIME = "application/zip"

    fun confirm(activity: FragmentActivity) {
        MobileAlertDialogBuilder(activity)
            .setTitle(R.string.diagnostics_title)
            .setMessage(R.string.diagnostics_warning)
            .setPositiveButton(R.string.diagnostics_start) { _, _ -> capture(activity) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun capture(activity: FragmentActivity) {
        val dir = File(activity.cacheDir, "diagnostics")
        dir.deleteRecursively()
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "qthrone-diagnostics-$stamp.zip")
        val progress = MobileAlertDialogBuilder(activity)
            .setTitle(R.string.diagnostics_title)
            .setMessage(R.string.diagnostics_running)
            .setCancelable(false)
            .setPositiveButton(R.string.diagnostics_stop, null)
            .show()
        // Stop ends the window early; the capture then completes with what it has.
        progress.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            it.isEnabled = false
            activity.lifecycleScope.launch(Dispatchers.IO) { runCatching { CoreServiceClient.call { core -> core.stopDiagnostics() } } }
        }
        activity.lifecycleScope.launch {
            val error = try {
                withContext(Dispatchers.IO) { CoreServiceClient.call { it.captureDiagnostics(DURATION_MS, file.absolutePath) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
                e.readableMessage
            }
            progress.dismiss()
            if (error.isNotEmpty() || !file.isFile) {
                MobileAlertDialogBuilder(activity)
                    .setTitle(R.string.diagnostics_failed)
                    .setMessage(error.ifEmpty { activity.getString(R.string.warp_core_unreachable) })
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }
            share(activity, file)
        }
    }

    private fun share(activity: FragmentActivity, file: File) {
        val uri = FileProvider.getUriForFile(activity, BuildConfig.APPLICATION_ID + ".cache", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        try {
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.diagnostics_title)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.share_target_missing, Toast.LENGTH_SHORT).show()
        }
    }
}
