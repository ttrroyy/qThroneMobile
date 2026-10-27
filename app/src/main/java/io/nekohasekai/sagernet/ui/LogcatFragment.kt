package app.qthrone.mobile.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.ForegroundColorSpan
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.qthrone.mobile.ui.MobileAlertDialogBuilder as MaterialAlertDialogBuilder
import app.qthrone.mobile.R
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.databinding.LayoutLogcatBinding
import app.qthrone.mobile.ktx.*
import app.qthrone.mobile.widget.applyListInsets
import moe.matsuri.nb4a.utils.CoreLog
import moe.matsuri.nb4a.utils.LogExport
import moe.matsuri.nb4a.utils.SendLog

class LogcatFragment : ToolbarFragment(R.layout.layout_logcat),
    Toolbar.OnMenuItemClickListener {

    companion object {
        // "Hide destinations" lasts for the process; "Hide sensitive data" is logExportRedact.
        private var hideDestinations = false
    }

    private var viewBinding: LayoutLogcatBinding? = null
    val binding: LayoutLogcatBinding get() = requireNotNull(viewBinding)
    private var lastLog: String? = null
    private var sharing=false

    /** What the screen shows: lines matching [query] (text, or a regex), and only warnings and errors with [problemsOnly]. */
    private var query = ""
    private var useRegex = false
    private var problemsOnly = false

    private val saveLogs = registerForActivityResult(SaveDocument("text/plain")) { uri ->
        if (uri == null) return@registerForActivityResult
        val context = requireContext().applicationContext
        val redact = DataStore.logExportRedact
        val destinations = hideDestinations
        runOnDefaultDispatcher {
            val error = try {
                val file = LogExport.build(context, redact, destinations)
                try {
                    context.contentResolver.openOutputStream(uri)!!.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                } finally {
                    file.delete()
                }
                null
            } catch (e: Exception) {
                Logs.w(e)
                e.readableMessage
            }
            onMainDispatcher {
                safeSnackbar(
                    if (error == null) context.getString(R.string.log_saved)
                    else context.getString(R.string.log_export_failed, error)
                )
            }
        }
    }

    @SuppressLint("RestrictedApi", "WrongConstant")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar?.setTitle(R.string.qmobile_logs)
        view.setBackgroundColor(MobilePalette.background)
        view.findViewById<View>(R.id.log_panel).background = android.graphics.drawable.GradientDrawable().apply {
            setColor(MobilePalette.surface)
            cornerRadius = 20 * resources.displayMetrics.density
        }

        toolbar?.inflateMenu(R.menu.logcat_menu)
        toolbar?.setOnMenuItemClickListener(this)
        toolbar?.overflowIcon?.setTint(MobilePalette.text)
        toolbar?.menu?.let { menu ->
            for (i in 0 until menu.size()) menu.getItem(i).icon?.mutate()?.setTint(MobilePalette.accent(requireContext()))
        }
        updateExportOptions()

        viewBinding = LayoutLogcatBinding.bind(view)

        if (Build.VERSION.SDK_INT >= 23) {
            binding.textview.breakStrategy = 0 // simple
        }

        binding.scroolview.applyListInsets()

        lastLog = null
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    reloadSession()
                    delay(1000)
                }
            }
        }
    }

    override fun onDestroyView() {
        toolbar?.setOnMenuItemClickListener(null)
        viewBinding = null
        lastLog = null
        super.onDestroyView()
    }

    private fun updateExportOptions() {
        val menu = toolbar?.menu ?: return
        val redact = DataStore.logExportRedact
        menu.findItem(R.id.action_hide_sensitive)?.isChecked = redact
        menu.findItem(R.id.action_hide_destinations)?.apply {
            isEnabled = redact
            isChecked = redact && hideDestinations
        }
    }

    private fun getColorForLine(line: String): ForegroundColorSpan {
        var color = ForegroundColorSpan(MobilePalette.text)
        when {
            line.contains("INFO[") || line.contains(" [Info]") -> {
                color = ForegroundColorSpan((0xFF86C166).toInt())
                if (MobilePalette.light) color = ForegroundColorSpan((0xFF286A35).toInt())
            }

            line.contains("ERROR[") || line.contains(" [Error]") -> {
                color = ForegroundColorSpan(Color.RED)
            }

            line.contains("WARN[") || line.contains(" [Warning]") -> {
                color = ForegroundColorSpan((0xFFFFA000).toInt())
                if (MobilePalette.light) color = ForegroundColorSpan((0xFF855400).toInt())
            }
        }
        return color
    }

    private suspend fun reloadSession() {
        val text = withContext(Dispatchers.IO) { String(CoreLog.read(50 * 1024)) }
        if (lastLog == text || binding.textview.hasSelection()) return
        val follow = lastLog == null || !binding.scroolview.canScrollVertically(1)
        lastLog = text
        val emptyText = getString(if (filtering()) R.string.log_search_nothing else R.string.log_empty)
        val span = withContext(Dispatchers.Default) {
            SpannableString(filter(text).ifEmpty { emptyText }).also { result ->
                var offset = 0
                for (line in result.lines()) {
                    result.setSpan(getColorForLine(line), offset, offset + line.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                    offset += line.length + 1
                }
            }
        }
        binding.textview.text = span
        if (follow) binding.textview.doOnLayout {
            binding.scroolview.scrollTo(0, binding.textview.height)
        }
    }

    private fun filtering() = query.isNotEmpty() || problemsOnly

    private fun isProblem(line: String) =
        line.contains("ERROR[") || line.contains(" [Error]") || line.contains("WARN[") || line.contains(" [Warning]")

    /** The lines of [text] the search keeps; an invalid regex falls back to plain text. */
    private fun filter(text: String): String {
        if (!filtering()) return text
        val regex = if (useRegex && query.isNotEmpty()) runCatching { Regex(query, RegexOption.IGNORE_CASE) }.getOrNull() else null
        return text.lineSequence().filter { line ->
            (!problemsOnly || isProblem(line)) && when {
                query.isEmpty() -> true
                regex != null -> regex.containsMatchIn(line)
                else -> line.contains(query, ignoreCase = true)
            }
        }.joinToString("\n")
    }

    private fun showSearch() {
        val context = requireContext()
        val pad = (20 * resources.displayMetrics.density).toInt()
        val field = android.widget.EditText(context).apply {
            setText(query)
            setSelection(query.length)
            hint = getString(R.string.log_search_hint)
            isSingleLine = true
        }
        val regex = android.widget.CheckBox(context).apply {
            setText(R.string.log_search_regex)
            isChecked = useRegex
        }
        val problems = android.widget.CheckBox(context).apply {
            setText(R.string.log_search_problems)
            isChecked = problemsOnly
        }
        val column = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(field)
            addView(regex)
            addView(problems)
        }
        MaterialAlertDialogBuilder(context).setTitle(R.string.log_search)
            .setView(column)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                applySearch(field.text.toString().trim(), regex.isChecked, problems.isChecked)
            }
            .setNeutralButton(R.string.log_search_reset) { _, _ -> applySearch("", false, false) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applySearch(text: String, regex: Boolean, problems: Boolean) {
        query = text
        useRegex = regex
        problemsOnly = problems
        toolbar?.subtitle = if (filtering()) query.ifEmpty { getString(R.string.log_search_problems) } else null
        lastLog = null
        viewLifecycleOwner.lifecycleScope.launch { reloadSession() }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_search_logs -> showSearch()
            R.id.action_log_detail -> {
                val levels = listOf("debug", "info", "error")
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.log_detail)
                    .setSingleChoiceItems(R.array.log_detail_choices, levels.indexOf(DataStore.logLevel)) { dialog, index ->
                        DataStore.configurationStore.putBoolean("diagnosticLogsConfigured", true)
                        DataStore.logLevel = levels[index]
                        DataStore.xrayLogLevel = levels[index]
                        dialog.dismiss()
                        needReload()
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }
            R.id.action_clear_logcat -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.clear_logcat)
                    .setMessage(R.string.log_clear_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ -> viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            CoreLog.clear()
                            ProcessBuilder("logcat", "-c").start().waitFor()
                        }
                    } catch (e: Exception) {
                        safeSnackbar(e.readableMessage)
                        return@launch
                    }
                    lastLog = null
                    reloadSession()
                } }.show()

            }

            R.id.action_share_logs -> {
                if(sharing) return true
                sharing=true;item.isEnabled=false
                val context = requireContext()
                safeSnackbar(context.getString(R.string.log_export_preparing))
                val redact = DataStore.logExportRedact
                val destinations = hideDestinations
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val file = withContext(Dispatchers.IO) { LogExport.build(context.applicationContext, redact, destinations) }
                        if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) SendLog.share(context, file, file.nameWithoutExtension)
                    } catch(e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            safeSnackbar(context.getString(R.string.log_export_failed, e.readableMessage))
                        }
                    } finally { sharing=false;item.isEnabled=true }
                }
            }

            R.id.action_save_logs -> startFilesForResult(saveLogs, LogExport.fileName())

            R.id.action_hide_sensitive -> {
                DataStore.logExportRedact = !DataStore.logExportRedact
                updateExportOptions()
            }

            R.id.action_hide_destinations -> {
                hideDestinations = !hideDestinations
                updateExportOptions()
            }

            R.id.action_refresh -> {
                viewLifecycleOwner.lifecycleScope.launch { lastLog = null; reloadSession() }
            }
        }
        return true
    }

}
