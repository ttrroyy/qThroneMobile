package app.qthrone.mobile.bg.carrier

import android.content.Intent
import android.util.AtomicFile
import app.qthrone.mobile.ktx.app
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Small private snapshot for a newly resumed UI; updates also arrive via a signature-protected broadcast. */
internal object NativeConnectionStatus {
    /** [total] is 0 for clients without workers; [active] is what the client last reported up. */
    data class Snapshot(val profileId: Long, val stage: NativeConnectionStage, val active: Int = 0, val total: Int = 0)
    private fun file(testing:Boolean) = AtomicFile(File(app.noBackupFilesDir, if(testing) "native-test-status" else "native-connection-status"))
    @Synchronized private fun <T> locked(block:()->T):T = java.io.RandomAccessFile(File(app.noBackupFilesDir,"native-status.lock"),"rw").use {
        val lock=it.channel.lock()
        try { block() } finally { lock.release() }
    }
    fun action() = app.packageName + ".NATIVE_CONNECTION_STATUS"

    class Writer internal constructor(private val profileId: Long,private val testing:Boolean=false) : AutoCloseable {
        private val id = UUID.randomUUID().toString()
        private var closed = false
        private var last: NativeConnectionStage? = null
        private var active = 0
        private var total = 0
        @Synchronized fun updateWorkers(active: Int, total: Int) {
            if (closed || (active == this.active && total == this.total)) return
            this.active = active; this.total = total
            last?.let { publish(it) }
        }
        @Synchronized fun update(stage: NativeConnectionStage) {
            if (closed || stage == last) return
            last = stage
            publish(stage)
        }
        private fun publish(stage: NativeConnectionStage) {
            runCatching {
                locked {
                    val json = JSONObject().put("id", id).put("profileId", profileId).put("stage", stage.name)
                        .put("active", active).put("total", total).put("pid", android.os.Process.myPid())
                    val store = file(testing); val output = store.startWrite()
                    try { output.write(json.toString().toByteArray(Charsets.UTF_8)); store.finishWrite(output) }
                    catch (error: Throwable) { store.failWrite(output); throw error }
                }
                app.sendBroadcast(Intent(action()).setPackage(app.packageName)
                    .putExtra("profileId", profileId).putExtra("stage", stage.name).putExtra("active", active).putExtra("total", total)
                    .putExtra("testing",testing), app.packageName + ".SERVICE")
            }
        }
        @Synchronized override fun close() {
            closed = true
            runCatching { locked {
                if (JSONObject(String(file(testing).readFully(), Charsets.UTF_8)).optString("id") == id) file(testing).delete()
            } }
        }
    }

    fun read(testing:Boolean=false): Snapshot? = runCatching { locked {
        val json = JSONObject(String(file(testing).readFully(), Charsets.UTF_8))
        val pid = json.getInt("pid")
        if (pid <= 0 || !File("/proc/$pid").isDirectory) return@locked null
        Snapshot(json.getLong("profileId"), NativeConnectionStage.valueOf(json.getString("stage")), json.optInt("active"), json.optInt("total"))
    } }.getOrNull()
}
