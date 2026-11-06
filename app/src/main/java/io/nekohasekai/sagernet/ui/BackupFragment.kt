package app.qthrone.mobile.ui

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.qthrone.mobile.ui.MobileAlertDialogBuilder as MobileAlertDialogBuilder
import app.qthrone.mobile.BuildConfig
import app.qthrone.mobile.R
import app.qthrone.mobile.SagerNet
import app.qthrone.mobile.bg.BaseService
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.database.backup.BackupExport
import app.qthrone.mobile.database.backup.BackupRestore
import app.qthrone.mobile.database.backup.ThrBackup
import app.qthrone.mobile.database.backup.WebDavBackup
import app.qthrone.mobile.databinding.LayoutBackupBinding
import app.qthrone.mobile.databinding.LayoutImportBinding
import app.qthrone.mobile.databinding.LayoutProgressBinding
import app.qthrone.mobile.ktx.confirmAction
import app.qthrone.mobile.ktx.Logs
import app.qthrone.mobile.ktx.app
import app.qthrone.mobile.ktx.onMainDispatcher
import app.qthrone.mobile.ktx.readableMessage
import app.qthrone.mobile.ktx.runOnDefaultDispatcher
import app.qthrone.mobile.ktx.snackbar
import app.qthrone.mobile.ktx.startFilesForResult
import app.qthrone.mobile.ktx.triggerFullRestart
import app.qthrone.mobile.widget.applyListInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tools › Backup: the desktop's "Backup and Restore" (dialog_basic_settings.cpp:575-835) on `.thrbackup` files. A
 * backup (saved, shared or uploaded to WebDAV) holds the checked parts; a restore accepts any stream that starts
 * with "THRN", shows the desktop's part dialog, stops the service and restarts the app afterwards.
 */
class BackupFragment : Fragment(R.layout.layout_backup) {

    companion object {
        private const val ARG_RESTORE = "restore"
        private const val MIME = "application/octet-stream"
        private const val SHARE_DIR = "backup"

        /** A Backup page that opens the restore dialog for [restore] once. */
        fun newInstance(restore: Uri?) = BackupFragment().apply {
            if (restore != null) arguments = bundleOf(ARG_RESTORE to restore.toString())
        }

        fun fileName(): String = "qThrone-backup-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.thrbackup"
    }

    private var binding: LayoutBackupBinding? = null
    private var backupPage = 0
    private var busy = false
    private var progress: AlertDialog? = null
    private var restoreDialog: AlertDialog? = null

    /** The backup the restore dialog shows; discarded when the dialog goes away without a restore. */
    private var pending: BackupRestore.Loaded? = null

    private val saveBackup = registerForActivityResult(SaveDocument(MIME)) { uri ->
        if (uri != null) createTo(uri)
    }

    private val pickBackup = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) openBackup(uri)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("backupPage",backupPage)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = LayoutBackupBinding.bind(view)
        this.binding = binding
        view.applyListInsets()
        view.setBackgroundColor(MobilePalette.screen)
        (view as? android.widget.ScrollView)?.isVerticalScrollBarEnabled=false
        (view as? androidx.core.widget.NestedScrollView)?.isVerticalScrollBarEnabled=false
        fun style(node: View) {
            node.isVerticalScrollBarEnabled=false
            if(node is com.google.android.material.card.MaterialCardView) {
                // A translucent card and button would double up: the glass goes on the row inside.
                if(MobileStyle.new) { node.setCardBackgroundColor(android.graphics.Color.TRANSPARENT); node.strokeWidth=0; node.getChildAt(0)?.background=MobilePalette.rowShape(requireContext()) }
                else node.setCardBackgroundColor(MobilePalette.surface)
            }
            if(node is com.google.android.material.switchmaterial.SwitchMaterial) {
                node.setTextColor(MobilePalette.text)
            } else if(node is com.google.android.material.button.MaterialButton) {
                if(!MobileStyle.new) node.backgroundTintList=android.content.res.ColorStateList.valueOf(MobilePalette.surface)
                node.iconTint=android.content.res.ColorStateList.valueOf(MobilePalette.accent(requireContext()))
                node.setTextColor(MobilePalette.text)
            } else if(node is android.widget.Button) {
                node.setTextColor(android.content.res.ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),
                    intArrayOf(MobilePalette.muted,MobilePalette.accent(requireContext()))))
            } else if(node is android.widget.TextView) node.setTextColor(if(node.tag == "backup_section") MobilePalette.accent(requireContext()) else MobilePalette.text)
            if(node is android.view.ViewGroup) for(i in 0 until node.childCount) style(node.getChildAt(i))
        }
        style(view)
        fun showPage(page: Int) {
            backupPage = page
            binding.backupMenu.isVisible = page == 0
            binding.backupContent.isVisible = page != 0
            binding.backupLocalPage.isVisible = page == 1
            binding.backupWebdavPage.isVisible = page == 2
            (parentFragment as? ToolsFragment)?.toolbar?.apply {
                title=when(page) { 1 -> "Локально";2 -> "WebDAV";else -> "Резервное копирование" }
                setNavigationOnClickListener {
                    if(backupPage != 0) showPage(0) else requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        }
        binding.backupTabLocal.setOnClickListener { showPage(1) }
        binding.backupTabWebdav.setOnClickListener { showPage(2) }
        showPage(savedInstanceState?.getInt("backupPage") ?: 0)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if(backupPage != 0) showPage(0) else {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        binding.resetSettings.setOnClickListener {
            requireContext().confirmAction(
                getString(R.string.confirm_reset_settings),
                getString(R.string.reset_settings_message), R.string.confirm_restore,
            ) {
                DataStore.configurationStore.reset()
                triggerFullRestart(requireContext())
            }
        }
        binding.backupCreate.setOnClickListener {
            if (selection() != null) startFilesForResult(saveBackup, fileName())
        }
        binding.backupShare.setOnClickListener { selection()?.let(::share) }
        binding.backupRestore.setOnClickListener { startFilesForResult(pickBackup, "*/*") }
        binding.webdavSettings.setOnClickListener {
            startActivity(Intent(requireContext(), WebDAVSettingsActivity::class.java))
        }
        binding.backupToWebdav.setOnClickListener { selection()?.let(::uploadToWebDav) }
        binding.restoreFromWebdav.setOnClickListener { restoreFromWebDav() }

        val restore = arguments?.getString(ARG_RESTORE)
        if (restore != null) {
            arguments?.remove(ARG_RESTORE)
            if (savedInstanceState == null) openBackup(Uri.parse(restore))
        }
    }

    override fun onDestroyView() {
        restoreDialog?.dismiss()
        restoreDialog = null
        pending?.discard()
        pending = null
        progress?.dismiss()
        progress = null
        binding = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------------------------------------------ create

    /** The checked parts (the desktop requires at least one), or null with a message. */
    private fun selection(): BackupExport.Selection? {
        val b = binding ?: return null
        if (busy) {
            snackbar(R.string.backup_in_progress).show()
            return null
        }
        val selection = BackupExport.Selection(
            profiles = b.backupProfiles.isChecked,
            routes = b.backupRoutes.isChecked,
            settings = b.backupSettings.isChecked,
        )
        if (!selection.any()) {
            snackbar(R.string.backup_select_part).show()
            return null
        }
        return selection
    }

    private fun createTo(uri: Uri) {
        val selection = selection() ?: return
        runTask(R.string.backup_creating, R.string.backup_failed, work = {
            app.contentResolver.openOutputStream(uri)?.use { BackupExport.write(selection, it) }
                ?: error("cannot write $uri")
        }) {
            snackbar(getString(R.string.backup_created, included(selection))).show()
        }
    }

    private fun share(selection: BackupExport.Selection) {
        runTask(R.string.backup_creating, R.string.backup_failed, work = {
            val dir = File(app.cacheDir, SHARE_DIR).apply { mkdirs() }
            val stale = System.currentTimeMillis() - 10 * 60_000L
            dir.listFiles()?.forEach { if (it.lastModified() < stale) it.delete() }
            val file = File(dir, fileName())
            try {
                file.outputStream().use { BackupExport.write(selection, it) }
            } catch (e: Exception) {
                file.delete()
                throw e
            }
            file
        }) { file ->
            val uri = FileProvider.getUriForFile(requireContext(), BuildConfig.APPLICATION_ID + ".cache", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType(MIME)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(file.name, uri)
            try {
                startActivity(Intent.createChooser(send, getString(R.string.abc_shareactionprovider_share_with)))
            } catch (_: ActivityNotFoundException) {
                snackbar(R.string.share_target_missing).show()
            }
        }
    }

    private fun uploadToWebDav(selection: BackupExport.Selection) {
        val dav = webDav() ?: return
        runTask(R.string.webdav_uploading, R.string.webdav_backup_failed, work = {
            val file = File(app.cacheDir, "thrupload-${System.currentTimeMillis()}.thrbackup")
            try {
                file.outputStream().use { BackupExport.write(selection, it) }
                dav.upload(file, WebDavBackup.fileName())
            } finally {
                file.delete()
            }
        }) {
            snackbar(R.string.webdav_backup_success).show()
        }
    }

    private fun included(selection: BackupExport.Selection): String = listOfNotNull(
        R.string.backup_part_profiles.takeIf { selection.profiles },
        R.string.backup_part_routes.takeIf { selection.routes },
        R.string.backup_part_settings.takeIf { selection.settings },
    ).joinToString(", ") { getString(it) }

    // ------------------------------------------------------------------------------------------------ restore

    /** Parses [uri] (any name, any MIME type: only the magic counts) and shows the restore dialog. */
    private fun openBackup(uri: Uri) {
        if (busy) {
            snackbar(R.string.restore_in_progress).show()
            return
        }
        runTask(R.string.backup_reading, R.string.backup_restore_failed, work = {
            app.contentResolver.openInputStream(uri)?.use { BackupRestore.load(it) } ?: error("cannot read $uri")
        }, done = ::showRestoreDialog)
    }

    private fun restoreFromWebDav() {
        val dav = webDav() ?: return
        runTask(R.string.webdav_listing, R.string.webdav_restore_failed, work = { dav.list() }) { backups ->
            if (backups.isEmpty()) {
                alert(getString(R.string.webdav_no_backups, dav.directory.toString()))
                return@runTask
            }
            val names = Array<CharSequence>(backups.size) { backups[it].name }
            MobileAlertDialogBuilder(requireContext())
                .setTitle(R.string.webdav_choose_backup)
                .setItems(names) { _, which ->
                    runTask(R.string.webdav_downloading, R.string.webdav_restore_failed, work = {
                        dav.download(backups[which]) { BackupRestore.load(it) }
                    }, done = ::showRestoreDialog)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun webDav(): WebDavBackup? {
        if (busy) {
            snackbar(R.string.backup_in_progress).show()
            return null
        }
        val dav = try {
            WebDavBackup.configured()
        } catch (e: Exception) {
            snackbar(e.readableMessage).show()
            return null
        }
        if (dav == null) snackbar(R.string.webdav_server_empty).show()
        return dav
    }

    /** The desktop's restore dialog (dialog_basic_settings.cpp:732-788). */
    private fun showRestoreDialog(backup: BackupRestore.Loaded) {
        val available = BackupRestore.available(backup.contents)
        if (!available.any()) {
            backup.discard()
            alert(getString(R.string.backup_error_nothing))
            return
        }
        pending?.discard()
        pending = backup
        val view = LayoutImportBinding.inflate(layoutInflater)
        val created = backup.contents.createdAt.ifEmpty { getString(R.string.backup_unknown_date) }
        view.restoreHeader.text = getString(R.string.backup_restore_header, created, platformName(backup.contents.platform))
        for ((box, on) in listOf(
            view.restoreProfiles to available.profiles,
            view.restoreRoutes to available.routes,
            view.restoreSettings to available.settings,
        )) {
            box.isEnabled = on
            box.isChecked = on
        }
        view.restoreOtp.isVisible = backup.contents.parts.otp
        val dialog = MobileAlertDialogBuilder(requireContext())
            .setTitle(R.string.backup_restore_title)
            .setView(view.root)
            .setPositiveButton(R.string.backup_restore, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener {
                if (pending === backup) {
                    pending = null
                    backup.discard()
                }
                restoreDialog = null
            }
            .show()
        restoreDialog = dialog
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val choice = BackupRestore.Choice(
                profiles = available.profiles && view.restoreProfiles.isChecked,
                routes = available.routes && view.restoreRoutes.isChecked,
                settings = available.settings && view.restoreSettings.isChecked,
            )
            if (!choice.any()) {
                Toast.makeText(requireContext(), R.string.backup_restore_select_part, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pending = null
            dialog.dismiss()
            restore(backup, choice)
        }
    }

    private fun platformName(platform: String): String = when (platform) {
        ThrBackup.PLATFORM_ANDROID -> getString(R.string.backup_platform_android)
        "winnt" -> getString(R.string.backup_platform_windows)
        "linux" -> getString(R.string.backup_platform_linux)
        "darwin" -> getString(R.string.backup_platform_macos)
        "" -> getString(R.string.backup_platform_unknown)
        else -> platform
    }

    /** Runs to the end even when the screen goes away, then restarts the app as the desktop does. */
    private fun restore(backup: BackupRestore.Loaded, choice: BackupRestore.Choice) {
        val activity = requireActivity()
        busy = true
        showProgress(R.string.backup_restoring)
        runOnDefaultDispatcher {
            val result = runCatching {
                stopService()
                BackupRestore.restore(backup, choice)
            }
            onMainDispatcher {
                busy = false
                progress?.dismiss()
                progress = null
                result.onSuccess { warnings -> restored(activity, warnings) }.onFailure {
                    Logs.w(it)
                    alert(activity.getString(R.string.backup_restore_failed, describe(it)))
                }
            }
        }
    }

    private suspend fun stopService() {
        fun running() = DataStore.serviceState.canStop || DataStore.serviceState == BaseService.State.Stopping
        if (!running()) return
        SagerNet.stopService()
        withTimeoutOrNull(5000L) {
            while (running()) delay(100L)
        }
    }

    private fun restored(activity: Activity, warnings: List<String>) {
        if (activity.isFinishing || activity.isDestroyed) {
            triggerFullRestart(app)
            return
        }
        val text = if (warnings.isEmpty()) {
            activity.getString(R.string.backup_restored)
        } else {
            activity.getString(R.string.backup_restored_warnings, warnings.joinToString("\n") { "• $it" })
        }
        MobileAlertDialogBuilder(activity)
            .setTitle(R.string.backup_restore_title)
            .setMessage(text)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> triggerFullRestart(activity) }
            .show()
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** [work] off the main thread under a progress dialog, then [done] or [failure] (`%s` = the error), while the view lives. */
    private fun <T> runTask(@StringRes text: Int, @StringRes failure: Int, work: suspend () -> T, done: (T) -> Unit) {
        if (busy) {
            snackbar(R.string.backup_in_progress).show()
            return
        }
        busy = true
        showProgress(text)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { work() } }
            busy = false
            progress?.dismiss()
            progress = null
            result.onSuccess(done).onFailure {
                Logs.w(it)
                alert(app.getString(failure, describe(it)))
            }
        }
    }

    private fun showProgress(@StringRes text: Int) {
        val view = LayoutProgressBinding.inflate(layoutInflater)
        view.content.setText(text)
        progress?.dismiss()
        progress = MobileAlertDialogBuilder(requireContext()).setView(view.root).setCancelable(false).show()
    }

    private fun describe(e: Throwable): String = when (e) {
        is ThrBackup.FormatException -> when (e.kind) {
            ThrBackup.Kind.NOT_BACKUP -> app.getString(R.string.backup_error_not_backup)
            ThrBackup.Kind.UNSUPPORTED_VERSION -> app.getString(R.string.backup_error_version, e.version)
            ThrBackup.Kind.CORRUPT -> app.getString(R.string.backup_error_corrupt)
        }

        else -> e.readableMessage
    }

    private fun alert(text: String) {
        val context = context
        if (context == null || !isAdded) {
            MessageStore.showMessage(text)
            return
        }
        MobileAlertDialogBuilder(context).setMessage(text).setPositiveButton(android.R.string.ok, null).show()
    }
}
