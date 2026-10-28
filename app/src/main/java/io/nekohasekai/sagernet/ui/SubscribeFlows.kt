package app.qthrone.mobile.ui

import app.qthrone.mobile.group.ProviderLinks
import app.qthrone.mobile.group.SubscriptionCrypto
import app.qthrone.mobile.route.ProviderRouting
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentActivity
import app.qthrone.mobile.ui.MobileAlertDialogBuilder as MaterialAlertDialogBuilder
import app.qthrone.mobile.R
import app.qthrone.mobile.group.SubscriptionClient
import app.qthrone.mobile.database.GroupRepo
import app.qthrone.mobile.database.ProfileManager
import app.qthrone.mobile.outbound.Outbound
import app.qthrone.mobile.ui.profile.ProfileTextImport
import app.qthrone.mobile.ktx.Logs
import app.qthrone.mobile.ktx.app
import app.qthrone.mobile.ktx.onMainDispatcher
import app.qthrone.mobile.ktx.readableMessage
import app.qthrone.mobile.ktx.runOnDefaultDispatcher
import app.qthrone.mobile.outbound.link.Base64Strict
import app.qthrone.mobile.ui.route.RouteImports
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream
import com.king.zxing.util.CodeUtils

/**
 * The desktop's import entry points (mainwindow_deeplink.cpp): pasted, scanned or shared text
 * (import_or_handle_deeplink, import_text), the throne:// deep links and the "Add this subscription?" prompt.
 * Imports go to the explicit group [gid]; otherwise the user chooses the destination first.
 */
object SubscribeFlows {

    /** A subscription to add: the URL and the group name the link suggests (may be empty). */
    class SubscribeLink(val url: String, val name: String)

    private const val MAX_IMPORT_FILE_SIZE = 50L * 1024 * 1024

    /** Text from the clipboard, a scan or a share: http(s) URL creates a subscription; links/documents go into the server list unless an explicit target is supplied. */
    fun importText(activity: FragmentActivity, text: String, gid: Long = -1L) {
        val content = text.trim()
        if (content.isEmpty()) return
        if (ProviderRouting.isLink(content)) { importRoute(activity, content); return }
        if (RouteImports.isRouteLink(content)) { importRoute(activity, content); return }
        if (gid <= 0) { ImportTargetActivity.start(activity, "text", content); return }
        if (ProviderLinks.isProviderLink(content)) {
            // RSA key parsing and authenticated decryption must not stall the main thread.
            runOnDefaultDispatcher {
                val decoded = runCatching { ProviderLinks.decodeChecked(content) }.getOrNull()
                onMainDispatcher {
                    if (activity.isFinishing || activity.isDestroyed) return@onMainDispatcher
                    if (decoded == null) notify(activity, activity.getString(R.string.provider_crypto_import_error))
                    else if (SubscriptionCrypto.isHttpUrl(decoded.data)) {
                        addSubscription(activity, content, decoded.name.ifBlank { hostOf(decoded.data) }, gid)
                    } else importInto(activity, decoded.data, gid)
                }
            }
            return
        }
        if (content.startsWith("throne://")) {
            openDeepLink(activity, content, gid)
            return
        }
        subscribeLink(content)?.let {
            addSubscription(activity, it.url, it.name, gid)
            return
        }
        if (content.startsWith("http://") || content.startsWith("https://")) {
            val uri=Uri.parse(content)
            if(uri.userInfo!=null) importInto(activity,content,gid)
            else addSubscription(activity,uri.buildUpon().fragment(null).build().toString(),uri.fragment.orEmpty().ifBlank { hostOf(content) },gid)
            return
        }
        importInto(activity, content, gid)
    }

    /** Several payloads at once (importFromFiles): each URL or deep link on its own, the rest as one import. */
    fun importTexts(activity: FragmentActivity, payloads: List<String>, gid: Long = -1L) {
        if (payloads.size == 1) { importText(activity, payloads.single(), gid); return }
        if (gid <= 0) { ImportTargetActivity.start(activity, "batch", org.json.JSONArray(payloads).toString()); return }
        val batch = ArrayList<String>()
        for (payload in payloads.map { it.trim() }.filter { it.isNotEmpty() }) {
            val single = payload.startsWith("http://") || payload.startsWith("https://") ||
                payload.startsWith("throne://") || ProviderLinks.isProviderLink(payload) || subscribeLink(payload) != null
            if (single) importText(activity, payload, gid) else batch.add(payload)
        }
        if (batch.isNotEmpty()) importInto(activity, batch.joinToString("\n"), gid)
    }

    /** Adds a subscription link (throne://addsub, clash://install-config, a pasted URL) without asking. */
    fun addSubscription(activity: FragmentActivity, url: String, name: String, gid: Long = -1L) {
        if (gid <= 0) {
            ImportTargetActivity.start(activity, "text", url)
        } else {
            SubscriptionClient.importUrl(url, gid)
        }
    }

    /**
     * throne://addsub/<base64 of "url#name"> (handle_deeplink_impl) or clash://install-config?url=&name=;
     * null for any other text. An addsub link whose payload does not decode is null too (the desktop ignores it).
     */
    fun subscribeLink(text: String): SubscribeLink? {
        val content = text.trim()
        if (content.contains('\n')) return null
        // The foreground clipboard probe only classifies encrypted links; the confirmation flow decodes on IO.
        if (ProviderLinks.isEncrypted(content)) return SubscribeLink(content, "")
        ProviderLinks.subscription(content)?.let { return SubscribeLink(content, it.name.ifBlank { hostOf(it.data) }) }
        if (content.startsWith("clash://install-config", ignoreCase = true)) {
            val uri = Uri.parse(content)
            return SubscribeLink(uri.getQueryParameter("url").orEmpty(), uri.getQueryParameter("name").orEmpty())
        }
        if (!content.startsWith("throne://")) return null
        val uri = Uri.parse(content)
        if (!uri.host.equals("addsub", ignoreCase = true)) return null
        val path = uri.path ?: return null
        if (!path.startsWith('/')) return null
        val data = Base64Strict.decode(path.substring(1))?.takeIf { it.isNotEmpty() }?.toString(Charsets.UTF_8)
            ?: return null
        val hash = data.indexOf('#')
        if (hash < 0) return SubscribeLink(data.trim(), "")
        return SubscribeLink(data.substring(0, hash).trim(), Uri.decode(data.substring(hash + 1)))
    }

    /** A file opened or shared from outside that is not a backup: its text is imported like pasted text (importFromFiles). */
    /** importFromFiles: a text file goes through [importUri]; a zip holds one document per entry (WireGuard / OpenVPN). */
    fun importFile(activity: FragmentActivity, uri: Uri, gid: Long = -1L) {
        if (gid <= 0) { ImportTargetActivity.start(activity, "file", uri.toString()); return }
        val name = displayName(uri)
        if (isImage(uri, name)) { importQrImage(activity, uri, name, gid); return }
        if (!name.endsWith(".zip", true)) { importUri(activity, uri, gid); return }
        runOnDefaultDispatcher {
            val message = try {
                val outbounds = mutableListOf<Outbound>()
                ZipInputStream(app.contentResolver.openInputStream(uri)!!).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        outbounds.addAll(ProfileTextImport.parse(zip.bufferedReader().readText()))
                        zip.closeEntry()
                    }
                }
                if (outbounds.isEmpty()) activity.getString(R.string.no_proxies_found_in_file)
                else {
                    ProfileManager.addProfileBatch(outbounds, gid)
                    activity.resources.getQuantityString(R.plurals.added, outbounds.size, outbounds.size)
                }
            } catch (e: Exception) {
                Logs.w(e)
                e.readableMessage
            }
            onMainDispatcher { notify(activity, message) }
        }
    }

    private val imageExtensions = listOf(".png", ".jpg", ".jpeg", ".bmp", ".gif", ".webp")

    private fun isImage(uri: Uri, name: String) =
        imageExtensions.any { name.endsWith(it, true) } || app.contentResolver.getType(uri)?.startsWith("image/") == true

    /** A QR code image, like the desktop's file import: the code's text is imported as if pasted. */
    private fun importQrImage(activity: FragmentActivity, uri: Uri, name: String, gid: Long) {
        runOnDefaultDispatcher {
            val text = try {
                val bitmap = app.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
                bitmap?.let { CodeUtils.parseCodeResult(it)?.text }?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                Logs.w(e)
                null
            }
            onMainDispatcher {
                if (text == null) notify(activity, activity.getString(R.string.grp_file_not_readable, name))
                else if (!activity.isFinishing && !activity.isDestroyed) importText(activity, text, gid)
            }
        }
    }

    fun importUri(activity: FragmentActivity, uri: Uri, gid: Long = -1L) {
        runOnDefaultDispatcher {
            val name = displayName(uri)
            val text: String? = try {
                readText(uri, name)
            } catch (e: Exception) {
                Logs.w(e)
                val message = (e as? ImportFileException)?.message ?: activity.getString(R.string.grp_file_cannot_open, name)
                onMainDispatcher { notify(activity, message) }
                return@runOnDefaultDispatcher
            }
            onMainDispatcher {
                if (text.isNullOrBlank()) {
                    notify(activity, activity.getString(R.string.grp_file_not_readable, name))
                } else if (!activity.isFinishing && !activity.isDestroyed) {
                    importText(activity, text, gid)
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ flows

    /** handle_deeplink_impl: add → import, route/remoteroute → the route import, addsub → subscribe. */
    private fun openDeepLink(activity: FragmentActivity, link: String, gid: Long) {
        val command = Uri.parse(link).host.orEmpty()
        when {
            command.equals("add", ignoreCase = true) -> importInto(activity, link, gid)
            RouteImports.isRouteLink(link) -> importRoute(activity, link)
            command.equals("addsub", ignoreCase = true) -> {
                val sub = subscribeLink(link) ?: return
                addSubscription(activity, sub.url, sub.name, gid)
            }

            else -> {
                val message = activity.getString(R.string.grp_unknown_deeplink, command)
                Logs.w(message)
                notify(activity, message)
            }
        }
    }

    private fun importRoute(activity: FragmentActivity, link: String) {
        if (activity is MainActivity) {
            activity.importRouteLink(link)
        } else if (activity is AppCompatActivity) {
            RouteImports.importLink(activity, link) {}
        }
    }

    /** ImportText into the group, then "Imported N profile(s)". */
    private fun importInto(activity: FragmentActivity, content: String, gid: Long) {
        runOnDefaultDispatcher {
            val message = try {
                val target=if(gid>0) gid else GroupRepo.serverListId()
                activity.getString(R.string.grp_imported_count, SubscriptionClient.importText(content, target))
            } catch (e: Exception) {
                Logs.w(e)
                e.readableMessage
            }
            onMainDispatcher { notify(activity, message) }
        }
    }

    private fun notify(activity: FragmentActivity, text: CharSequence) {
        if (activity.isFinishing || activity.isDestroyed) {
            Toast.makeText(app, text, Toast.LENGTH_LONG).show()
        } else if (activity is MainActivity) {
            activity.snackbar(text).show()
        } else {
            Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
        }
    }

    private fun hostOf(url: String): String = Uri.parse(url).host.orEmpty()

    // ------------------------------------------------------------------------------------------------ files

    private class ImportFileException(message: String) : Exception(message)

    private fun displayName(uri: Uri): String {
        val name = runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return name ?: uri.lastPathSegment ?: uri.toString()
    }

    /** The file's text, or null when it is not text (decodeImportedText); throws when it cannot be read. */
    private fun readText(uri: Uri, name: String): String? {
        val bytes = app.contentResolver.openInputStream(uri)!!.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_IMPORT_FILE_SIZE) throw ImportFileException(app.getString(R.string.grp_file_too_large, name))
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
        return decodeImportedText(bytes)
    }

    /** A BOM names the encoding (and is dropped); otherwise text with NULs or many control bytes is not a config. */
    private fun decodeImportedText(bytes: ByteArray): String? {
        fun startsWith(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { (bytes[it].toInt() and 0xFF) == prefix[it] }
        val bom = when {
            startsWith(0xEF, 0xBB, 0xBF) -> Charsets.UTF_8 to 3
            startsWith(0xFF, 0xFE, 0x00, 0x00) -> Charset.forName("UTF-32LE") to 4
            startsWith(0x00, 0x00, 0xFE, 0xFF) -> Charset.forName("UTF-32BE") to 4
            startsWith(0xFF, 0xFE) -> Charsets.UTF_16LE to 2
            startsWith(0xFE, 0xFF) -> Charsets.UTF_16BE to 2
            else -> null
        }
        if (bom != null) return String(bytes, bom.second, bytes.size - bom.second, bom.first)
        val sample = minOf(bytes.size, 8192)
        var control = 0
        for (i in 0 until sample) {
            val c = bytes[i].toInt() and 0xFF
            if (c == 0) return null
            if (c < 0x20 && c != '\t'.code && c != '\n'.code && c != '\r'.code && c != 0x0B && c != 0x0C && c != 0x1B) control++
        }
        if (control * 20 > sample) return null
        return String(bytes, Charsets.UTF_8)
    }
}
