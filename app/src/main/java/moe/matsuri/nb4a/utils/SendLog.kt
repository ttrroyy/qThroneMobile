package moe.matsuri.nb4a.utils

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.rawline.mobile.BuildConfig
import app.rawline.mobile.R
import java.io.File

object SendLog {

    /** Opens the share sheet for a [LogExport] file (cacheDir/log, exposed by the FileProvider). */
    fun share(context: Context, file: File, subject: String) {
        val uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".cache", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        val chooser = Intent.createChooser(send, context.getString(R.string.log_share))
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
