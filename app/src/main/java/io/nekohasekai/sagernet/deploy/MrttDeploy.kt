package app.rawline.mobile.deploy

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Installs and removals of MRTT servers, as its app runs them: each server has its own run, progress and error, several go
 * at once, one operation per server. Cancelling only breaks the connection and does not undo steps already done on the VPS.
 */
object MrttDeploy {
    private class Run(val id: String) {
        lateinit var job: Job
        @Volatile var session: Session? = null
        @Volatile var socket: java.net.Socket? = null
        @Volatile var trustReply: CompletableDeferred<Boolean>? = null
        @Volatile var cancelled = false
    }
    val states = MutableStateFlow<Map<String,DeployState>>(emptyMap())
    /** The name each running server is shown under. */
    val titles = MutableStateFlow<Map<String,String>>(emptyMap())
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val runs = java.util.concurrent.ConcurrentHashMap<String,Run>()
    fun running(id: String) = states.value[id]?.running==true
    private fun change(id: String,block: (DeployState) -> DeployState) = states.update { it+(id to block(it[id] ?: DeployState(id))) }
    private fun log(id: String,line: String) = change(id) { it.copy(logs=(it.logs+line+"\n").takeLast(200_000)) }
    private fun step(id: String,percent: Int,step: String) = change(id) { it.copy(percent=percent,step=step) }
    private fun redact(server: DeployServer,text: String): String {
        var result = text
        for(key in listOf("sshPassword","key","keyPassword","sudoPassword","password")) if(server[key].isNotBlank()) result = result.replace(server[key],"[скрыто]")
        return result.replace(Regex("\\u001B\\[[0-9;]*[A-Za-z]"),"")
    }
    fun approve(id: String,approved: Boolean) { runs[id]?.trustReply?.complete(approved) }
    fun cancel(id: String) {
        val run = runs[id] ?: return
        if(run.cancelled) return
        run.cancelled = true
        change(id) { it.copy(stopping=true,step="Остановка…") }
        run.job.cancel(); run.trustReply?.complete(false)
        scope.launch { run.session?.disconnect(); runCatching { run.socket?.close() } }
    }
    fun cancelAll() = runs.keys.toList().forEach(::cancel)
    fun start(server: DeployServer,uninstall: Boolean) {
        if(running(server.id)) return
        val copy = DeployServer(JSONObject(server.json.toString()))
        if(copy["manualPorts"]!="true") { copy["peerPort"]="46000"; copy["webPort"]="46002"; copy["sshPort"]="22" }
        val run = Run(server.id)
        runs[server.id] = run
        titles.update { it+(server.id to server["name"].ifBlank { server["host"] }) }
        states.update { it+(server.id to DeployState(server.id,true,0,"Подключение",config=server["config"])) }
        run.job = scope.launch {
            try { perform(run,copy,uninstall) }
            catch(e: Exception) {
                // Breaking off a blocking call surfaces as its own error; after a cancel it is the cancel, not a failure.
                if(e is CancellationException || run.cancelled) {
                    log(run.id,"Отменено пользователем. Уже выполненные на VPS шаги не откатываются: при необходимости запустите ещё раз.")
                    change(run.id) { it.copy(step="Отменено пользователем") }
                } else {
                    log(run.id,"Ошибка: "+redact(copy,e.message ?: e.javaClass.simpleName))
                    change(run.id) { it.copy(step="Ошибка установки или SSH") }
                }
            } finally {
                run.session?.disconnect(); runCatching { run.socket?.close() }
                // A new run for this server may already be going: it owns the state now.
                if(runs.remove(run.id,run)) {
                    change(run.id) { it.copy(running=false,stopping=false) }
                    titles.update { it-run.id }
                }
            }
        }
    }
    private suspend fun connect(run: Run,server: DeployServer): Session {
        val jsch = JSch()
        if(server["auth"]=="key") jsch.addIdentity("Rawline",server["key"].toByteArray(),null,server["keyPassword"].takeIf { it.isNotEmpty() }?.toByteArray())
        jsch.hostKeyRepository = object : HostKeyRepository {
            override fun check(host: String,bytes: ByteArray): Int {
                val fingerprint = "SHA256:"+android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(bytes),android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                val expected = server["hostKey"]
                if(expected.isNotBlank()) return if(expected==fingerprint) HostKeyRepository.OK else HostKeyRepository.CHANGED
                val reply = CompletableDeferred<Boolean>(); run.trustReply = reply
                change(run.id) { it.copy(trust=fingerprint) }
                val accepted = runBlocking { withTimeoutOrNull(120_000) { reply.await() } }==true
                change(run.id) { it.copy(trust="") }; run.trustReply = null
                if(!accepted) return HostKeyRepository.CHANGED
                server["hostKey"] = fingerprint; DeployStore.save(server)
                return HostKeyRepository.OK
            }
            override fun add(hostkey: HostKey?,userinfo: UserInfo?) {}
            override fun remove(host: String?,type: String?) {}
            override fun remove(host: String?,type: String?,key: ByteArray?) {}
            override fun getKnownHostsRepositoryID() = "Rawline encrypted servers"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?,type: String?): Array<HostKey> = emptyArray()
        }
        val ssh = jsch.getSession(server["user"],server["host"],server.port("sshPort"))
        run.session = ssh
        ssh.setSocketFactory(object : SocketFactory {
            override fun createSocket(host: String,port: Int) = java.net.Socket().also { run.socket = it; if(run.cancelled) it.close() }.apply { connect(java.net.InetSocketAddress(host,port),30_000) }
            override fun getInputStream(socket: java.net.Socket) = socket.getInputStream()
            override fun getOutputStream(socket: java.net.Socket) = socket.getOutputStream()
        })
        ssh.setConfig("StrictHostKeyChecking","yes")
        ssh.setConfig("PreferredAuthentications",if(server["auth"]=="key") "publickey" else "password,keyboard-interactive")
        if(server["auth"]!="key") ssh.setPassword(server["sshPassword"])
        ssh.setServerAliveInterval(15000); ssh.setServerAliveCountMax(4)
        ssh.connect(30_000)
        currentCoroutineContext().ensureActive()
        return ssh
    }
    private suspend fun exec(run: Run,ssh: Session,server: DeployServer,command: String,timeout: Long,root: Boolean = true): Pair<Int,String> {
        val channel = ssh.openChannel("exec") as ChannelExec
        val plan = if(root) rootStdinPlan(command,server["sudoPassword"].ifBlank { server["sshPassword"] },"") else null
        channel.setCommand((plan?.command ?: command)+" 2>&1")
        channel.setInputStream(plan?.input.orEmpty().byteInputStream())
        val input = channel.inputStream
        val output = StringBuilder(); var pending = ""
        val deadline = System.currentTimeMillis()+timeout
        val buffer = ByteArray(8192)
        try {
            channel.connect(30_000)
            while(true) {
                currentCoroutineContext().ensureActive()
                check(System.currentTimeMillis()<deadline) { "Превышено время ожидания SSH-команды" }
                while(input.available()>0) {
                    val count = input.read(buffer); if(count<0) break
                    val part = String(buffer,0,count,Charsets.UTF_8); output.append(part); pending += part
                    while(pending.contains('\n')) {
                        val line = redact(server,pending.substringBefore('\n')); pending = pending.substringAfter('\n')
                        val stage = DeployProtocol.progress("mrtt",line)
                        if(stage!=null) change(run.id) { it.copy(percent=stage.first,step=stage.second,installerProgress=true) }
                        else if(!line.startsWith("MRTT_DEPLOY_")) log(run.id,line)
                    }
                    if(output.length>1_000_000) output.delete(0,output.length-1_000_000)
                }
                if(channel.isClosed && input.available()==0) break
                delay(100)
            }
            return channel.exitStatus to output.toString()
        } finally { channel.disconnect() }
    }
    private suspend fun perform(run: Run,server: DeployServer,uninstall: Boolean) {
        server.validate(!uninstall)
        val ssh = connect(run,server)
        val machine = exec(run,ssh,server,"uname -m",30_000,root = false).second.trim()
        val asset = if(uninstall) "" else MrttBundle.serverAsset(machine)
        step(run.id,5,"Подготовка файлов")
        val bundle = MrttBundle.load()
        currentCoroutineContext().ensureActive()
        val sftp = ssh.openChannel("sftp") as ChannelSftp
        sftp.connect(30_000)
        try {
            fun upload(path: String,bytes: ByteArray) { sftp.put(bytes.inputStream(),path); sftp.chmod(384,path) }
            upload("/tmp/deploy.sh",bundle.getValue("deploy.sh"))
            if(!uninstall) {
                step(run.id,10,"Загрузка нового сервера")
                upload("/tmp/.mrtt-upload-server",bundle.getValue(asset))
                val host = server["host"].trim()
                val value = if(server["docker"]=="true") host else "\""+host.replace("\\","\\\\").replace("\"","\\\"")+"\""
                upload("/tmp/.mrtt-upload-web.env","MRTT_PUBLIC_IP=$value\n".toByteArray())
                upload("/tmp/.mrtt-upload-overrides.json",JSONObject().put("main_password",server["password"]).toString().toByteArray())
            }
        } finally { sftp.disconnect() }
        if(uninstall) {
            step(run.id,30,"Удаление через deploy.sh")
            exec(run,ssh,server,"env MRTT_PEER_PORT=${server.port("peerPort")} MRTT_SSH_PORT=${server.port("sshPort")} bash /tmp/deploy.sh uninstall",5*60_000L)
            // The cascade clients belong to MRTT and leave with it.
            // shortcut: their iptables rules stay until a reboot, without the interface they act on nothing.
            exec(run,ssh,server,"for u in /etc/systemd/system/mrtt-exit-*.service; do [ -e \"\$u\" ] || continue; systemctl disable --now \"\$(basename \"\$u\")\" >/dev/null 2>&1; rm -f \"\$u\"; done; rm -rf /etc/mrtt-exit /usr/local/lib/mrtt-exit-client; systemctl daemon-reload",60_000L)
            server.recordUninstall(); DeployStore.save(server)
            change(run.id) { it.copy(config="",configCleared=true) }
            step(run.id,100,"Удаление завершено")
            return
        }
        step(run.id,18,"Установка нового сервера")
        val environment = "env MRTT_PEER_PORT=${server.port("peerPort")} MRTT_SSH_PORT=${server.port("sshPort")} MRTT_WEB_PORT=${server.port("webPort")} MRTT_PUBLIC_IP=${DeployProtocol.quote(server["host"].trim())} MRTT_DEPLOY_MODE=${if(server["docker"]=="true") "docker" else "systemd"}"
        val result = exec(run,ssh,server,"$environment bash /tmp/deploy.sh install",30*60_000L)
        require(DeployProtocol.successful("mrtt",result.first,result.second)) { MrttBundle.failure(result.first,result.second) }
        // The owner's link is what this deployment gives; the server may still be starting its API.
        server["installed"] = "true"
        var link = ""
        for(attempt in 1..10) {
            link = runCatching { JSONArray(MrttAdmin(server).get("/api/links")).optJSONObject(0)?.optString("link").orEmpty() }.getOrDefault("")
            if(link.isNotEmpty()) break
            delay(1_000)
        }
        server.recordInstallation(link); DeployStore.save(server)
        change(run.id) { it.copy(config=link) }
        step(run.id,100,"Установка завершена")
    }
}
