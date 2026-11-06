package app.rawline.mobile.bg.carrier

import android.content.Intent
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import app.rawline.mobile.R
import app.rawline.mobile.ktx.Logs
import app.rawline.mobile.ktx.app
import app.rawline.mobile.ui.MainActivity
import java.io.File
import java.io.RandomAccessFile
import org.json.JSONObject

/** Replay a private pending interaction when the app was closed during a reconnect. */
internal object CarrierUiRequests {
    private const val notificationId=9304
    private fun file()=AtomicFile(File(app.noBackupFilesDir,"carrier-ui-request"))
    @Synchronized private fun <T> locked(block: () -> T): T =
        RandomAccessFile(File(app.noBackupFilesDir,"carrier-ui-request.lock"),"rw").use { access ->
            val lock=access.channel.lock()
            try { block() } finally { lock.release() }
        }
    fun post(id: String,url: String,turn: Boolean=false,tokenState: String?=null,onlyIfIdle: Boolean=false,automatic: Boolean=false,client: String="qwdtt"): Boolean {
        var previousId: String? = null
        val posted = locked {
            val previous=runCatching { JSONObject(String(file().readFully(),Charsets.UTF_8)) }.getOrNull()
            if(previous!=null && previous.optLong("expires")>System.currentTimeMillis()) {
                if(onlyIfIdle) return@locked false
                previousId=previous.optString("id").takeIf { it.isNotEmpty() && it!=id }
            }
            val json=JSONObject().put("id",id).put("url",url).put("turn",turn).put("automatic",automatic).put("client",client)
                .put("ownerPid",android.os.Process.myPid())
                .put("expires",System.currentTimeMillis()+if(automatic) NativeCaptchaPolicy.automaticTimeout(client) else 5*60_000L)
            if(tokenState!=null) json.put("tokenState",tokenState)
            val store=file();val out=store.startWrite()
            try { out.write(json.toString().toByteArray(Charsets.UTF_8));store.finishWrite(out) }
            catch(error: Throwable) { store.failWrite(out);throw error }
            true
        }
        if(!posted) return false
        previousId?.let { app.sendBroadcast(Intent(app.packageName+".CAPTCHA_RESULT").setPackage(app.packageName)
            .putExtra("id",it).putExtra("result","error:superseded"),app.packageName+".SERVICE") }
        pending()?.let { app.sendBroadcast(it,app.packageName+".SERVICE") }
        // Hidden attempts never notify: only a request the user must answer does.
        if(!automatic) runCatching {
            if(android.os.Build.VERSION.SDK_INT>=33 && androidx.core.content.ContextCompat.checkSelfPermission(app,
                android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) return@runCatching
            val processes=app.getSystemService(android.app.ActivityManager::class.java).runningAppProcesses
            if(processes?.any { it.processName==app.packageName && it.importance<=android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }==true)
                return@runCatching
            if(pending()?.getStringExtra("id")!=id) return@runCatching
            val manager=app.getSystemService(android.app.NotificationManager::class.java)
            val channel="vk-confirmation"
            if(android.os.Build.VERSION.SDK_INT>=26) manager.createNotificationChannel(
                android.app.NotificationChannel(channel,"Подтверждение VK",android.app.NotificationManager.IMPORTANCE_DEFAULT))
            val intent=Intent(app,MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val click=android.app.PendingIntent.getActivity(app,notificationId,intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            manager.notify(notificationId,NotificationCompat.Builder(app,channel).setSmallIcon(R.drawable.ic_mobile_vk)
                .setContentTitle("Rawline · VK").setContentText("Откройте приложение для подтверждения подключения")
                .setContentIntent(click).setAutoCancel(true).build())
        }
        return true
    }
    fun pending(): Intent? = locked { runCatching {
        val json=JSONObject(String(file().readFully(),Charsets.UTF_8))
        val owner=json.optInt("ownerPid")
        if(json.getLong("expires")<=System.currentTimeMillis() || owner<=0 || !File("/proc/$owner").isDirectory) {
            file().delete();app.getSystemService(android.app.NotificationManager::class.java).cancel(notificationId)
            return@runCatching null
        }
        Intent(app.packageName+".CAPTCHA_REQUEST").setPackage(app.packageName)
            .putExtra("id",json.getString("id")).putExtra("url",json.getString("url"))
            .putExtra("turn",json.optBoolean("turn"))
            .putExtra("automatic",json.optBoolean("automatic"))
            .putExtra("client",json.optString("client","qwdtt"))
            .apply { json.optString("tokenState").takeIf { it.isNotEmpty() }?.let { putExtra("tokenState",it) } }
    }.getOrNull() }
    fun clear(id: String) {
        // Called while a request finishes on any thread; a failed lock must never end the VPN process.
        val cleared=runCatching { locked { runCatching {
            if(JSONObject(String(file().readFully(),Charsets.UTF_8)).getString("id")!=id) false
            else { file().delete();true }
        }.getOrDefault(false) } }.onFailure { Logs.w(it) }.getOrDefault(false)
        if(cleared) app.getSystemService(android.app.NotificationManager::class.java).cancel(notificationId)
    }
}
