package app.qthrone.mobile.bg.carrier

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import app.qthrone.mobile.ktx.Logs
import app.qthrone.mobile.ktx.app
import java.util.UUID

/** Private, same-app messages; captcha addresses and tokens never enter logs. */
class CarrierCaptchaBridge(private val process: Process, private val onlyIfUiIdle: Boolean = false,
    private val allowUi: Boolean = true,
    /** Whose original WebView to follow: qWDTT and CSQTT differ in user agent and cookie handling. */
    private val client: String = "qwdtt"): AutoCloseable {
    @Volatile var waiting=false
        private set
    @Volatile var interactionRequired=false
        private set
    private var receiver: BroadcastReceiver? = null
    private var requestId: String? = null
    private var pendingPost: Thread? = null
    private var command="CAPTCHA_RESULT|"
    private var closed=false
    private var cancelledByUser=false
    val userCancelled: Boolean @Synchronized get() = cancelledByUser
    @Synchronized fun accept(line: String) {
        if(closed) return
        val turn=line.startsWith("VK_AUTH_REQUIRED|")
        if(!turn && !line.startsWith("CAPTCHA_SOLVE|")) return
        // WDTT Plus numbers its requests: CAPTCHA_SOLVE|id|mode|uri|token, answered as CAPTCHA_RESULT|id|value.
        val numbered=!turn && client=="wdttplus"
        val fields=if(numbered) line.split('|',limit=5).let { listOf(it[0])+it.drop(2) } else line.split('|',limit=4)
        val nativeId=if(numbered) line.split('|',limit=3).getOrNull(1).orEmpty() else ""
        val target=if(turn) fields.getOrNull(1).orEmpty() else fields.getOrNull(2).orEmpty()
        if(turn) { if(!target.matches(Regex("[A-Za-z0-9_-]{16,512}"))) return }
        else if(fields.size!=4 || target.length>8192 || (numbered && !nativeId.matches(Regex("[A-Za-z0-9_.:-]{1,128}")))) return
        finishRequest()
        command=if(turn) "TURN_CREDS|" else if(numbered) "CAPTCHA_RESULT|$nativeId|" else "CAPTCHA_RESULT|"
        val automatic=NativeCaptchaPolicy.automatic(fields.joinToString("|"))
        val automaticTimeout=NativeCaptchaPolicy.automaticTimeout(client)
        if(!NativeCaptchaPolicy.permitted(allowUi,cancelledByUser,automatic)) {
            if(!allowUi) interactionRequired=true
            synchronized(process) { runCatching { process.outputStream.write((command+"error:cancelled\n").toByteArray());process.outputStream.flush() } }
            return
        }
        val id=UUID.randomUUID().toString()
        requestId=id
        waiting=true
        val callback=object: BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                respond(intent.getStringExtra("id"),intent.getStringExtra("result"))
            }
        }
        ContextCompat.registerReceiver(app,callback,IntentFilter(app.packageName+".CAPTCHA_RESULT"),app.packageName+".SERVICE",null,ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver=callback
        if(automatic) {
            // Several profiles can ask at once; one hidden WebView runs at a time. The client
            // waits 10 s for an automatic attempt (as in the original app), so a queued attempt
            // gets the rest of those 10 s and the answer is "timeout" once they are spent.
            pendingPost=Thread({
                val deadline=System.nanoTime()+automaticTimeout*1_000_000
                try {
                    var posted=false
                    while(!posted && System.nanoTime()<deadline) {
                        posted=try {
                            synchronized(this@CarrierCaptchaBridge) {
                                if(closed || requestId!=id) return@Thread
                                CarrierUiRequests.post(id,target,turn,onlyIfIdle=true,automatic=true,client=client)
                            }
                        } catch(_:Exception) { respond(id,"error:VK confirmation unavailable");return@Thread }
                        if(!posted) Thread.sleep(100)
                    }
                    val left=(deadline-System.nanoTime())/1_000_000
                    if(left>0) Thread.sleep(left)
                } catch(_:InterruptedException) { return@Thread }
                try { respond(id,"error:timeout") } catch(error:Exception) { Logs.w(error) }
            },"qThrone-auto-captcha-queue").apply { isDaemon=true;start() }
        } else if (!CarrierUiRequests.post(id,target,turn,onlyIfIdle=onlyIfUiIdle || !allowUi,client=client)) {
            respond(id,"error:VK confirmation is busy")
        }
    }
    @Synchronized private fun respond(id: String?,result: String?) {
        if(id==null || id!=requestId || result==null || result.length>16384 || result.any { it=='\n' || it=='\r' }) return
        // Both OpenFlux apps keep the connection after "Позже" or a closed check: the client asks again later.
        if(result=="error:cancelled" && client !in listOf("openflux","openfluxkey")) cancelledByUser=true
        synchronized(process) {
            runCatching { process.outputStream.write((command+result+"\n").toByteArray());process.outputStream.flush() }
        }
        finishRequest()
    }
    @Synchronized override fun close() {
        closed=true
        finishRequest()
    }
    private fun finishRequest() {
        // The queue thread answers the timeout itself: interrupting it there broke the file lock below.
        pendingPost?.takeIf { it !== Thread.currentThread() }?.interrupt();pendingPost=null
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver=null
        waiting=false
        requestId?.let {
            CarrierUiRequests.clear(it)
            app.sendBroadcast(Intent(app.packageName+".CAPTCHA_CLOSE").setPackage(app.packageName).putExtra("id",it),app.packageName+".SERVICE")
        }
        requestId=null
    }
}
