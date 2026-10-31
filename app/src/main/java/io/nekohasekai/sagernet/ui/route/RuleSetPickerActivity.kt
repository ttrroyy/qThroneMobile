package app.qthrone.mobile.ui.route

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.qthrone.mobile.ui.MobileAlertDialogBuilder as MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView
import app.qthrone.mobile.R
import app.qthrone.mobile.database.RouteManager
import app.qthrone.mobile.databinding.LayoutRuleSetItemBinding
import app.qthrone.mobile.databinding.LayoutRuleSetPickerBinding
import app.qthrone.mobile.ktx.crossFadeFrom
import app.qthrone.mobile.ktx.dp2px
import app.qthrone.mobile.route.RuleSetCatalog
import app.qthrone.mobile.route.RuleSets
import app.qthrone.mobile.ui.ThemedActivity
import app.qthrone.mobile.widget.applyListInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Multi-select over the rule-set list (RouteManager.catalog()) plus .srs URLs. [EXTRA_RULE_SETS] carries the rule's
 * entries in and the selection back out (RESULT_OK on back or up); entries the list does not know stay selected and
 * are flagged at the top.
 */
class RuleSetPickerActivity : ThemedActivity() {

    companion object {
        const val EXTRA_RULE_SETS = "ruleSets"
        /** The profile is a Happ or Incy one, whose geosite:/geoip: categories come from its own geo files. */
        const val EXTRA_GEO_FILES = "geoFiles"
        private const val STATE_SELECTED = "selected"
    }

    class Contract(private val geoFiles: () -> Boolean = { false }) : ActivityResultContract<List<String>, List<String>?>() {
        override fun createIntent(context: Context, input: List<String>) =
            Intent(context, RuleSetPickerActivity::class.java).putStringArrayListExtra(EXTRA_RULE_SETS, ArrayList(input))
                .putExtra(EXTRA_GEO_FILES, geoFiles())

        override fun parseResult(resultCode: Int, intent: Intent?): List<String>? =
            if (resultCode == RESULT_OK) intent?.getStringArrayListExtra(EXTRA_RULE_SETS) else null
    }

    private enum class Kind { KNOWN, URL, GEO, UNKNOWN }

    private class Row(val entry: String, val source: String, val kind: Kind)

    private lateinit var binding: LayoutRuleSetPickerBinding
    private val selected = LinkedHashSet<String>()
    private var catalog = RuleSetCatalog(emptyList())
    private var rows = emptyList<Row>()
    private val adapter = RowAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutRuleSetPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setTitle(R.string.rule_set_picker_title)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.baseline_arrow_back_24)
        }

        val initial = savedInstanceState?.getStringArrayList(STATE_SELECTED)
            ?: intent.getStringArrayListExtra(EXTRA_RULE_SETS).orEmpty()
        initial.map { it.trim() }.filterTo(selected) { it.isNotEmpty() }
        updateSubtitle()

        binding.list.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        binding.list.adapter = adapter
        // the app bar pads the status bar and the sides itself (applyTopInset); the list pads the navigation bar and the sides
        binding.list.applyListInsets(ime = true, horizontal = true)
        binding.search.addTextChangedListener { rebuild() }
        onBackPressedDispatcher.addCallback(this) { finishWithResult() }

        lifecycleScope.launch {
            catalog = withContext(Dispatchers.IO) { RouteManager.catalog() }
            binding.catalogEmpty.isVisible = catalog.size == 0
            rebuild()
            binding.list.crossFadeFrom(binding.loading)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_SELECTED, ArrayList(selected))
    }

    private fun updateSubtitle() {
        supportActionBar?.subtitle = getString(R.string.picker_selected, selected.size)
    }

    private fun rowOf(entry: String): Row {
        catalog.urlOf(entry)?.let { return Row(entry, RuleSetLabels.source(it), Kind.KNOWN) }
        if (RuleSets.isUrl(entry)) return Row(entry, RuleSetLabels.source(entry), Kind.URL)
        val geo = entry.substringBefore(':', "")
        if (intent.getBooleanExtra(EXTRA_GEO_FILES, false) && (geo == "geosite" || geo == "geoip")) return Row(entry, geo, Kind.GEO)
        return Row(entry, "", Kind.UNKNOWN)
    }

    /** Selected entries outside the list first (unknown ones flagged), then selected list entries, then the rest. */
    @Suppress("NotifyDataSetChanged")
    private fun rebuild() {
        val query = binding.search.text?.toString()?.trim().orEmpty()
        val extra = selected.map { rowOf(it) }.filter { it.kind != Kind.KNOWN }
            .sortedBy { if (it.kind == Kind.UNKNOWN) 0 else 1 }
            .filter { query.isEmpty() || it.entry.contains(query, true) }
        val matches = catalog.search(query, Int.MAX_VALUE).map { (name, url) -> Row(name, RuleSetLabels.source(url), Kind.KNOWN) }
        val (picked, rest) = matches.partition { it.entry in selected }
        rows = extra + picked + rest
        adapter.notifyDataSetChanged()
    }

    private fun finishWithResult() {
        setResult(RESULT_OK, Intent().putStringArrayListExtra(EXTRA_RULE_SETS, ArrayList(selected)))
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finishWithResult()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.rule_set_picker_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_add_url -> {
                addUrl()
                return true
            }

            R.id.action_clear_selections -> {
                selected.clear()
                updateSubtitle()
                rebuild()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun addUrl() {
        val layout = TextInputLayout(this).apply {
            hint = getString(R.string.rule_set_add_url_hint)
        }
        val input = TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        layout.addView(input)
        val container = FrameLayout(this).apply {
            setPadding(dp2px(20), dp2px(12), dp2px(20), 0)
            addView(layout)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rule_set_add_url)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val url = input.text.toString().trim()
            if (!RuleSets.isUrl(url)) {
                layout.error = getString(R.string.rule_set_add_url_invalid)
                return@setOnClickListener
            }
            selected.add(url)
            updateSubtitle()
            rebuild()
            binding.list.scrollToPosition(0)
            dialog.dismiss()
        }
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowHolder>(), FastScrollRecyclerView.SectionedAdapter {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            RowHolder(LayoutRuleSetItemBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: RowHolder, position: Int) = holder.bind(rows[position])

        override fun getItemCount() = rows.size

        override fun getSectionName(position: Int): String {
            val row = rows[position]
            return if (row.kind == Kind.KNOWN) row.entry.removePrefix("geoip-").removePrefix("geosite-").take(1) else "*"
        }
    }

    private inner class RowHolder(val binding: LayoutRuleSetItemBinding) : RecyclerView.ViewHolder(binding.root) {
        private lateinit var row: Row
        private val sourceColors = binding.source.textColors

        init {
            binding.root.setOnClickListener {
                if (!selected.remove(row.entry)) selected.add(row.entry)
                binding.check.isChecked = row.entry in selected
                updateSubtitle()
            }
        }

        fun bind(row: Row) {
            this.row = row
            binding.name.text = when (row.kind) {
                Kind.URL -> RuleSetLabels.shortName(row.entry)
                else -> row.entry
            }
            binding.source.text = when (row.kind) {
                Kind.KNOWN -> row.source
                Kind.URL -> getString(R.string.rule_set_url_source, row.source)
                Kind.GEO -> getString(R.string.rule_set_geo_file, row.source)
                Kind.UNKNOWN -> getString(R.string.rule_set_unknown)
            }
            if (row.kind == Kind.UNKNOWN) {
                binding.source.setTextColor(ContextCompat.getColor(this@RuleSetPickerActivity, R.color.color_route_block))
            } else {
                binding.source.setTextColor(sourceColors)
            }
            binding.check.isChecked = row.entry in selected
        }
    }
}
