package app.qthrone.mobile.deploy

import app.qthrone.mobile.bg.carrier.NativeHashPolicy
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.group.DeviceDetails
import app.qthrone.mobile.ktx.app
import app.qthrone.mobile.bg.carrier.VkRelayAccount
import app.qthrone.mobile.outbound.types.WdttPlus
import com.jcraft.jsch.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

data class DeployState(val serverId: String = "",val running: Boolean = false,val percent: Int = 0,val step: String = "",val logs: String = "",val config: String = "",val trust: String = "",val installerProgress: Boolean = false,val configCleared: Boolean = false,val stopping: Boolean = false,val question: DeployQuestion? = null)

/** A choice the running operation needs from the user; the answer is an option index or -1. */
data class DeployQuestion(val title: String,val message: String,val options: List<String>)

object DeployEngine {
    val state = MutableStateFlow(DeployState())
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var session: Session? = null
    // Session.disconnect() is a no-op until the handshake ends, so Stop closes the socket itself.
    @Volatile private var socket: java.net.Socket? = null
    @Volatile private var download: okhttp3.Call? = null
    @Volatile private var trustReply: CompletableDeferred<Boolean>? = null
    @Volatile private var questionReply: CompletableDeferred<Int>? = null
    @Volatile private var rolledBack = false
    fun approveHost(approved: Boolean) { trustReply?.complete(approved) }
    fun answer(choice: Int) { questionReply?.complete(choice) }
    /** Blocking SSH, SFTP and download calls ignore coroutine cancellation, so each is broken off here. */
    fun cancel() {
        if(!state.value.running || state.value.stopping) return
        state.update { it.copy(stopping=true,step="Остановка…") }
        log("Остановка по запросу…")
        job?.cancel();trustReply?.complete(false);questionReply?.complete(-1);download?.cancel()
        scope.launch { session?.disconnect();runCatching { socket?.close() } }
    }
    /** [removeData] chooses WDTT Plus's full removal over keeping /etc/wdtt; other protocols ignore it. */
    fun start(server: DeployServer, uninstall: Boolean, removeData: Boolean = false) {
        if(state.value.running) return
        rolledBack = false
        val copy = DeployServer(JSONObject(server.json.toString()))
        if(copy["manualPorts"] != "true") {
            copy["peerPort"]=if(copy.kind=="csqtt") "46000" else "56000";copy["localPort"]="9000"
            copy["wgPort"]="56001";copy["directPort"]="56002";copy["rawPort"]="56003";copy["adminPort"]="56002";copy["webPort"]="46002"
        }
        state.value = DeployState(server.id,true,0,"Подключение",config=server["config"])
        job = scope.launch {
            try { run(copy,uninstall,removeData) }
            catch(e: Exception) {
                // Breaking off a blocking call surfaces as its own error; after Stop it is the stop, not a failure.
                if(e is CancellationException || state.value.stopping) {
                    log(if(rolledBack) "Операция остановлена. Изменения на VPS отменены, прежнее состояние восстановлено."
                        else "Операция остановлена. Уже выполненные на VPS шаги не откатываются: при необходимости запустите установку или удаление заново.")
                    update(state.value.percent,"Операция остановлена")
                } else { log("Ошибка: "+redact(copy,e.message ?: e.javaClass.simpleName));update(state.value.percent,"Ошибка установки или SSH") }
                if(e is CancellationException) throw e
            }
            finally { session?.disconnect();session = null;runCatching { socket?.close() };socket = null;download = null;state.update { it.copy(running=false,stopping=false,trust="",question=null) } }
        }
    }
    // Stop writes from the UI thread while the job logs, so these changes are atomic.
    private fun update(percent: Int,step: String) { state.update { it.copy(percent=percent,step=step) } }
    private fun log(line: String) { state.update { it.copy(logs=(it.logs+line+"\n").takeLast(200_000)) } }
    private suspend fun ask(title: String,message: String,vararg options: String): Int {
        val reply = CompletableDeferred<Int>();questionReply = reply
        state.update { it.copy(question=DeployQuestion(title,message,options.toList())) }
        try { return reply.await() } finally { questionReply = null;state.update { it.copy(question=null) } }
    }
    private fun redact(server: DeployServer,text: String): String {
        var result = text
        for(key in listOf("sshPassword","key","keyPassword","sudoPassword","password","webPassword","botToken","adminToken","secretPath")) {
            val secret = server[key]
            if(secret.isNotBlank()) result = result.replace(secret,"[скрыто]")
        }
        return result.replace(Regex("\\u001B\\[[0-9;]*[A-Za-z]"),"")
    }
    fun quote(value: String) = DeployProtocol.quote(value)
    private fun root(command: String): String = "if [ \"\$(id -u)\" = 0 ]; then bash -c ${quote(command)}; else sudo -S -p '' bash -c ${quote(command)}; fi"
    /** [stoppable] lets Stop break the handshake; a recovery connection after Stop must not be. */
    private suspend fun connect(server: DeployServer,stoppable: Boolean): Session {
        val jsch = JSch()
        val key = server["key"].toByteArray()
        if(server["auth"] == "key") jsch.addIdentity("qThrone",key,null,server["keyPassword"].takeIf { it.isNotEmpty() }?.toByteArray())
        // Host verification precedes sending a password or authenticating with the private key.
        jsch.hostKeyRepository = object : HostKeyRepository {
            override fun check(host: String, bytes: ByteArray): Int {
                val fingerprint = "SHA256:"+android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(bytes),android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                val expected = server["hostKey"]
                if(expected.isNotBlank()) return if(expected == fingerprint) HostKeyRepository.OK else HostKeyRepository.CHANGED
                val reply = CompletableDeferred<Boolean>();trustReply = reply
                state.update { it.copy(trust=fingerprint) }
                val accepted = runBlocking { withTimeoutOrNull(120_000) { reply.await() } } == true
                state.update { it.copy(trust="") };trustReply = null
                if(!accepted) return HostKeyRepository.CHANGED
                server["hostKey"] = fingerprint;DeployStore.save(server)
                return HostKeyRepository.OK
            }
            override fun add(hostkey: HostKey?,userinfo: UserInfo?) {}
            override fun remove(host: String?,type: String?) {}
            override fun remove(host: String?,type: String?,key: ByteArray?) {}
            override fun getKnownHostsRepositoryID() = "qThrone encrypted servers"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?,type: String?): Array<HostKey> = emptyArray()
        }
        val ssh = jsch.getSession(server["user"],server["host"],server.port("sshPort"))
        if(stoppable) session = ssh
        ssh.setSocketFactory(object : SocketFactory {
            override fun createSocket(host: String,port: Int) = java.net.Socket().also { if(stoppable) { socket = it;if(state.value.stopping) it.close() } }.apply { connect(java.net.InetSocketAddress(host,port),30_000) }
            override fun getInputStream(socket: java.net.Socket) = socket.getInputStream()
            override fun getOutputStream(socket: java.net.Socket) = socket.getOutputStream()
        })
        ssh.setConfig("StrictHostKeyChecking","yes")
        ssh.setConfig("PreferredAuthentications",if(server["auth"] == "key") "publickey" else "password,keyboard-interactive")
        if(server["auth"] != "key") ssh.setPassword(server["sshPassword"])
        ssh.setServerAliveInterval(15000);ssh.setServerAliveCountMax(4)
        if(stoppable) currentCoroutineContext().ensureActive()
        ssh.connect(30_000)
        if(stoppable) currentCoroutineContext().ensureActive()
        return ssh
    }
    private suspend fun run(server: DeployServer,uninstall: Boolean,removeData: Boolean) {
        server.validate(!uninstall)
        val ssh = connect(server,stoppable=true)
        val machine = exec(ssh,server,"uname -m",30_000).second.trim()
        if(server.kind == "wdttplus") { runWdttPlus(ssh,server,uninstall,removeData,machine);return }
        val source = if(server.kind == "qwdtt") Pair("https://github.com/makriq-org/qwdtt/releases/download/v1.4.4/qWDTT_v1.4.4.apk","ff5b2d1f265b0c4253cdf54ebf1f8a5d64afd1424086aa8b8be754b5c14df6a7")
            else Pair("https://github.com/ttrroyy/csqttr/releases/download/v2.5.0-legacy/CSQTT-universal.apk","bbc261eff2fce56b0ee774271416b0764b34ee16349e9e250ce3c8d45d660d0f")
        update(5,"Загрузка официального установщика")
        val apk = artifact(source.first,source.second)
        val binaryName = if(server.kind == "qwdtt") {
            require(machine == "x86_64" || uninstall) { "Официальный qWDTT установщик содержит сервер только для x86_64" }; "assets/server"
        } else when(machine) {
            "x86_64","amd64" -> "assets/csqtt-linux-amd64"
            "aarch64","arm64" -> "assets/csqtt-linux-arm64"
            "armv7l","armv7" -> "assets/csqtt-linux-armv7"
            else -> error("Архитектура VPS не поддерживается: $machine")
        }
        val sftp = ssh.openChannel("sftp") as ChannelSftp
        sftp.connect(30_000)
        try {
            fun upload(path: String,bytes: ByteArray) { sftp.put(bytes.inputStream(),path);sftp.chmod(384,path) }
            ZipFile(apk).use { zip ->
                fun entry(name: String): ByteArray {
                    val item = zip.getEntry(name) ?: error("Нет файла установщика $name")
                    require(item.size in 1..30_000_000)
                    return zip.getInputStream(item).use { it.readBytes() }
                }
                upload("/tmp/deploy.sh",entry("assets/deploy.sh"))
                if(!uninstall) {
                    // As in the original apps a new server is bound to this phone's own identity.
                    if(server["installed"]!="true") server.set("deviceId",ownDeviceId())
                    update(30,"Передача файлов на VPS")
                    val binary=entry(binaryName)
                    if(server.kind == "qwdtt") require(DeployProtocol.safeQwdttServer(binary)) { "Устаревший сервер qWDTT: требуется сборка для wdtt0 и /etc/wdtt" }
                    upload(if(server.kind == "qwdtt") "/tmp/wdtt-server" else "/tmp/.csqtt-upload-server",binary)
                    if(server.kind == "qwdtt") {
                        upload("/tmp/wdtt-main.password",server["password"].toByteArray())
                        upload("/tmp/wdtt-admin.token",server["adminToken"].toByteArray())
                        upload("/tmp/wdtt-bot.token",server["botToken"].toByteArray())
                    } else {
                        val docker=server["docker"] == "true"
                        val environment=linkedMapOf("CSQTT_WEB_USER" to server["webUser"],"CSQTT_WEB_PASS" to server["webPassword"],
                            "CSQTT_PUBLIC_IP" to server["host"],"CSQTT_WEB_SECRET_PATH" to server["secretPath"])
                        upload("/tmp/.csqtt-upload-web.env",environment.entries.joinToString("\n",postfix="\n") {
                            "${it.key}=${CsqttDeployPolicy.environmentValue(it.value,docker)}"
                        }.toByteArray())
                        upload("/tmp/.csqtt-upload-overrides.json",JSONObject().put("main_password",server["password"]).toString().toByteArray())
                    }
                }
            }
        } finally { sftp.disconnect() }
        val env = if(server.kind == "qwdtt") "WDTT_DTLS_PORT=${server.port("peerPort")} WDTT_WG_PORT=${server.port("wgPort")} WDTT_RAW_PORT=${server.port("rawPort")} WDTT_DIRECT_PORT=${server.port("directPort")} WDTT_ADMIN_PORT=${server.port("adminPort")} WDTT_SSH_PORT=${server.port("sshPort")} WDTT_ADMIN_ID=${quote(server["adminId"])} WDTT_DNS_SERVERS=${quote(server["dns1"]+","+server["dns2"])}"
            else "CSQTT_PEER_PORT=${server.port("peerPort")} CSQTT_WEB_PORT=${server.port("webPort")} CSQTT_SSH_PORT=${server.port("sshPort")} CSQTT_DEPLOY_MODE=${if(server["docker"] == "true") "docker" else "systemd"} CSQTT_PUBLIC_IP=${quote(server["host"])} CSQTT_WEB_SECRET_PATH=${quote(server["secretPath"])}"
        update(45,if(uninstall) "Удаление" else "Установка и запуск сервиса")
        val result = exec(ssh,server,root("env $env bash /tmp/deploy.sh ${if(uninstall) "uninstall" else "install"}"),30*60_000L)
        require(result.first == 0) { if(server.kind=="csqtt") CsqttDeployPolicy.failure(result.first,result.second) else "Установщик завершился с кодом ${result.first}" }
        if(!uninstall) {
            require(DeployProtocol.successful(server.kind,result.first,result.second)) { "Установщик не подтвердил запуск сервиса" }
            if(server.kind == "qwdtt") {
                val pin = Regex("WDTT_ADMIN_PIN\\|(sha256/[A-Za-z0-9+/=]+)").find(result.second)?.groupValues?.get(1).orEmpty()
                require(pin.isNotBlank()) { "Не получен TLS-отпечаток панели" };server["adminPin"] = pin
            }
            val json = JSONObject().put("type",server.kind).put("name",server["name"]).put("server",server["host"]).put("server_port",server.port("peerPort"))
                .put("password",server["password"]).put("device_id",server["deviceId"]).put("hashes",org.json.JSONArray())
                .put("hash_source",DataStore.configurationStore.getString(VkRelayAccount.DEFAULT_SOURCE,"manual"))
            if(server.kind == "qwdtt") json.put("raw_port",server.port("rawPort"))
            val config=DeployConfig.link(json,ownDeviceId())
            server.recordInstallation(config);DeployStore.save(server)
            state.update { it.copy(config=config) }
        } else {
            server.recordUninstall();DeployStore.save(server)
            state.update { it.copy(config="",configCleared=true) }
        }
        update(100,if(uninstall) "Удаление завершено" else "Установка завершена")
    }
    /**
     * WDTT Plus, as its app's performDeploy: root and ownership checks, a persistent server copy and a
     * rollback copy before an update, the prepared passwords.json, deploy.sh under the update lock, then
     * the binary hash, service, admin socket and preserved clients are verified; a failed update is rolled back.
     */
    private suspend fun runWdttPlus(ssh: Session,server: DeployServer,uninstall: Boolean,removeData: Boolean,machine: String) {
        val ops = wdttPlusOps(ssh,server)
        update(3,"Проверяю root-права...");ops.verifyRootAccess()
        if(uninstall) { uninstallWdttPlus(ops,server,if(removeData) ServerUninstallMode.RemoveAll else ServerUninstallMode.PreserveData);return }
        require(machine == "x86_64" || machine == "amd64") { "Официальный сервер WDTT Plus собран только для x86_64" }
        resolveEarlierWdttPlusUpdate(ops,server)
        update(4,"Проверяю владельца установки...")
        val ownership = ops.ownership().let { found ->
            if(found != DeploymentOwnership.UnknownExisting || !ops.hasUnmarkedQthroneData()) return@let found
            require(ask("Данные WDTT Plus без службы","На VPS остались данные WDTT Plus (клиенты, ключи WireGuard) без службы и сервера: так их оставляло удаление в qThrone 1.8.1 и раньше. " +
                "Если это ваш сервер, данные будут отмечены как сохранённые и подхвачены установкой.","Сохранить данные и установить") == 0) { "Установка отменена: данные на VPS не подтверждены" }
            ops.adoptUnmarkedQthroneData()
            ops.ownership()
        }
        val mode = when(ownership) {
            DeploymentOwnership.NoInstall -> DeployMode.FreshInstall
            DeploymentOwnership.IncompleteAndroidDeploy -> {
                // WDTT Plus offers only a full reset for an incomplete Android install.
                require(ask("Неполная установка WDTT Plus","На VPS осталась неполная установка WDTT Plus. Её можно только сбросить: /etc/wdtt, служба и сервер будут удалены, затем сервер установится заново. Перед сбросом создаётся страховочная копия.",
                    "Сбросить и установить") == 0) { "Установка отменена: неполную установку можно только сбросить" }
                DeployMode.ResetAll
            }
            DeploymentOwnership.AndroidDeploy, DeploymentOwnership.PreservedAndroidData -> {
                when(ask("Деплой WDTT Plus","Обновление сохранит клиентов, привязки устройств, ключи и статистику. Полный сброс удалит эти данные; перед ним создаётся страховочная копия.",
                    "Обновить с сохранением", "Сбросить и установить")) {
                    0 -> DeployMode.PreserveData
                    1 -> {
                        require(ask("Сброс данных WDTT Plus","Будут удалены все клиенты, пароли, привязки и ключи WireGuard на этом сервере. Старые ссылки перестанут работать.",
                            "Удалить данные и установить заново") == 0) { "Сброс отменён" }
                        DeployMode.ResetAll
                    }
                    else -> error("Установка отменена")
                }
            }
            // The standalone installer and unknown installs are refused below with WDTT Plus's messages.
            else -> DeployMode.PreserveData
        }
        assertDeploymentMayBeUpdated(ownership,mode)
        if(ownership == DeploymentOwnership.AndroidDeploy) {
            // A server whose owner password was lost cannot authenticate an admin backup.
            // Preserve its original files through the rollback path before repairing that field.
            val current = ops.readRemotePasswordsJson()
            val recoverOwner = mode == DeployMode.PreserveData && current != null &&
                validatePasswordsDbForPreserving(JSONObject(current),server["password"])
            if(recoverOwner) log("Главный пароль отсутствует в базе. Он будет восстановлен из параметров сервера; клиенты и ключи сохранятся в страховочной копии.")
            else { update(5,"Создаю постоянную копию перед деплоем...");createPersistentSafetyBackup(server) }
        }
        update(6,"Загрузка официального установщика")
        val apk = artifact("https://github.com/Ivan4537/WDTT-Plus/releases/download/v20/WDTT-Plus-v20-arm64-v8a-release.apk",
            "918cc7ab477247ebdaca030c13c0d689ca1a9f59152217f8edf0484a943da0e3")
        val (script,binary) = ZipFile(apk).use { zip ->
            fun entry(name: String): ByteArray {
                val item = zip.getEntry(name) ?: error("Нет файла установщика $name")
                require(item.size in 1..30_000_000)
                return zip.getInputStream(item).use { it.readBytes() }
            }
            entry("assets/deploy.sh") to entry("assets/server")
        }
        require(!unsafeLegacyWdttPlusServer(binary)) { "server asset выглядит устаревшим: найдено wg0 или /etc/wireguard без /etc/wdtt и wdtt0" }
        val expectedServerSha256 = java.security.MessageDigest.getInstance("SHA-256").digest(binary).joinToString("") { "%02x".format(it.toInt() and 255) }
        val desiredDns = normalizedDeployDns(server["dns"].ifBlank { "1.1.1.1" })
        val ports = "${server.port("peerPort")},${server.port("wgPort")},${server.port("localPort")}"
        val attemptId = java.util.UUID.randomUUID().toString()
        var preparationStarted = false
        var rollbackPrepared = false
        var installationVerified = false
        var preservedDbJson: String? = null
        var staging: String? = null
        try {
            var currentDbJsonForDeploy: String? = null
            if(mode == DeployMode.PreserveData || (mode == DeployMode.ResetAll && ownership in setOf(DeploymentOwnership.AndroidDeploy,
                    DeploymentOwnership.PreservedAndroidData,DeploymentOwnership.IncompleteAndroidDeploy))) {
                update(26,"Проверка сохранённых данных...")
                // Reset is also the recovery path for an incomplete or corrupt database.
                // Back up the raw files, without requiring a usable main_password in discarded data.
                fun validated(json: String) = json.also { validatePasswordsDbForPreserving(JSONObject(it),server["password"]) }
                var currentDbJson = if(mode == DeployMode.PreserveData) ops.readRemotePasswordsJson()?.let(::validated) else null
                if(mode == DeployMode.PreserveData && currentDbJson == null) throw IllegalStateException(
                    "обновление с сохранением остановлено: на сервере нет passwords.json; создание пустой базы вместо неизвестного состояния запрещено")
                preparationStarted = true
                update(28,"Создаю страховочную копию...")
                ops.prepareServerUpdateRollback(attemptId)
                rollbackPrepared = true
                // The first read was preflight only. The daemon has now flushed and stopped: use this stable database.
                currentDbJson = if(mode == DeployMode.PreserveData) ops.readRemotePasswordsJson()?.let(::validated) else null
                if(mode == DeployMode.PreserveData && currentDbJson == null) throw IllegalStateException("После подготовки копии база сервера не найдена; обновление остановлено")
                currentDbJsonForDeploy = currentDbJson
                if(mode == DeployMode.PreserveData) preservedDbJson = currentDbJson
                if(ownership == DeploymentOwnership.PreservedAndroidData) ops.markPreservedAndroidData()
            }
            val database = prepareDeployDatabaseJson(if(mode == DeployMode.PreserveData) currentDbJsonForDeploy else null,
                server["password"],"","",desiredDns,ports)
            update(32,"Загрузка на сервер...")
            val dir = exec(ssh,server,"mktemp -d /tmp/wdtt-plus-deploy.XXXXXX",30_000,quiet=true).second.trim().lines().last()
            require(dir.matches(Regex("""/tmp/wdtt-plus-deploy\.[A-Za-z0-9]+"""))) { "Не удалось подготовить временный каталог" }
            staging = dir
            exec(ssh,server,"chmod 700 ${quote(dir)}",30_000,quiet=true)
            val sftp = ssh.openChannel("sftp") as ChannelSftp
            sftp.connect(30_000)
            try {
                fun upload(path: String,bytes: ByteArray,permissions: Int) { sftp.put(bytes.inputStream(),path);sftp.chmod(permissions,path) }
                upload("$dir/deploy.sh",script,384)
                upload("$dir/server",binary,448)
                upload("$dir/passwords.json",database.toByteArray(),384)
            } finally { sftp.disconnect() }
            if(mode == DeployMode.ResetAll) {
                update(40,"Сброс старых данных...")
                require(ops.ownership() == ownership) { "состояние установки изменилось после проверки; сброс остановлен" }
                assertDeploymentMayBeUpdated(ownership,DeployMode.ResetAll)
                ops.execChecked(serverUpdateApplyGuardScript("wdtt_stop_service; pkill -x wdtt-server 2>/dev/null || true; rm -rf /etc/wdtt; " +
                    "rm -f /etc/systemd/system/wdtt.service /usr/local/bin/wdtt-server; systemctl daemon-reload",attemptId),30_000L)
            }
            update(45,when(mode) { DeployMode.PreserveData -> "Обновление с сохранением данных"; DeployMode.ResetAll -> "Сброс и установка"; else -> "Установка и запуск сервиса" })
            val installCommand = "env WDTT_DTLS_PORT=${server.port("peerPort")} WDTT_WG_PORT=${server.port("wgPort")} WDTT_SSH_PORT=${server.port("sshPort")} " +
                "WDTT_INSTALL_MODE=${when(mode) { DeployMode.FreshInstall -> "fresh"; DeployMode.PreserveData -> "preserve"; DeployMode.ResetAll -> "reset" }} " +
                "WDTT_PRESERVE_DATA=${if(mode == DeployMode.PreserveData) 1 else 0} " +
                "WDTT_STAGED_SERVER=${quote("$dir/server")} WDTT_STAGED_DB=${quote("$dir/passwords.json")} " +
                "bash ${quote("$dir/deploy.sh")}"
            val (code,output) = exec(ssh,server,rootCommand(if(rollbackPrepared) serverUpdateApplyGuardScript(installCommand,attemptId)
                else serverUpdateShellContext()+"\nwdtt_lock\n"+installCommand),30*60_000L)
            if(code != 0 || Regex("^error:",RegexOption.MULTILINE).containsMatchIn(output))
                throw IllegalStateException("Серверная операция не завершена:\n"+serverUpdateFailureDetail(output))
            check(markerValue(output,"WDTT_INSTALL_RESULT") == "success") { "не удалось подтвердить установку: нет итогового подтверждения скрипта" }
            update(92,"Проверяю установку...")
            val missing = wdttPlusInstallMissing(ops.exec(WDTT_PLUS_VERIFY_INSTALL,20_000L),expectedServerSha256)
            check(missing.isEmpty()) { "скрипт завершился, но проверка не прошла: $missing" }
            val updatedDbJson = ops.readRemotePasswordsJson() ?: throw IllegalStateException("после обновления не найдена база passwords.json")
            validatePasswordsDbStructure(JSONObject(updatedDbJson))
            preservedDbJson?.let { validatePreservedServerState(it,updatedDbJson) }
            installationVerified = true
            if(rollbackPrepared) {
                // Data, binary identity and service have been verified; a cleanup failure must not revert a working server.
                rollbackPrepared = false
                runCatching { ops.cleanupServerUpdateRollback(attemptId) }
                    .onFailure { log("Сервер обновлён. Очистка страховочной копии не завершена: ${redact(server,it.message.orEmpty())}") }
            }
            val link = WdttPlus().apply {
                this.server = server["host"]; serverPort = server.port("peerPort"); wgPort = server.port("wgPort"); localPort = server.port("localPort")
                password = server["password"]; name = server["name"].ifBlank { "WDTT Plus" }
            }.exportToLink()
            server.recordInstallation(link);DeployStore.save(server)
            state.update { it.copy(config=link) }
            update(100,if(mode == DeployMode.PreserveData) "Обновление завершено" else "Установка завершена")
        } catch(e: Exception) {
            if(preparationStarted && !installationVerified) withContext(NonCancellable) { recoverWdttPlus(ssh,server,attemptId) }
            throw e
        } finally {
            staging?.let { dir -> withContext(NonCancellable) { if(ssh.isConnected) runCatching {
                exec(ssh,server,rootCommand("rm -f -- ${quote("$dir/deploy.sh")} ${quote("$dir/server")} ${quote("$dir/passwords.json")}; rmdir -- ${quote(dir)}"),10_000,quiet=true)
            } } }
        }
    }
    /** One recovery connection after a failed or stopped update, as WDTT Plus's recoverServerUpdateAfterFailure. */
    private suspend fun recoverWdttPlus(ssh: Session,server: DeployServer,attemptId: String) {
        log("Восстанавливаю прежнее состояние сервера…")
        runCatching {
            val recovery = if(ssh.isConnected) ssh else connect(server,stoppable=false)
            try { wdttPlusOps(recovery,server).recoverServerUpdateAfterFailure(attemptId) } finally { if(recovery !== ssh) recovery.disconnect() }
        }.onSuccess { rolledBack = true;log("Прежнее состояние WDTT Plus восстановлено.") }
            .onFailure { log("Откат не выполнен: ${redact(server,it.message.orEmpty())}. При следующей установке приложение предложит восстановить прежнее состояние.") }
    }
    /** A rollback copy left by an earlier attempt blocks the update until the user resolves it, as in WDTT Plus. */
    private suspend fun resolveEarlierWdttPlusUpdate(ops: WdttPlusDeployOps,server: DeployServer) {
        val title = "Незавершённое обновление WDTT Plus"
        val intro = "На сервере остались данные предыдущей попытки обновления WDTT Plus. "
        val action = when(val state = ops.inspectServerUpdateRollback()) {
            ServerUpdateRollbackState.None -> return
            ServerUpdateRollbackState.PreparationIncomplete -> ServerUpdateRollbackAction.CancelPreparation.takeIf {
                ask(title,intro+"Подготовка страховочной копии была прервана до замены сервера. Можно восстановить прежнее состояние службы и сохранить незавершённую копию отдельно. Настройки и бинарник не заменяются.",
                    "Восстановить работу и сохранить копию отдельно") == 0 }
            ServerUpdateRollbackState.Committed -> ServerUpdateRollbackAction.KeepCurrentAndDeleteBackup.takeIf {
                ask(title,intro+"Обновление было подтверждено, но удаление страховочной копии не завершилось. Приложение проверит рабочую установку перед удалением только этой копии.",
                    "Проверить сервер и удалить копию") == 0 }
            is ServerUpdateRollbackState.PreparedValid -> when(ask(title,intro+"Копия проверена и готова к восстановлению. Если текущая установка уже полностью работает, можно удалить только эту копию: " +
                    "приложение сначала проверит бинарник, конфигурацию, unit и активную службу.","Восстановить прежнее состояние","Текущее работает — удалить копию")) {
                0 -> ServerUpdateRollbackAction.RestorePrevious
                1 -> ServerUpdateRollbackAction.KeepCurrentAndDeleteBackup
                else -> null
            }
            is ServerUpdateRollbackState.PreparedCorrupted -> {
                check(state.diagnostic == "missing or unsafe state") { "Страховочная копия неполная или не прошла проверку: ${state.diagnostic}. Нужна ручная проверка сервера по SSH" }
                ServerUpdateRollbackAction.ArchiveLegacyPreparation.takeIf {
                    ask(title,intro+"Страховочная копия неполная. Если сервер работает, приложение может проверить установку и сохранить старую копию отдельно, не удаляя её.",
                        "Проверить сервер и сохранить копию отдельно") == 0 }
            }
            is ServerUpdateRollbackState.UnknownState -> error("Состояние страховочной копии не распознано: ${state.diagnostic}. Обычный деплой заблокирован до ручной проверки по SSH")
        } ?: error("Установка отменена: сначала нужно разобраться с копией прошлого обновления")
        update(4,"Обрабатываю копию прошлого обновления...")
        log(ops.resolveServerUpdateRollback(action,server["password"]))
    }
    /** WDTT Plus's createPersistentSafetyBackup: an old server without backups keeps only the rollback copy. */
    private suspend fun createPersistentSafetyBackup(server: DeployServer) {
        try { runInterruptible { WdttPlusAdmin(server).createBackup("pre_deploy") } }
        catch(e: Exception) {
            if(e is CancellationException) throw e
            val message = e.message.orEmpty().lowercase()
            if(listOf("не поддерживает","неизвестная admin-команда","admin_socket_unavailable","старый wdtt-server").any { it in message })
                log("Постоянная копия недоступна на прежней версии сервера; страховочная копия обновления остаётся включённой.")
            else throw IllegalStateException("не удалось создать обязательную серверную копию перед изменением: ${e.message}",e)
        }
    }
    /** WDTT Plus's performUninstall: its own steps and their verification, keeping or removing /etc/wdtt. */
    private suspend fun uninstallWdttPlus(ops: WdttPlusDeployOps,server: DeployServer,mode: ServerUninstallMode) {
        update(12,"Проверяю владельца установки...")
        ops.assertAndroidDeployMayManageServer("удаления")
        if(mode == ServerUninstallMode.PreserveData) { update(16,"Защищаю сохраняемые данные...");ops.markPreservedAndroidData() }
        wdttPlusUninstallSteps(mode,server.port("sshPort"),server.port("peerPort"),server.port("wgPort")).forEachIndexed { index,(step,command) ->
            update(22+index*16,step);ops.exec(command,15_000L)
        }
        update(94,"Проверяю результат...")
        require(wdttPlusUninstallVerified(ops.exec(wdttPlusUninstallProbe(mode),15_000L),mode)) {
            "удаление завершилось не полностью; проверьте состояние сервера и повторите операцию"
        }
        server.recordUninstall();DeployStore.save(server)
        state.update { it.copy(config="",configCleared=true) }
        update(100,if(mode == ServerUninstallMode.PreserveData) "Удаление завершено, данные сохранены" else "Удаление завершено, данные удалены")
    }
    private fun wdttPlusOps(ssh: Session,server: DeployServer) = WdttPlusDeployOps { command,timeout ->
        exec(ssh,server,command,timeout,quiet=true).let { (code,output) -> when {
            code < 0 -> output.trimEnd()+"\nerror: SSH channel closed before exit status\n"
            code != 0 -> output.trimEnd()+"\nerror: remote command exited with code $code\n"
            else -> output
        } }
    }

    private suspend fun artifact(url: String,sha: String): File {
        val target = File(app.cacheDir,"deploy-$sha.apk")
        fun digest(file: File): String {
            val hash = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536);while(true) { val count=input.read(buffer);if(count<0) break;hash.update(buffer,0,count) } }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        if(target.exists() && digest(target) == sha) return target
        target.delete()
        val http = OkHttpClient.Builder().callTimeout(5,TimeUnit.MINUTES).build()
        try {
            val call=http.newCall(Request.Builder().url(url).build());download=call
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                check(response.isSuccessful) { "Не удалось загрузить установщик: HTTP ${response.code}" }
                val body=response.body ?: error("Пустой установщик");val total=body.contentLength()
                body.byteStream().use { input -> target.outputStream().use { output ->
                    val buffer=ByteArray(65536);var read=0L
                    while(true) { currentCoroutineContext().ensureActive();val count=input.read(buffer);if(count<0) break;read+=count;check(read<=80_000_000);output.write(buffer,0,count);update(if(total>0) 5+(read*20/total).toInt() else 5,"Загрузка установщика") }
                } }
            }
            check(digest(target) == sha) { "Контрольная сумма установщика не совпала" }
            return target
        } catch(e: Exception) { target.delete();throw e }
    }
    /** [quiet] keeps a command's output, such as a base64 copy of passwords.json, out of the console. */
    private suspend fun exec(ssh: Session,server: DeployServer,command: String,timeout: Long,quiet: Boolean = false): Pair<Int,String> {
        val channel=ssh.openChannel("exec") as ChannelExec
        val plan=deployStdinPlan(command,server["sudoPassword"].ifBlank { server["sshPassword"] })
        channel.setCommand("${plan?.command ?: command} 2>&1")
        channel.setInputStream(plan?.input.orEmpty().byteInputStream())
        val input=channel.inputStream
        val output=StringBuilder();var pending=""
        val deadline=System.currentTimeMillis()+timeout
        try {
            channel.connect(30_000)
            val buffer=ByteArray(8192)
            while(true) {
                currentCoroutineContext().ensureActive()
                check(System.currentTimeMillis()<deadline) { "Превышено время ожидания SSH-команды" }
                while(input.available()>0) {
                    val count=input.read(buffer);if(count<0) break
                    val part=String(buffer,0,count,Charsets.UTF_8);output.append(part);pending+=part
                    while(pending.contains('\n')) {
                        val line=redact(server,pending.substringBefore('\n'));pending=pending.substringAfter('\n')
                        val stage=DeployProtocol.progress(server.kind,line)
                        if(stage!=null) state.update { it.copy(percent=stage.first,step=stage.second,installerProgress=true) }
                        else if(!quiet) log(line)
                    }
                    if(output.length>1_000_000) output.delete(0,output.length-1_000_000)
                }
                if(channel.isClosed && input.available()==0) break
                delay(100)
            }
            if(pending.isNotBlank() && !quiet) log(redact(server,pending))
            return channel.exitStatus to output.toString()
        } finally { channel.disconnect() }
    }
    private fun ownDeviceId(): String =
        NativeHashPolicy.defaultDeviceId(DeviceDetails.get().hwid)
}
