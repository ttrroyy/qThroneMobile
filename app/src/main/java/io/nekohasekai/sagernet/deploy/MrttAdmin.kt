package app.rawline.mobile.deploy

import app.rawline.mobile.ktx.app
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** MRTT's own server files of the release this client carries: deploy.sh, cascade.sh, the server and the cascade client for each architecture. */
object MrttBundle {
    const val URL = "https://github.com/ttrroyy/RawlineMobile/releases/download/v1.0.0/mrtt-server-233eddc0.tar.gz"
    const val SHA256 = "bb94501c7c762bb24297f3a36d94b0d1c74e7fed8125f9a89b6a21027da2c503"
    fun load(): Map<String,ByteArray> {
        val cached = File(app.cacheDir,"mrtt-server-$SHA256.tar.gz")
        fun digest(file: File): String {
            val hash = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536); while(true) { val count = input.read(buffer); if(count<0) break; hash.update(buffer,0,count) } }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        if(!cached.exists() || digest(cached)!=SHA256) {
            cached.delete()
            OkHttpClient.Builder().callTimeout(5,TimeUnit.MINUTES).build().newCall(Request.Builder().url(URL).build()).execute().use { response ->
                check(response.isSuccessful) { "Загрузка файлов сервера: HTTP ${response.code}" }
                cached.outputStream().use { output -> response.body!!.byteStream().copyTo(output) }
            }
            check(digest(cached)==SHA256) { cached.delete(); "Контрольная сумма файлов сервера не совпала" }
        }
        return untar(GZIPInputStream(ByteArrayInputStream(cached.readBytes())).readBytes())
    }
    /** Plain files of a ustar archive. */
    internal fun untar(data: ByteArray): Map<String,ByteArray> {
        val files = linkedMapOf<String,ByteArray>()
        var at = 0
        while(at+512<=data.size && data[at]!=0.toByte()) {
            val name = String(data,at,100,Charsets.US_ASCII).substringBefore('\u0000').removePrefix("./")
            val size = String(data,at+124,12,Charsets.US_ASCII).trim { it=='\u0000' || it==' ' }.toLong(8).toInt()
            val kind = data[at+156].toInt().toChar()
            if(kind=='0' || kind=='\u0000') files[name] = data.copyOfRange(at+512,at+512+size)
            at += 512+(size+511)/512*512
        }
        return files
    }
    fun serverAsset(machine: String) = when(machine.trim().lowercase()) {
        "x86_64","amd64" -> "mrtt-linux-amd64"
        "aarch64","arm64" -> "mrtt-linux-arm64"
        "armv7l","armv7","armhf" -> "mrtt-linux-armv7"
        else -> error("Архитектура VPS ${machine.trim()} пока не поддерживается. Поддерживаются: x86_64, aarch64, armv7l")
    }
    fun failure(exit: Int,output: String): String {
        val detail = output.lineSequence().map(String::trim).lastOrNull { it.startsWith("MRTT_DEPLOY_ERROR|") }?.split('|',limit=3)?.getOrNull(2)?.trim()?.take(360).orEmpty()
        fun with(message: String) = if(detail.isEmpty()) message else "$message Причина: $detail"
        val lines = output.lineSequence().map(String::trim).toList()
        return when {
            "MRTT_DEPLOY_ROLLED_BACK" in lines || exit==30 -> with("Новый релиз не прошёл проверку; предыдущая установка восстановлена")
            "MRTT_DEPLOY_ROLLBACK_FAILED" in lines || exit==31 -> with("Новый релиз не запустился, а автоматический rollback завершился с ошибкой — требуется проверка VPS")
            exit==20 -> with("Кандидат не прошёл предзапусковую проверку; работающий сервер не останавливался")
            exit==2 -> with("Установщик отклонил параметры развёртывания")
            else -> detail.ifEmpty { "Установщик завершился с кодом $exit" }
        }
    }
}

class MrttApiException(message: String): IOException(message)

/** The server's management API: it listens on 127.0.0.1 only, so calls go through SSH with the deploy's access. */
class MrttAdmin(private val server: DeployServer) {
    val available: Boolean get() = server["installed"]=="true" && server["hostKey"].isNotBlank()
    private fun session(): Session = WdttPlusAdmin(server).session()
    private fun exchange(ssh: Session,method: String,path: String,body: String?): String {
        val payload = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val head = buildString {
            append("$method $path HTTP/1.0\r\n")
            // The server puts the Host header into client links: the VPS address, not 127.0.0.1.
            append("Host: ${server["host"]}\r\nAccept: application/json\r\nConnection: close\r\n")
            if(body!=null) append("Content-Type: application/json\r\n")
            append("Content-Length: ${payload.size}\r\n\r\n")
        }
        val channel = ssh.openChannel("direct-tcpip") as ChannelDirectTCPIP
        channel.setHost("127.0.0.1"); channel.setPort(server.port("webPort"))
        val input = channel.inputStream; val output = channel.outputStream
        val opening = System.currentTimeMillis()
        // A refusal comes at once and means nothing listens; silence means the kept connection died, so it goes back to be retried.
        try { channel.connect(10_000) } catch(error: Exception) {
            if(System.currentTimeMillis()-opening>=9_000 || !ssh.isConnected) throw java.io.IOException(error)
            throw MrttApiException("MRTT не установлен")
        }
        try {
            output.write(head.toByteArray(Charsets.US_ASCII)); output.write(payload); output.flush()
            return parseResponse(input.readBytes())
        } finally { channel.disconnect() }
    }
    fun request(method: String,path: String,body: String? = null): String = SshSessions.use(server) { exchange(it,method,path,body) }
    fun get(path: String) = request("GET",path)
    fun post(path: String,body: JSONObject? = null) = request("POST",path,(body ?: JSONObject()).toString())
    fun put(path: String,body: JSONObject) = request("PUT",path,body.toString())
    fun delete(path: String) = request("DELETE",path)
    fun clients() = JSONArray(get("/api/clients"))
    fun groups() = JSONArray(get("/api/groups"))
    fun rules() = JSONArray(get("/api/routing-rules"))
    fun settings() = JSONObject(get("/api/settings"))
    fun proxy() = JSONObject(get("/api/local-proxy"))
    fun stats() = JSONObject(get("/api/stats"))
    fun logs() = JSONObject(get("/api/logs"))

    private fun exec(ssh: Session,command: String,timeoutMs: Long): Pair<Int,String> {
        val plan = rootStdinPlan(command,server["sudoPassword"].ifBlank { server["sshPassword"] },"")
        val channel = ssh.openChannel("exec") as ChannelExec
        channel.setCommand(plan.command+" 2>&1")
        channel.setInputStream(plan.input.byteInputStream())
        val input = channel.inputStream
        channel.connect(30_000)
        val deadline = System.currentTimeMillis()+timeoutMs
        val text = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        try {
            while(true) {
                while(input.available()>0) { val count = input.read(buffer); if(count<0) break; text.write(buffer,0,count) }
                if(channel.isClosed && input.available()==0) break
                check(System.currentTimeMillis()<deadline) { "Превышено время ожидания SSH-команды" }
                Thread.sleep(100)
            }
            return channel.exitStatus to text.toString("UTF-8")
        } finally { channel.disconnect() }
    }
    private fun title() = server["name"].ifBlank { server["host"] }
    private fun cascade(ssh: Session,script: String,marker: String,vararg arguments: String): List<String> {
        val quoted = arguments.joinToString(" ") { DeployProtocol.quote(it) }
        val (exit,output) = exec(ssh,"set -- $quoted\n$script",180_000L)
        val line = output.lineSequence().firstOrNull { it.startsWith(marker) }
        if(exit!=0 || line==null) {
            val reason = output.lineSequence().lastOrNull { it.startsWith("error:") }?.removePrefix("error:")?.trim()
            throw IOException("${title()}: ${reason ?: "каскад не настроился"}")
        }
        return line.split(' ')
    }
    /** This server connects to [target] as an ordinary config; on an error it removes what it set up. */
    fun addExit(target: DeployServer): JSONObject {
        val remote = MrttAdmin(target)
        val id = java.util.UUID.randomUUID().toString().replace("-","").take(8)
        val name = "Каскад $id"
        remote.post("/api/clients",JSONObject().put("name",name).put("group_id","").put("days",0).put("hash","").put("vk_token",false)
            .put("workers",0).put("max_devices",0).put("transport","").put("link_room_off",false).put("exit",""))
        val items = remote.clients()
        val password = (0 until items.length()).map { items.getJSONObject(it) }.firstOrNull { it.optString("name")==name }?.optString("password")
            ?: throw IOException("Второй сервер не создал конфиг каскада")
        try {
            val bundle = MrttBundle.load()
            val script = bundle.getValue("cascade.sh").toString(Charsets.UTF_8)
            val ssh = session()
            try {
                val machine = exec(ssh,"uname -m",30_000).second.trim()
                val asset = when(machine) {
                    "x86_64","amd64" -> "mrtt-exit-client-amd64"
                    "aarch64","arm64" -> "mrtt-exit-client-arm64"
                    else -> throw IOException("${title()}: каскад не поддерживает архитектуру $machine")
                }
                try {
                    val sftp = ssh.openChannel("sftp") as ChannelSftp
                    sftp.connect(30_000)
                    try { sftp.put(bundle.getValue(asset).inputStream(),"/tmp/.mrtt-upload-exit-client"); sftp.chmod(384,"/tmp/.mrtt-upload-exit-client") } finally { sftp.disconnect() }
                    // SFTP gives no execute right and cannot write to a system folder without root.
                    val installed = exec(ssh,"install -m 0755 /tmp/.mrtt-upload-exit-client /usr/local/lib/mrtt-exit-client; status=\$?; rm -f /tmp/.mrtt-upload-exit-client; exit \$status",30_000L)
                    if(installed.first!=0) throw IOException("${title()}: клиент каскада не установился")
                    val up = cascade(ssh,script,"CASCADE_CLIENT","client-up",id,target["host"],target["peerPort"],password)
                    cascade(ssh,script,"CASCADE_OK","client-check",id)
                    return JSONObject().put("id",id).put("name",target["name"].ifBlank { target["host"] }).put("host",target["host"])
                        .put("table",up.getOrNull(1)?.toIntOrNull() ?: 0).put("remote_password",password)
                } catch(error: Exception) {
                    runCatching { cascade(ssh,script,"CASCADE_OK","client-down",id) }
                    throw error
                }
            } finally { ssh.disconnect() }
        } catch(error: Exception) {
            runCatching { remote.delete("/api/clients/"+encode(password)) }
            throw error
        }
    }
    /** An unavailable or already removed exit does not stop the removal here. */
    fun removeExit(id: String,target: DeployServer?,remotePassword: String) {
        require(id.matches(Regex("[a-f0-9]{8}")))
        val script = MrttBundle.load().getValue("cascade.sh").toString(Charsets.UTF_8)
        val ssh = session()
        try { cascade(ssh,script,"CASCADE_OK","client-down",id) } finally { ssh.disconnect() }
        if(target!=null && remotePassword.isNotEmpty()) runCatching { MrttAdmin(target).delete("/api/clients/"+encode(remotePassword)) }
    }
    companion object {
        fun encode(value: String): String = java.net.URLEncoder.encode(value,"UTF-8").replace("+","%20")
        internal fun parseResponse(raw: ByteArray): String {
            val text = String(raw,Charsets.UTF_8)
            val split = text.indexOf("\r\n\r\n")
            if(split<0) throw MrttApiException("Сервер не ответил: API управления недоступно")
            val status = text.substring(0,text.indexOf("\r\n").coerceAtLeast(0)).split(' ').getOrNull(1)?.toIntOrNull() ?: throw MrttApiException("Некорректный ответ API")
            val body = text.substring(split+4)
            if(status !in 200..299) throw MrttApiException(body.trim().ifBlank { "HTTP $status" }.take(300))
            return body
        }
    }
}
