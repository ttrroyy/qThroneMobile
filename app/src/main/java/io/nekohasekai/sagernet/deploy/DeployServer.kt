package app.rawline.mobile.deploy

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.rawline.mobile.ktx.app
import app.rawline.mobile.outbound.link.Hosts
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DeployServer(val json: JSONObject) {
    val id: String get() = json.getString("id")
    val kind: String get() = json.getString("kind")
    operator fun get(key: String): String = json.optString(key)
    operator fun set(key: String,value: String) { json.put(key,value) }
    fun isEmptyDraft(): Boolean {
        val defaults=create(kind).json
        val generated=setOf("id","deviceId","adminToken","secretPath")
        val keys=(json.keys().asSequence().toSet()+defaults.keys().asSequence().toSet())-generated
        return keys.all { json.optString(it)==defaults.optString(it) }
    }
    fun port(key: String): Int {
        val value=if(kind=="csqtt" && get("manualPorts")!="true") when(key) {
            "sshPort" -> "22";"peerPort" -> "46000";"webPort" -> "46002";else -> get(key)
        } else get(key)
        return value.toInt().also { require(it in 1..65535) }
    }
    fun recordInstallation(config: String) { set("installed","true");set("config",config) }
    fun recordUninstall() { set("installed","false");set("config","") }
    fun panelUrl(): String = "https://"+Hosts.displayAddress(get("host").ifBlank { "IP" },
        if(get("manualPorts")=="true") port("webPort") else 46002)+get("secretPath").takeIf { it.isNotBlank() }?.let { "/$it" }.orEmpty()
    fun editSignature(): String = json.keys().asSequence()
        .filterNot { json.optString(it).isEmpty() || it in setOf("config","installed","hostKey","adminPin") || (it in setOf("manualPorts","docker") && json.optString(it)!="true") }.sorted()
        .joinToString(";") { key -> val value=json.optString(key);"${key.length}:$key${value.length}:$value" }
    fun validateAddress() {
        require(get("host").matches(Regex("[A-Za-z0-9.:_-]+"))) { "Укажите IP или домен без схемы и порта" }
        require(get("user").matches(Regex("[A-Za-z0-9_.-]+"))) { "Укажите логин SSH" }
    }
    fun validate(install: Boolean) {
        require(kind in listOf("qwdtt","csqtt","wdttplus"))
        validateAddress()
        for(key in listOf("sshPort","peerPort")) port(key)
        when(kind) {
            "qwdtt" -> { port("wgPort"); port("rawPort"); port("adminPort"); port("directPort") }
            "wdttplus" -> { port("wgPort"); port("localPort") }
            else -> port("webPort")
        }
        if(get("auth") == "key") require(get("key").isNotBlank()) { "Загрузите SSH-ключ" }
        else require(get("sshPassword").isNotBlank()) { "Введите пароль SSH" }
        if(!install) return
        require(get("password").isNotBlank() && get("password").none { it == '|' }) { "Задайте пароль туннеля" }
        for(key in listOf("password","webUser","webPassword","botToken")) require(get(key).none { it == '\n' || it == '\r' || it == '\u0000' })
        if(kind == "wdttplus") {
            require(get("password").length in 8..256) { "Главный пароль: от 8 до 256 символов" }
            require(get("dns").isBlank() || get("dns").matches(Regex("[0-9a-fA-F:.,]{1,255}"))) { "DNS: адреса через запятую" }
        } else if(kind == "csqtt") {
            require(get("password").matches(Regex("[A-Za-z0-9]{16}"))) { "Пароль туннеля: ровно 16 латинских букв и цифр" }
            require(get("webUser").matches(Regex("[A-Za-z0-9]+")) && get("webPassword").matches(Regex("[A-Za-z0-9]+"))) { "Логин и пароль панели: латиница и цифры" }
            require(get("secretPath").matches(Regex("[A-Za-z0-9_-]{16,128}"))) { "Создайте секретный путь панели" }
        }
        else {
            require(get("adminId").all(Char::isDigit))
            for(key in listOf("dns1","dns2")) require(get(key).split('.').let { it.size == 4 && it.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true } }) { "DNS должен быть IPv4-адресом" }
        }
    }
    companion object {
        fun create(kind: String) = DeployServer(JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("kind",kind)).apply {
            set("name","");set("user","root");set("auth","password");set("sshPort","22")
            set("peerPort",if(kind == "csqtt") "46000" else "56000");set("localPort","9000");set("dns","")
            set("wgPort","56001");set("directPort","56002");set("rawPort","56003");set("adminPort","56002");set("webPort","46002")
            set("dns1","1.1.1.1");set("dns2","1.0.0.1");set("deviceId",java.util.UUID.randomUUID().toString())
            set("adminToken",java.util.UUID.randomUUID().toString()+java.util.UUID.randomUUID().toString())
            if(kind == "csqtt") set("secretPath",java.util.UUID.randomUUID().toString())
        }
    }
}

/** SSH credentials are local-only: encrypted, excluded from backups and profile exports. */
object DeployStore {
    private fun file() = AtomicFile(File(app.noBackupFilesDir,"deploy-servers"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("qThrone.deploy",null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES","AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("qThrone.deploy",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes("GCM").setEncryptionPaddings("NoPadding").build())
        }.generateKey()
    }
    @Synchronized fun all(): List<DeployServer> {
        if(!file().baseFile.exists()) return emptyList()
        val bytes = file().readFully()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        val items = JSONArray(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
        return (0 until items.length()).map { DeployServer(items.getJSONObject(it)) }
    }
    @Synchronized fun save(server: DeployServer) {
        val items = all().filter { it.id != server.id } + server
        write(items)
    }
    @Synchronized fun remove(id: String) { write(all().filter { it.id != id }) }
    @Synchronized fun savedServers(): List<DeployServer> {
        val stored=all()
        val kept=stored.filterNot { it.isEmptyDraft() }
        if(kept.size!=stored.size) write(kept)
        return kept
    }
    @Synchronized fun removeEmptyDrafts() { savedServers() }
    private fun write(items: List<DeployServer>) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val data = cipher.iv+cipher.doFinal(JSONArray(items.map { it.json }).toString().toByteArray(Charsets.UTF_8))
        val output = file().startWrite()
        try { output.write(data);file().finishWrite(output) } catch(e: Exception) { file().failWrite(output);throw e }
    }
}
