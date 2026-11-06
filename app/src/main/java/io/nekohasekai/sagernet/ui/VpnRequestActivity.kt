package app.rawline.mobile.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import app.rawline.mobile.ui.MobileAlertDialogBuilder as MobileAlertDialogBuilder
import app.rawline.mobile.Key
import app.rawline.mobile.R
import app.rawline.mobile.SagerNet
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.ktx.Logs
import app.rawline.mobile.ktx.broadcastReceiver
import app.rawline.mobile.utils.Theme

class VpnRequestActivity : AppCompatActivity() {
    private var receiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (getSystemService<KeyguardManager>()!!.isKeyguardLocked) {
            receiver = broadcastReceiver { _, _ -> launchConnect() }
            if (SDK_INT >= 33) {
                registerReceiver(
                    receiver,
                    IntentFilter(Intent.ACTION_USER_PRESENT),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT))
            }
        } else launchConnect()
    }

    private val connect = registerForActivityResult(StartService()) {
        if (it) Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun launchConnect() {
        try {
            connect.launch(null)
        } catch (_: ActivityNotFoundException) {
            showConsentUnavailable(ContextThemeWrapper(this, Theme.getTheme())) { finish() }
        } catch (_: SecurityException) {
            showConsentUnavailable(ContextThemeWrapper(this, Theme.getTheme())) { finish() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (receiver != null) unregisterReceiver(receiver)
    }

    class StartService : ActivityResultContract<Void?, Boolean>() {
        private var cachedIntent: Intent? = null

        override fun getSynchronousResult(
            context: Context,
            input: Void?,
        ): SynchronousResult<Boolean>? {
            if (DataStore.serviceMode == Key.MODE_VPN) VpnService.prepare(context)?.let { intent ->
                cachedIntent = intent
                return null
            }
            SagerNet.startService()
            return SynchronousResult(false)
        }

        override fun createIntent(context: Context, input: Void?) =
            cachedIntent!!.also { cachedIntent = null }

        override fun parseResult(resultCode: Int, intent: Intent?) =
            if (resultCode == Activity.RESULT_OK) {
                SagerNet.startService()
                false
            } else {
                Logs.e("Failed to start VpnService: $intent")
                true
            }
    }

    companion object {

        /**
         * Some TV, AOSP and vendor builds ship without VpnDialogs, so the consent screen cannot open. The app op can
         * still be granted over adb (VpnService.prepare then returns null), or the proxy mode used instead.
         */
        fun showConsentUnavailable(context: Context, onDismiss: () -> Unit = {}) {
            val command = "adb shell appops set ${context.packageName} ACTIVATE_VPN allow"
            MobileAlertDialogBuilder(context)
                .setTitle(R.string.vpn_dialog_missing_title)
                .setMessage(context.getString(R.string.vpn_dialog_missing_message, context.packageName))
                .setPositiveButton(R.string.vpn_dialog_use_proxy) { _, _ ->
                    DataStore.serviceMode = Key.MODE_PROXY
                    SagerNet.startService()
                }
                .setNeutralButton(R.string.vpn_dialog_copy_command) { _, _ -> SagerNet.trySetPrimaryClip(command) }
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener { onDismiss() }
                .show()
        }
    }
}
