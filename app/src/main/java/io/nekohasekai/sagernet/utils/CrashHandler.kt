package app.rawline.mobile.utils

import android.content.Intent
import android.util.Log
import com.jakewharton.processphoenix.ProcessPhoenix
import app.rawline.mobile.ktx.Logs
import app.rawline.mobile.ktx.app
import app.rawline.mobile.ui.BlankActivity

/** Logs an uncaught exception and restarts into [BlankActivity], which shares a redacted log export. */
object CrashHandler : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // note: libc / go panic is in android log

        try {
            Log.e(thread.toString(), throwable.stackTraceToString())
        } catch (e: Exception) {
        }

        try {
            Logs.e(thread.toString())
            Logs.e(throwable.stackTraceToString())
        } catch (e: Exception) {
        }

        ProcessPhoenix.triggerRebirth(app, Intent(app, BlankActivity::class.java).apply {
            putExtra(BlankActivity.EXTRA_SEND_LOG, true)
        })
    }
}
