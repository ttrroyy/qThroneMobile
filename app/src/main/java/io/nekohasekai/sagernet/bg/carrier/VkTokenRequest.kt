package app.rawline.mobile.bg.carrier

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import app.rawline.mobile.ktx.app
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** qWDTT's WebView token fallback, only requested while creating new hashes. */
internal object VkTokenRequest {
    fun obtain(): Pair<String,Long>? {
        val id=UUID.randomUUID().toString()
        val done=CountDownLatch(1)
        val result=AtomicReference<Pair<String,Long>?>(null)
        val receiver=object: BroadcastReceiver() {
            override fun onReceive(context: Context,intent: Intent) {
                if(intent.getStringExtra("id")!=id) return
                val text=intent.getStringExtra("result").orEmpty()
                if(text.length<=16384) result.set(runCatching {
                    val json=JSONObject(text)
                    json.getString("token").takeIf { it.isNotBlank() }?.let { it to json.optLong("expires") }
                }.getOrNull())
                done.countDown()
            }
        }
        ContextCompat.registerReceiver(app,receiver,IntentFilter(app.packageName+".CAPTCHA_RESULT"),
            app.packageName+".SERVICE",null,ContextCompat.RECEIVER_NOT_EXPORTED)
        return try {
            CarrierUiRequests.post(id,VkRelayAccount.authorizeUrl(id),tokenState=id)
            if(done.await(5,TimeUnit.MINUTES)) result.get() else null
        } finally {
            app.unregisterReceiver(receiver)
            CarrierUiRequests.clear(id)
            app.sendBroadcast(Intent(app.packageName+".CAPTCHA_CLOSE").setPackage(app.packageName).putExtra("id",id),app.packageName+".SERVICE")
        }
    }
}
