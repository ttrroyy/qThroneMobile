package app.rawline.mobile.database.backup

import android.content.Intent
import android.net.Uri
import androidx.fragment.app.FragmentActivity
import app.rawline.mobile.R
import app.rawline.mobile.ktx.Logs
import app.rawline.mobile.ui.MainActivity

// Entry for a .rwlbackup opened from outside (VIEW/SEND intents).
object BackupEntry {

    private var pending: Uri? = null

    /**
     * True when [uri] starts with the "THRN" magic; then Tools › Backup opens with the restore dialog for it. The
     * magic is read on the calling thread.
     */
    fun open(activity: FragmentActivity, uri: Uri): Boolean {
        val backup = try {
            activity.contentResolver.openInputStream(uri)?.use { RwlBackup.hasMagic(it) } == true
        } catch (e: Exception) {
            Logs.w("open backup $uri", e)
            false
        }
        if (!backup) return false
        synchronized(this) { pending = uri }
        activity.runOnUiThread {
            if (activity is MainActivity) {
                activity.displayFragmentWithId(R.id.nav_tools)
            } else {
                activity.startActivity(
                    Intent(activity, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            }
        }
        return true
    }

    /** The Uri [open] handed to the Tools screen, once. */
    fun takePending(): Uri? = synchronized(this) { pending.also { pending = null } }
}
