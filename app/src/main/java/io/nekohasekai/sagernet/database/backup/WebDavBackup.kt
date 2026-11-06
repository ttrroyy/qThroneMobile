package app.rawline.mobile.database.backup

import android.util.Xml
import app.rawline.mobile.BuildConfig
import app.rawline.mobile.R
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.ktx.app
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** `.rwlbackup` files in `<webdavServer>/<webdavPath>` over WebDAV with Basic auth (the device-local settings). */
class WebDavBackup private constructor(private val base: HttpUrl, private val segments: List<String>) {

    /** A backup in the folder; [modified] is epoch milliseconds, 0 when the server does not say. */
    class Remote(val name: String, val url: HttpUrl, val size: Long, val modified: Long)

    private val authorization = Credentials.basic(DataStore.webdavUsername.orEmpty(), DataStore.webdavPassword.orEmpty())

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val directory: HttpUrl = base.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()

    /** Uploads [file] as [name], creating the folder first when it is missing. */
    fun upload(file: File, name: String) {
        ensureDirectory()
        val request = request(directory.newBuilder().addPathSegment(name).build())
            .put(file.asRequestBody(OCTET_STREAM.toMediaType()))
            .build()
        client.newCall(request).execute().use { requireOk(it) }
    }

    /** The folder's `.rwlbackup` files, newest first; empty when the folder does not exist. */
    fun list(): List<Remote> {
        val request = request(directory)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
            .build()
        return client.newCall(request).execute().use { response ->
            if (response.code == 404) return@use emptyList()
            requireOk(response)
            val body = response.body ?: return@use emptyList()
            body.byteStream().use { parse(it) }
        }.sortedWith(compareByDescending<Remote> { sortKey(it) }.thenByDescending { it.name })
    }

    /** Streams [remote] into [block]. */
    fun <T> download(remote: Remote, block: (InputStream) -> T): T {
        val request = request(remote.url).get().build()
        return client.newCall(request).execute().use { response ->
            requireOk(response)
            val body = response.body ?: throw IOException(app.getString(R.string.webdav_server_error))
            body.byteStream().use(block)
        }
    }

    private fun request(url: HttpUrl) = Request.Builder().url(url).header("Authorization", authorization)

    /** PROPFIND the folder; on 404 MKCOL each configured segment in turn (405 = already there). */
    private fun ensureDirectory() {
        val probe = request(directory).method("PROPFIND", null).header("Depth", "0").build()
        val code = client.newCall(probe).execute().use { response ->
            if (response.code != 404) requireOk(response)
            response.code
        }
        if (code != 404) return
        var url = base
        for (segment in segments) {
            url = url.newBuilder().addPathSegment(segment).build()
            val mkcol = request(url).method("MKCOL", null).build()
            client.newCall(mkcol).execute().use { response ->
                if (!response.isSuccessful && response.code != 405) {
                    throw IOException(app.getString(R.string.webdav_create_dir_failed))
                }
            }
        }
    }

    private fun requireOk(response: Response) {
        if (response.isSuccessful) return
        throw IOException(
            when (response.code) {
                401 -> app.getString(R.string.webdav_auth_error)
                403 -> app.getString(R.string.webdav_permission_denied)
                404 -> app.getString(R.string.webdav_server_not_found)
                in 500..599 -> app.getString(R.string.webdav_server_error)
                else -> app.getString(R.string.webdav_connect_failed, response.code)
            }
        )
    }

    /** A multistatus body: each `response` whose `href` is a file named `*.rwlbackup` (namespace prefixes vary). */
    private fun parse(input: InputStream): List<Remote> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(input, null)
        val out = ArrayList<Remote>()
        val text = StringBuilder()
        var href: String? = null
        var size = -1L
        var modified = 0L
        var collection = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    text.setLength(0)
                    when (parser.name.lowercase(Locale.ROOT)) {
                        "response" -> {
                            href = null
                            size = -1L
                            modified = 0L
                            collection = false
                        }

                        "collection" -> collection = true
                    }
                }

                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name.lowercase(Locale.ROOT)) {
                    "href" -> href = text.toString().trim()
                    "getcontentlength" -> size = text.toString().trim().toLongOrNull() ?: -1L
                    "getlastmodified" -> modified = parseHttpDate(text.toString().trim())
                    "response" -> {
                        val url = href?.let { directory.resolve(it) }
                        val name = url?.pathSegments?.lastOrNull { it.isNotEmpty() }
                        if (!collection && url != null && name != null && (name.endsWith(EXTENSION, ignoreCase = true))) {
                            out.add(Remote(name, url, size, modified))
                        }
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun parseHttpDate(text: String): Long = try {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
            .parse(text)?.time ?: 0L
    } catch (e: Exception) {
        0L
    }

    /** The timestamp our file names carry, else the modification time, as `yyyyMMdd_HHmmss`. */
    private fun sortKey(remote: Remote): String = STAMP.find(remote.name)?.value
        ?: if (remote.modified > 0) SimpleDateFormat(STAMP_FORMAT, Locale.US).format(Date(remote.modified)) else ""

    companion object {
        const val EXTENSION = ".rwlbackup"
        /** Backups made before the rename stay listed and restorable. */
        private const val OCTET_STREAM = "application/octet-stream"
        private const val STAMP_FORMAT = "yyyyMMdd_HHmmss"
        private val STAMP = Regex("\\d{8}_\\d{6}")
        private const val PROPFIND_BODY = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/>" +
            "</d:prop></d:propfind>"

        /** The configured server, or null when there is none. */
        fun configured(): WebDavBackup? {
            val server = DataStore.webdavServer?.trim().orEmpty()
            if (server.isEmpty()) return null
            if (!server.startsWith("http://", true) && !server.startsWith("https://", true)) {
                throw IOException("Invalid server URL: must start with http:// or https://")
            }
            val base = server.toHttpUrlOrNull() ?: throw IOException("Invalid server URL: $server")
            val path = DataStore.webdavPath?.trim('/')?.takeIf { it.isNotBlank() } ?: "Rawline"
            return WebDavBackup(base, path.split('/').filter { it.isNotBlank() })
        }

        /** `rawline_backup_<versionName>_<yyyyMMdd_HHmmss>.rwlbackup`. */
        fun fileName(): String =
            "rawline_backup_${BuildConfig.VERSION_NAME}_${SimpleDateFormat(STAMP_FORMAT, Locale.US).format(Date())}$EXTENSION"
    }
}
