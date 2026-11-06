package app.rawline.mobile.database

import app.rawline.mobile.ktx.Logs
import app.rawline.mobile.ktx.app
import app.rawline.mobile.ktx.fetchText
import app.rawline.mobile.ktx.readableMessage
import app.rawline.mobile.outbound.json.JsonArray
import app.rawline.mobile.outbound.json.JsonInput
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.route.RouteShare
import app.rawline.mobile.route.RuleSetCatalog
import app.rawline.mobile.route.RuleSets
import java.io.File
import java.io.IOException
import java.util.TreeSet

/**
 * The snapshot of https://github.com/throneproj/routeprofiles: `srslist.h` from the rule-set branch and the files
 * of the profile branch. CI bundles one into the assets (`routeprofiles/`); [refresh] keeps a newer copy in
 * `filesDir/routeprofiles/`, which wins over the bundle file by file.
 */
object RouteRepo {
    const val SRS_LIST = "srslist.h"
    private const val DIR = "routeprofiles"
    private const val PROFILE_DIR = "profile"
    private const val REPO = "https://raw.githubusercontent.com/throneproj/routeprofiles"
    private const val PROFILE_URL_PREFIX = "$REPO/profile/"
    private const val SRS_LIST_URL = "$REPO/rule-set/$SRS_LIST"
    private const val LISTING_URL = "https://api.github.com/repos/throneproj/routeprofiles/contents/?ref=profile"
    private const val BUNDLE_PREFIX = "Profile_"

    private val PROFILE_URL = Regex("^(?i:https?://raw\\.githubusercontent\\.com/throneproj/routeprofiles)/profile/([^/?#]+)$")

    /** A `Profile_<Country>` file: the remote profiles "Download profiles" adds for that country. */
    class Bundle(val country: String, val entries: List<RouteShare.RemoteEntry>)

    private val cacheDir: File get() = File(app.filesDir, DIR)

    internal fun srsListFile(): File = File(cacheDir, SRS_LIST)

    /** A bundled snapshot file (`assets/routeprofiles/<path>`), null when the build has none. */
    internal fun readAsset(path: String): String? = try {
        app.assets.open("$DIR/$path").bufferedReader().use { it.readText() }
    } catch (e: IOException) {
        null
    }

    fun bundles(): List<Bundle> = profileFileNames().filter { it.startsWith(BUNDLE_PREFIX) }.mapNotNull { file ->
        val text = profileFile(file) ?: return@mapNotNull null
        val entries = try {
            RouteShare.fromRemoteRoutesLink(text)
        } catch (e: IllegalArgumentException) {
            Logs.w("route bundle $file: ${e.message}")
            null
        } ?: return@mapNotNull null
        Bundle(file.removePrefix(BUNDLE_PREFIX).replace('_', ' '), entries)
    }

    /** The snapshot's content for a `raw.githubusercontent.com/throneproj/routeprofiles/profile/<file>` URL. */
    fun snapshot(url: String): String? {
        val file = PROFILE_URL.matchEntire(url.trim())?.groupValues?.get(1) ?: return null
        return profileFile(file)
    }

    /**
     * Downloads srslist.h and every profile-branch file through the rule-set mirror into the runtime copy. The
     * branch is listed through the GitHub API, or taken from the snapshot's file names when the API is unreachable.
     * Returns the errors, one per line, or null.
     */
    fun refresh(): String? {
        val mirror = DataStore.rulesetMirror
        val errors = ArrayList<String>()
        try {
            val text = fetchText(RuleSets.mirrorLink(SRS_LIST_URL, mirror)).body
            if (RuleSetCatalog.parseSrsList(text).size == 0) {
                errors.add("$SRS_LIST: no rule-sets in the download")
            } else {
                writeAtomically(srsListFile(), text)
            }
        } catch (e: Exception) {
            errors.add("$SRS_LIST: ${e.readableMessage}")
        }
        val names = try {
            listProfileBranch()
        } catch (e: Exception) {
            Logs.w("routeprofiles listing: ${e.readableMessage}")
            null
        } ?: profileFileNames()
        val profileDir = File(cacheDir, PROFILE_DIR)
        for (name in names) {
            try {
                writeAtomically(File(profileDir, name), fetchText(RuleSets.mirrorLink(PROFILE_URL_PREFIX + name, mirror)).body)
            } catch (e: Exception) {
                errors.add("$name: ${e.readableMessage}")
            }
        }
        RouteManager.invalidateCatalog()
        return errors.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun listProfileBranch(): List<String>? {
        val listing = JsonInput.parseValue(fetchText(LISTING_URL).body) as? JsonArray ?: return null
        val names = listing.mapNotNull { item ->
            val entry = item as? JsonObject ?: return@mapNotNull null
            entry.string("name").takeIf { entry.string("type") == "file" && isPlainFileName(it) }
        }
        return names.takeIf { it.isNotEmpty() }
    }

    private fun profileFile(name: String): String? {
        if (!isPlainFileName(name)) return null
        val file = File(File(cacheDir, PROFILE_DIR), name)
        if (file.isFile) {
            try {
                return file.readText()
            } catch (e: IOException) {
                Logs.w(e)
            }
        }
        return readAsset("$PROFILE_DIR/$name")
    }

    private fun profileFileNames(): Set<String> {
        val names = TreeSet<String>()
        File(cacheDir, PROFILE_DIR).list()?.let { names.addAll(it) }
        try {
            app.assets.list("$DIR/$PROFILE_DIR")?.let { names.addAll(it) }
        } catch (e: IOException) {
            Logs.w(e)
        }
        return names.filterTo(TreeSet()) { isPlainFileName(it) && !it.endsWith(".tmp") }
    }

    private fun isPlainFileName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' }

    private fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("cannot write ${file.name}")
        }
    }
}
