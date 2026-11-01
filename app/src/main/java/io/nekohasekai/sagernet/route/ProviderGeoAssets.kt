package app.qthrone.mobile.route

import app.qthrone.mobile.ktx.*
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.outbound.json.JsonObject
import app.qthrone.core.mobile.Mobile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*

object ProviderGeoAssets {
    data class Status(val file: String = "", val bytes: Long = 0, val total: Long = 0, val error: String = "", val complete: Boolean = false)
    val states = kotlinx.coroutines.flow.MutableStateFlow<Map<Long, Status>>(emptyMap())
    private val expiryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val action get() = app.packageName + ".PROVIDER_GEO_STATUS"

    private fun show(gid: Long, value: Status) {
        synchronized(states) { states.value = states.value + (gid to value) }
        if (value.complete) expiryScope.launch {
            delay(5000)
            synchronized(states) { if (states.value[gid] == value) states.value = states.value - gid }
        }
    }

    /** Subscriptions refresh in :bg while the home screen is in the main process: every status goes to both. */
    private fun status(gid: Long, value: Status) {
        show(gid, value)
        app.sendBroadcast(android.content.Intent(action).setPackage(app.packageName).putExtra("gid", gid)
            .putExtra("file", value.file).putExtra("bytes", value.bytes).putExtra("total", value.total)
            .putExtra("error", value.error).putExtra("complete", value.complete), app.packageName + ".SERVICE")
    }

    fun dismiss() = synchronized(states) { states.value = emptyMap() }

    /** Called once in the main process. */
    fun listen() {
        androidx.core.content.ContextCompat.registerReceiver(app, object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                show(intent.getLongExtra("gid", 0), Status(intent.getStringExtra("file").orEmpty(), intent.getLongExtra("bytes", 0),
                    intent.getLongExtra("total", 0), intent.getStringExtra("error").orEmpty(), intent.getBooleanExtra("complete", false)))
            }
        }, android.content.IntentFilter(action), app.packageName + ".SERVICE", null, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    fun url(document: JsonObject, kind: String) = document.string(if (kind == "geoip") "Geoipurl" else "Geositeurl").ifBlank {
        "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/$kind.dat"
    }
    fun file(document: JsonObject, kind: String) = File(File(app.filesDir, "provider-geodata"), "${digest(url(document,kind))}.dat")
    @Synchronized
    fun prepare(gid: Long, document: JsonObject, force: Boolean = false) {
        try {
            for (kind in listOf("geosite", "geoip")) download(gid, document, kind, force)
            status(gid, Status(complete = true))
        } catch (error: Exception) { status(gid, Status(error = error.readableMessage)); throw error }
    }
    @Synchronized
    fun refresh(gid: Long, document: JsonObject, kind: String) {
        require(kind in listOf("geosite","geoip"))
        try {
            download(gid,document,kind,true)
            status(gid,Status(complete = true))
        } catch (error: Exception) { status(gid,Status(error = error.readableMessage)); throw error }
    }
    /**
     * The desktop's ResolveXrayGeo: an Xray profile's `geosite:` / `geoip:` rules point at this provider's own files
     * (`ext:` / `ext-ip:` in the asset dir), so the standard geosite.dat and geoip.dat stay for every other profile.
     */
    @Synchronized
    fun xrayConfig(document: JsonObject, config: String): String {
        var result = config
        for (kind in listOf("geosite", "geoip")) {
            if (!config.contains("$kind:")) continue
            val path = download(0, document, kind, false).relativeTo(app.filesDir).invariantSeparatorsPath
            val prefix = if (kind == "geoip") "ext-ip:" else "ext:"
            result = result.replace(Regex("\"(!?)$kind:")) { "\"${it.groupValues[1]}$prefix$path:" }
        }
        status(0, Status(complete = true))
        return result
    }

    /** Before 3.2.8 a provider's files replaced the standard ones; such a copy goes once, and the standard file downloads again. */
    fun dropInstalledCopies() {
        if (DataStore.providerGeoRestored) return
        DataStore.providerGeoRestored = true
        val own = File(app.filesDir, "provider-geodata").listFiles()?.filter { it.extension == "dat" }.orEmpty()
        for (name in listOf("geosite.dat", "geoip.dat")) {
            val installed = File(app.filesDir, name)
            if (installed.isFile && own.any { it.length() == installed.length() && it.readBytes().contentEquals(installed.readBytes()) }) installed.delete()
        }
    }
    private fun download(gid: Long, document: JsonObject, kind: String, force: Boolean): File {
        val source = file(document,kind)
        if (!force && source.isFile && System.currentTimeMillis() - source.lastModified() < 7 * 86400000L) return source
        val address = url(document,kind)
        require(address.startsWith("https://") || address.startsWith("http://")) { "Invalid geo file URL" }
        source.parentFile!!.mkdirs()
        val temp = File(source.parentFile, "${source.name}.${android.os.Process.myPid()}.${System.nanoTime()}.download")
        status(gid, Status(file = kind))
        try {
            val client = appHttpClient(serviceConnected(), 30).newBuilder().callTimeout(180, java.util.concurrent.TimeUnit.SECONDS).build()
            client.newCall(okhttp3.Request.Builder().url(address).build()).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val body = response.body ?: error("Empty geo file")
                val total = body.contentLength().coerceAtLeast(0)
                check(total <= 64L * 1024 * 1024) { "Geo file is too large" }
                body.byteStream().use { input -> temp.outputStream().use { output ->
                    val buffer = ByteArray(65536); var bytes = 0L; var shown = -1L
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        bytes += count; check(bytes <= 64L * 1024 * 1024) { "Geo file is too large" }
                        output.write(buffer,0,count)
                        val step = if (total > 0) bytes * 100 / total else bytes / (1024 * 1024)
                        if (step != shown) { shown = step; status(gid,Status(kind,bytes,total)) }
                    }
                    check(bytes > 0) { "Empty geo file" }
                } }
            }
            check(temp.renameTo(source)) { "Cannot save geo file" }
            return source
        } catch (error: Exception) {
            // An older copy beats a failed start: the network that refuses the download may be the reason to reconnect.
            if (force || !source.isFile || source.length() == 0L) throw error
            Logs.w("Provider $kind.dat update failed, the cached copy stays: ${error.readableMessage}")
            return source
        } finally { temp.delete() }
    }
    @Synchronized
    fun resolve(profile: RouteProfile, document: JsonObject, catalog: RuleSetCatalog): RuleSetCatalog {
        val entries = catalog.names().map { it to catalog.urlOf(it)!! }.toMutableList()
        val dir = File(app.filesDir, "provider-geodata").apply { mkdirs() }
        for (rule in profile.rules) {
            for (i in rule.rule_set.indices) {
                val selector = rule.rule_set[i]
                val kind = selector.substringBefore(':')
                if (kind != "geosite" && kind != "geoip") continue
                val url = document.string(if (kind == "geoip") "Geoipurl" else "Geositeurl").ifBlank {
                    "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/$kind.dat"
                }
                require(url.startsWith("https://") || url.startsWith("http://")) { "Invalid geo file URL" }
                val sourceId = digest(url)
                val source = download(0,document,kind,false)
                val key = digest("$url/$selector/${source.lastModified()}")
                val converted = File(dir, "$key.srs")
                if (!converted.isFile) {
                    val temp = File(dir, "$key.${android.os.Process.myPid()}.${System.nanoTime()}.tmp")
                    Mobile.convertGeoRuleSet(source.absolutePath, temp.absolutePath, kind, selector.substringAfter(':'))
                    check(temp.renameTo(converted)) { "Cannot save routing rules" }
                }
                val tag = "provider-$key"
                rule.rule_set[i] = tag
                entries.add(tag to "file://${converted.absolutePath}")
            }
        }
        status(0,Status(complete = true))
        return RuleSetCatalog(entries)
    }
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
