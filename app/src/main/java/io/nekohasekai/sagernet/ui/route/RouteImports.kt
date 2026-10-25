package app.qthrone.mobile.ui.route

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.qthrone.mobile.ui.MobileAlertDialogBuilder as MaterialAlertDialogBuilder
import app.qthrone.mobile.R
import app.qthrone.mobile.database.RouteManager
import app.qthrone.mobile.databinding.LayoutRouteRemotePromptBinding
import app.qthrone.mobile.group.RemoteRouteUpdater
import app.qthrone.mobile.route.RouteProfile
import app.qthrone.mobile.route.RouteShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The route-link imports shared by the Routes screen, deep links, the QR scanner and the main clipboard import. */
object RouteImports {

    fun isRouteLink(text: String): Boolean {
        val t = text.trim()
        return t.startsWith(RouteShare.ROUTE_LINK_PREFIX, true) || t.startsWith(RouteShare.REMOTE_ROUTE_LINK_PREFIX, true) || app.qthrone.mobile.route.ProviderRouting.isLink(t)
    }

    fun isRemoteRouteLink(text: String) = text.trim().startsWith(RouteShare.REMOTE_ROUTE_LINK_PREFIX, true)

    /** The entries of a throne://remoteroute link, or null after showing why it is invalid (FromRemoteRoutesLink). */
    fun remoteEntries(context: Context, text: String): List<RouteShare.RemoteEntry>? = try {
        RouteShare.fromRemoteRoutesLink(text)?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException(context.getString(R.string.route_remote_invalid))
    } catch (e: IllegalArgumentException) {
        showMessage(context, R.string.route_remote_add_title, e.message ?: context.getString(R.string.route_remote_invalid))
        null
    }

    /**
     * handle_add_remote_routes (mainwindow_deeplink.cpp:179-219): lists the entries with an "Auto update" checkbox,
     * on by default, then adds them (saved at once, prefilled from the repository snapshot, fetched online).
     */
    fun promptRemote(activity: AppCompatActivity, entries: List<RouteShare.RemoteEntry>, onAdded: (() -> Unit)? = null) {
        val binding = LayoutRouteRemotePromptBinding.inflate(activity.layoutInflater)
        binding.message.text = activity.getString(R.string.route_remote_add_prompt) + "\n\n" +
            entries.mapIndexed { i, e -> "${i + 1}. ${e.name}\n${e.url}" }.joinToString("\n\n")
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.route_remote_add_title)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val autoUpdate = binding.autoUpdate.isChecked
                activity.lifecycleScope.launch {
                    val ids = withContext(Dispatchers.IO) { RemoteRouteUpdater.addRemote(entries, autoUpdate) }
                    Toast.makeText(
                        activity, activity.resources.getQuantityString(R.plurals.route_remote_added, ids.size, ids.size),
                        Toast.LENGTH_SHORT
                    ).show()
                    onAdded?.invoke()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** FromShareInput off the main thread, with rule outbound names resolved against the server profiles. */
    suspend fun parse(text: String): RouteShare.Imported = withContext(Dispatchers.IO) {
        RouteShare.fromShareInput(text, RouteManager.profileIdResolver())
    }

    /** The imported profile's name, or "Imported profile" when it has none (handle_import_route). */
    fun nameOf(context: Context, p: RouteProfile) =
        p.name.trim().ifEmpty { context.getString(R.string.route_imported_default_name) }

    /**
     * A throne://route or throne://remoteroute link opened outside the Routes screen. A routing profile is added
     * after "Add this routing profile?" (handle_import_route); current_route_id does not change.
     */
    fun importLink(activity: AppCompatActivity, text: String, onAdded: () -> Unit) {
        if (isRemoteRouteLink(text)) {
            val entries = remoteEntries(activity, text) ?: return
            promptRemote(activity, entries, onAdded)
            return
        }
        activity.lifecycleScope.launch {
            val imported = parse(text)
            val profile = imported.profile
            if (profile == null) {
                showMessage(activity, R.string.route_import_title, activity.getString(R.string.route_import_parse_failed, imported.fatal))
                return@launch
            }
            profile.name = nameOf(activity, profile)
            val prompt = withDnsNote(activity, activity.getString(R.string.route_import_prompt, profile.name), profile)
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.route_import_title)
                .setMessage(withNotes(activity, prompt, imported.warnings))
                .setPositiveButton(R.string.yes) { _, _ ->
                    activity.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { RouteManager.save(profile) }
                        Toast.makeText(activity, activity.getString(R.string.route_imported, profile.name), Toast.LENGTH_SHORT).show()
                        onAdded()
                    }
                }
                .setNegativeButton(R.string.no, null)
                .show()
        }
    }

    /**
     * manageRoutesDnsNote (dialog_manage_routes.cpp): an imported profile's own DNS servers would see every query made
     * while it is in use, so the prompt names them; null when it follows the global settings.
     */
    fun dnsNote(context: Context, p: RouteProfile): String? =
        p.activeDns()?.let { context.getString(R.string.route_dns_own_servers, it.remoteDns, it.directDns) }

    fun withDnsNote(context: Context, prompt: String, p: RouteProfile): String =
        dnsNote(context, p)?.let { prompt + "\n\n" + it } ?: prompt

    /** [prompt] followed by the desktop's "Note:" block when there are warnings. */
    fun withNotes(context: Context, prompt: String, warnings: List<String>): String =
        if (warnings.isEmpty()) prompt
        else prompt + "\n\n" + context.getString(R.string.route_import_note) + "\n" + warnings.joinToString("\n")

    fun showMessage(context: Context, @StringRes title: Int, message: String) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
