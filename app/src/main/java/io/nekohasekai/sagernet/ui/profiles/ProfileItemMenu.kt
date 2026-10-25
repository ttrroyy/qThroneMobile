package app.qthrone.mobile.ui.profiles

import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import app.qthrone.mobile.ui.SaveDocument
import androidx.appcompat.widget.PopupMenu
import app.qthrone.mobile.R
import app.qthrone.mobile.SagerNet
import app.qthrone.mobile.bg.test.TestSpec
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.database.ProfileManager
import app.qthrone.mobile.database.ProxyEntity
import app.qthrone.mobile.database.ProxyGroup
import app.qthrone.mobile.ktx.Logs
import app.qthrone.mobile.ktx.confirmAction
import app.qthrone.mobile.ktx.needReload
import app.qthrone.mobile.ktx.readableMessage
import app.qthrone.mobile.ktx.showAllowingStateLoss
import app.qthrone.mobile.ktx.snackbar
import app.qthrone.mobile.ktx.isIpAddress
import app.qthrone.mobile.group.SubscriptionClient
import app.qthrone.mobile.ktx.startFilesForResult
import app.qthrone.mobile.ui.ConfigurationFragment
import app.qthrone.mobile.ui.profile.ProfileConfigExport
import app.qthrone.mobile.ui.profile.ProfileSettingsActivity
import app.qthrone.mobile.ui.profile.profileSettingsIntent
import app.qthrone.mobile.widget.QRCodeDialog

/** The actions of one profile row: edit, share, the ⋮ menu (tests, move up/down, delete). */
class ProfileItemMenu(private val host: ConfigurationFragment) {

    private val exportConfig = host.registerForActivityResult(
        SaveDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val resolver = host.requireContext().contentResolver
        host.launchIo {
            try {
                resolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(DataStore.serverConfig) }
                host.onUi { snackbar(R.string.action_export_msg).show() }
            } catch (e: Exception) {
                Logs.w(e)
                host.onUi { snackbar(e.readableMessage).show() }
            }
        }
    }

    private val editor = host.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.data?.getBooleanExtra(ProfileSettingsActivity.EXTRA_RESTART_NEEDED, false) == true) host.needReload()
    }

    fun edit(profile: ProxyEntity, group: ProxyGroup?) {
        val context = host.context ?: return
        editor.launch(profile.profileSettingsIntent(context, group?.isSubscription == true))
    }

    internal fun showMenu(anchor: View, profile: ProxyEntity, adapter: ProfileListAdapter) {
        val popup = PopupMenu(anchor.context, anchor)
        popup.menuInflater.inflate(R.menu.double_column_item_menu, popup.menu)
        val menu = popup.menu
        // the compact card has no edit / share buttons
        menu.findItem(R.id.action_edit).isVisible = adapter.isCompact
        menu.findItem(R.id.action_share).isVisible = adapter.isCompact && !profile.isChain()
        // positions of the stored order: a filtered list says nothing about it (like the desktop's drag and drop)
        val index = adapter.memberIds.indexOf(profile.id)
        val movable = !adapter.isFiltered && index >= 0
        menu.findItem(R.id.action_move_up).apply {
            isVisible = movable
            isEnabled = index > 0
        }
        menu.findItem(R.id.action_move_down).apply {
            isVisible = movable
            isEnabled = index in 0 until adapter.memberIds.size - 1
        }
        menu.findItem(R.id.action_delete).isEnabled = !host.isStartedProfile(profile.id)
        val address = profile.outbound.getAddress()
        menu.findItem(R.id.action_resolve_domain).isVisible =
            !profile.outbound.invalid && address.isNotEmpty() && !address.isIpAddress()
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_edit -> edit(profile, adapter.group)
                R.id.action_share -> showShare(anchor, profile)
                R.id.action_test_url -> test(profile, TestSpec.KIND_URL)
                R.id.action_test_ip -> test(profile, TestSpec.KIND_IP)
                R.id.action_test_speed -> test(profile, TestSpec.KIND_SPEED)
                R.id.action_resolve_domain -> resolve(profile)
                R.id.action_move_up -> move(profile, -1)
                R.id.action_move_down -> move(profile, 1)
                R.id.action_delete -> delete(profile, adapter)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun test(profile: ProxyEntity, kind: Int) {
        ProfileTests.startProfiles(host, kind, listOf(profile.id), profile.groupId)
    }

    /** Resolve Domain for one profile: the server's domain becomes the address the core's DNS returns. */
    private fun resolve(profile: ProxyEntity) {
        host.launchIo {
            val report = SubscriptionClient.resolveProfiles(profile.groupId, listOf(profile.id))
            if (report.isNotBlank()) host.onUi { snackbar(report).show() }
        }
    }

    /** One step in the stored order through Group::EmplaceProfile (the D-pad alternative to dragging). */
    private fun move(profile: ProxyEntity, step: Int) {
        val groupId = profile.groupId
        host.launchIo {
            val ids = ProfileManager.memberIds(groupId)
            val from = ids.indexOf(profile.id)
            val to = from + step
            if (from < 0 || to !in ids.indices) return@launchIo
            // EmplaceProfile lands a downward move on its target; moving up is the upper neighbour moving down
            if (step > 0) ProfileManager.emplace(groupId, from, to) else ProfileManager.emplace(groupId, to, from)
        }
    }

    private fun delete(profile: ProxyEntity, adapter: ProfileListAdapter) {
        if (DataStore.skipDeleteConfirmation) {
            adapter.removeWithUndo(profile)
            return
        }
        val context = host.requireContext()
        context.confirmAction(
            context.resources.getQuantityString(R.plurals.confirm_remove_profiles, 1, 1),
            profile.displayName(),
            R.string.delete,
        ) { adapter.removeWithUndo(profile) }
    }

    fun showShare(anchor: View, profile: ProxyEntity) {
        if (host.select || profile.isChain()) return
        val popup = PopupMenu(anchor.context, anchor)
        popup.menuInflater.inflate(R.menu.profile_share_menu, popup.menu)
        if (profile.exportLink().isEmpty()) {
            popup.menu.findItem(R.id.action_group_qr).subMenu?.removeItem(R.id.action_standard_qr)
            popup.menu.findItem(R.id.action_group_clipboard).subMenu?.removeItem(R.id.action_standard_clipboard)
        }
        popup.setOnMenuItemClickListener { item ->
            try {
                when (item.itemId) {
                    R.id.action_standard_qr -> showCode(profile.exportLink(), profile.displayName())
                    R.id.action_standard_clipboard -> copy(profile.exportLink())
                    R.id.action_universal_qr -> showCode(profile.exportJsonLink(), profile.displayName())
                    R.id.action_universal_clipboard -> copy(profile.exportJsonLink())
                    R.id.action_config_export_clipboard -> copy(ProfileConfigExport.export(profile).first)
                    R.id.action_config_export_file -> {
                        val (config, fileName) = ProfileConfigExport.export(profile)
                        DataStore.serverConfig = config
                        host.startFilesForResult(exportConfig, fileName)
                    }

                    else -> return@setOnMenuItemClickListener false
                }
            } catch (e: Exception) {
                Logs.w(e)
                host.snackbar(e.readableMessage).show()
            }
            true
        }
        popup.show()
    }

    private fun showCode(link: String, name: String) {
        QRCodeDialog(link, name).showAllowingStateLoss(host.parentFragmentManager)
    }

    private fun copy(text: String) {
        val success = SagerNet.trySetPrimaryClip(text)
        host.snackbar(if (success) R.string.action_export_msg else R.string.action_export_err).show()
    }
}
