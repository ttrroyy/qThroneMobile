package app.rawline.mobile.bg

import androidx.core.app.NotificationCompat
import app.qthrone.core.mobile.DiagnosticsRequest
import app.qthrone.core.mobile.Mobile
import app.rawline.mobile.R
import app.rawline.mobile.ktx.Logs
import app.rawline.mobile.ktx.app
import app.rawline.mobile.ktx.readableMessage

/**
 * :bg side of ICoreService.captureDiagnostics (core/mobile/diagnostics.go): the core profiles the process it runs in,
 * which is this one. The service stays in the foreground for the window, so the capture survives the app going to
 * the background while the problem is reproduced. No logs or configs are attached.
 */
object CoreDiagnostics {

    private const val FOREGROUND_REASON = "diagnostics"

    /** Blocks the binder thread for the window; returns "" or the error. */
    fun capture(durationMs: Int, outputPath: String): String = try {
        val request = DiagnosticsRequest()
        request.durationMs = durationMs
        CoreForeground.acquire(FOREGROUND_REASON, notification())
        try {
            Mobile.captureDiagnostics(request, outputPath)
        } finally {
            CoreForeground.release(FOREGROUND_REASON)
        }
        ""
    } catch (e: Throwable) {
        Logs.w(e)
        e.readableMessage
    }

    fun stop() = Mobile.stopDiagnostics()

    private fun notification() = NotificationCompat.Builder(app, CoreForeground.CHANNEL)
        .setSmallIcon(R.drawable.ic_rawline_tile)
        .setContentTitle(app.getString(R.string.app_name))
        .setContentText(app.getString(R.string.diagnostics_notification))
        .setProgress(0, 0, true)
        .setOngoing(true)
        .setSilent(true)
        .setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .build()
}
