package app.rawline.mobile.ui.json

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.rawline.mobile.ui.MobileSheetDialog as BottomSheetDialog
import com.google.android.material.color.MaterialColors
import app.rawline.mobile.ui.MobileAlertDialogBuilder as MobileAlertDialogBuilder
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import app.rawline.mobile.Key
import app.rawline.mobile.R
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.databinding.ItemJsonProblemBinding
import app.rawline.mobile.databinding.LayoutJsonEditorBinding
import app.rawline.mobile.ktx.Logs
import app.rawline.mobile.ui.ThemedActivity
import app.rawline.mobile.ui.json.engine.JsonFormatter
import app.rawline.mobile.ui.json.engine.JsonIssue
import app.rawline.mobile.ui.json.engine.JsonSpan
import app.rawline.mobile.ui.json.engine.JsonTree
import app.rawline.mobile.ui.json.engine.JsonType
import app.rawline.mobile.ui.json.engine.SchemaCompletion
import app.rawline.mobile.ui.json.engine.SchemaStore
import app.rawline.mobile.ui.json.engine.SchemaValidator
import app.rawline.mobile.ui.json.engine.Severity
import app.rawline.mobile.utils.Theme
import app.rawline.mobile.widget.applyInsetMargin
import app.rawline.mobile.widget.applyInsetPadding
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * The JSON editor (desktop JsonEditorDialog + JsonCodeEdit + JsonIssueList) on sora-editor. It edits one string of the
 * profile cache or, with [EXTRA_USE_CONFIG_STORE], of the configuration store, and writes it back formatted on Apply.
 * Syntax errors block saving; schema problems only ask ("save anyway?").
 */
class JsonEditorActivity : ThemedActivity() {

    companion object {
        const val EXTRA_KEY = "key"

        /** Boolean (or any String, the old ConfigEditActivity contract): [EXTRA_KEY] is a configuration-store key. */
        const val EXTRA_USE_CONFIG_STORE = "useConfigStore"

        /** String[] of JSON pointers into the schema ([SchemaStore] constants); several roots form a union. */
        const val EXTRA_SCHEMA_ROOTS = "schemaRoots"
        const val EXTRA_ALLOW_EMPTY = "allowEmpty"
        const val EXTRA_REQUIRE_OBJECT = "requireObject"

        /** [JsonRelaxations]. */
        const val EXTRA_RELAXATIONS = "relaxations"
        const val EXTRA_TITLE = "title"

        private const val STATE_TEXT = "text"
        private const val STATE_BASELINE = "baseline"
        private const val MAX_SAVED_CHARS = 256 * 1024
        private const val DEBOUNCE_MS = 200L

        private val SYMBOLS = listOf("{", "}", "[", "]", ":", ",", "\"", "_", "true", "false", "null")

        /** Outbound tags every generated config has (outbound/config/BuildState.kt Tags), for settings-level JSON. */
        private val GENERATED_TAGS = mapOf("outbound" to listOf("proxy", "direct"))

        fun intent(
            context: Context,
            key: String,
            useConfigStore: Boolean = false,
            schemaRoots: List<String> = emptyList(),
            allowEmpty: Boolean = false,
            requireObject: Boolean = true,
            relaxations: JsonRelaxations? = null,
            title: CharSequence? = null,
        ): Intent = Intent(context, JsonEditorActivity::class.java).apply {
            putExtra(EXTRA_KEY, key)
            putExtra(EXTRA_USE_CONFIG_STORE, useConfigStore)
            putExtra(EXTRA_SCHEMA_ROOTS, schemaRoots.toTypedArray())
            putExtra(EXTRA_ALLOW_EMPTY, allowEmpty)
            putExtra(EXTRA_REQUIRE_OBJECT, requireObject)
            if (relaxations != null) putExtra(EXTRA_RELAXATIONS, relaxations)
            if (title != null) putExtra(EXTRA_TITLE, title)
        }
    }

    private lateinit var binding: LayoutJsonEditorBinding
    private val editor get() = binding.editor

    private var key = Key.SERVER_CONFIG
    private var useConfigStore = false
    private var schemaRoots: List<String> = emptyList()
    private var allowEmpty = false
    private var requireObject = true
    private var relaxations = JsonRelaxations()
    private var baseline = ""

    @Volatile
    private var completion: SchemaCompletion? = null
    private var validator: SchemaValidator? = null
    private var schemaUnavailable = false

    private var issues: List<JsonIssue> = emptyList()
    private var checkedText = ""

    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val handler = Handler(Looper.getMainLooper())
    private val revalidate = Runnable { validate() }
    private var validation: Job? = null

    private var undoItem: MenuItem? = null
    private var redoItem: MenuItem? = null
    private var problemsItem: MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readExtras()

        binding = LayoutJsonEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            title = intent.getCharSequenceExtra(EXTRA_TITLE) ?: getString(R.string.json_editor)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        // the keyboard or the navigation bar below the symbol bar; the app bar pads the top itself
        binding.jsonRoot.applyInsetPadding(bottom = true, ime = true)
        binding.editor.applyInsetMargin(horizontal = true)
        binding.status.applyInsetPadding(horizontal = true)
        binding.symbolScroll.applyInsetPadding(horizontal = true)

        setupEditor()
        setupSymbols()
        binding.status.setOnClickListener { showProblems() }

        val restored = savedInstanceState?.getString(STATE_TEXT)
        if (restored != null) {
            baseline = savedInstanceState.getString(STATE_BASELINE) ?: ""
            editor.setText(restored)
        } else {
            baseline = load()
            editor.setText(baseline)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = close()
        })

        validate()
        loadSchema()
    }

    @Suppress("DEPRECATION")
    private fun readExtras() {
        intent.getStringExtra(EXTRA_KEY)?.let { key = it }
        useConfigStore = intent.getBooleanExtra(EXTRA_USE_CONFIG_STORE, false) ||
            intent.getStringExtra(EXTRA_USE_CONFIG_STORE) != null
        schemaRoots = intent.getStringArrayExtra(EXTRA_SCHEMA_ROOTS)?.toList() ?: emptyList()
        allowEmpty = intent.getBooleanExtra(EXTRA_ALLOW_EMPTY, false)
        requireObject = intent.getBooleanExtra(EXTRA_REQUIRE_OBJECT, true)
        intent.getParcelableExtra<JsonRelaxations>(EXTRA_RELAXATIONS)?.let { relaxations = it }
    }

    /** The stored text, shown indented when it parses (the desktop dialog shows the indented object). */
    private fun load(): String {
        val raw = (if (useConfigStore) DataStore.configurationStore.getString(key)
        else DataStore.profileCacheStore.getString(key)) ?: ""
        if (raw.isBlank()) return ""
        val root = JsonTree.parse(raw).root ?: return raw
        return JsonFormatter.format(raw, root)
    }

    private fun setupEditor() {
        val accent = JsonColors.accent(this)
        val onAccent = if (MaterialColors.isColorLight(accent)) 0xff000000.toInt() else 0xffffffff.toInt()
        val secondary = MaterialColors.getColor(this, android.R.attr.textColorSecondary, 0xff808080.toInt())
        editor.colorScheme = JsonColors.scheme(this, Theme.usingNightMode())
        editor.typefaceText = Typeface.MONOSPACE
        editor.typefaceLineNumber = Typeface.MONOSPACE
        editor.setTextSize(14f)
        editor.tabWidth = JsonLanguage.INDENT
        editor.isLineNumberEnabled = true
        editor.isHighlightBracketPair = true
        editor.setWordwrap(false)
        editor.props.symbolPairAutoCompletion = false
        editor.props.deleteMultiSpaces = -1
        editor.setEditorLanguage(
            JsonLanguage(
                { completion },
                JsonLanguage.LetterIcon("K", accent, onAccent),
                JsonLanguage.LetterIcon("V", secondary, 0xffffffff.toInt()),
            )
        )
        editor.subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
            handler.removeCallbacks(revalidate)
            handler.postDelayed(revalidate, DEBOUNCE_MS)
            updateUndoRedo()
        }
        editor.subscribeEvent(SelectionChangeEvent::class.java) { _, _ -> updateStatus() }
    }

    private fun setupSymbols() {
        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        val color = MaterialColors.getColor(this, android.R.attr.textColorPrimary, 0xff808080.toInt())
        val density = resources.displayMetrics.density
        for (symbol in SYMBOLS) {
            val view = TextView(this).apply {
                text = symbol
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(color)
                minWidth = (40 * density).toInt()
                setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { insertSymbol(symbol) }
            }
            binding.symbols.addView(
                view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
    }

    /** Symbols go through commitText so brackets and quotes pair exactly as when typed. */
    private fun insertSymbol(symbol: String) {
        if (!editor.isEditable) return
        if (symbol.length == 1) editor.commitText(symbol) else editor.insertText(symbol, symbol.length)
        editor.notifyIMEExternalCursorChange()
        editor.ensureSelectionVisible()
    }

    private fun loadSchema() {
        if (schemaRoots.isEmpty()) return
        lifecycleScope.launch {
            val loaded = withContext(worker) {
                val schema = SchemaStore.get { assets.open(SchemaStore.ASSET) }
                if (schema == null) {
                    Logs.w("json schema unavailable: ${SchemaStore.loadError}")
                    return@withContext null
                }
                val validator = SchemaStore.validator(schema, schemaRoots)?.also { relaxations.applyTo(it) }
                val tags = if (useConfigStore) GENERATED_TAGS else emptyMap()
                validator to SchemaCompletion(schema, schemaRoots, relaxations.typeAliases, tags)
            }
            if (loaded == null) {
                schemaUnavailable = true
                updateStatus()
                return@launch
            }
            validator = loaded.first
            completion = loaded.second
            validate()
        }
    }

    // ---- validation

    private fun validate() {
        handler.removeCallbacks(revalidate)
        val text = editor.text.toString()
        val validator = validator
        validation?.cancel()
        validation = lifecycleScope.launch {
            val found = withContext(worker) { check(text, validator) }
            issues = found
            checkedText = text
            showDiagnostics(found)
            updateStatus()
            updateProblemsItem()
        }
    }

    private fun check(text: String, validator: SchemaValidator?): List<JsonIssue> {
        if (text.isBlank()) return emptyList()
        val parsed = JsonTree.parse(text)
        val root = parsed.root ?: return listOf(JsonIssue(Severity.Error, parsed.error, "", parsed.errorSpan))
        val found = ArrayList<JsonIssue>()
        if (requireObject && root.type != JsonType.Object) {
            found.add(JsonIssue(Severity.Error, getString(R.string.json_must_be_object), "", JsonSpan(root.span.offset, 1)))
        }
        if (validator != null) {
            try {
                found.addAll(validator.validate(root))
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
        return found
    }

    private fun showDiagnostics(found: List<JsonIssue>) {
        val length = editor.text.length
        val container = DiagnosticsContainer()
        for ((index, issue) in found.withIndex()) {
            var start = issue.span.offset.coerceIn(0, length)
            val end = (start + max(1, issue.span.length)).coerceAtMost(length)
            if (start >= end) {
                if (start == 0) continue
                start = end - 1
            }
            val severity = if (issue.severity == Severity.Error) DiagnosticRegion.SEVERITY_ERROR
            else DiagnosticRegion.SEVERITY_WARNING
            container.addDiagnostic(DiagnosticRegion(start, end, severity, index.toLong()))
        }
        editor.diagnostics = container
    }

    private fun updateStatus() {
        val status = binding.status
        val errors = issues.count { it.severity == Severity.Error }
        val caret = editor.cursor.left
        val atCaret = issues.firstOrNull { caret >= it.span.offset && caret <= it.span.offset + max(1, it.span.length) }
        val (text, color) = when {
            checkedText.isBlank() -> getString(R.string.json_status_empty) to 0
            atCaret != null -> {
                val position = JsonTree.lineColumn(checkedText, atCaret.span.offset)
                getString(R.string.json_caret_issue, position[0], position[1], atCaret.message) to severityColor(atCaret.severity)
            }

            errors > 0 -> resources.getQuantityString(R.plurals.json_status_problems, errors, errors) to JsonColors.ERROR
            issues.isNotEmpty() -> resources.getQuantityString(
                R.plurals.json_status_warnings, issues.size, issues.size
            ) to JsonColors.WARNING

            schemaUnavailable -> getString(R.string.json_status_syntax_only) to 0
            else -> getString(R.string.json_status_valid) to 0
        }
        status.text = text
        status.setTextColor(
            if (color != 0) color else MaterialColors.getColor(this, android.R.attr.textColorSecondary, 0xff808080.toInt())
        )
    }

    private fun severityColor(severity: Severity) = if (severity == Severity.Error) JsonColors.ERROR else JsonColors.WARNING

    // ---- actions

    private fun format() {
        val text = editor.text.toString()
        if (text.isBlank()) return
        val root = JsonTree.parse(text).root
        if (root == null) {
            alert(getString(R.string.json_fix_before_format))
            return
        }
        val formatted = JsonFormatter.format(text, root)
        if (formatted == text) return
        val content = editor.text
        content.replace(0, content.length, formatted)
        editor.setSelection(0, 0)
    }

    private fun save() {
        val text = editor.text.toString()
        if (text == baseline) {
            finish()
            return
        }
        if (text.isBlank()) {
            if (allowEmpty) store("") else alert(JsonTree.parse(text).error)
            return
        }
        val parsed = JsonTree.parse(text)
        val root = parsed.root
        if (root == null) {
            jumpTo(parsed.errorSpan.offset)
            val position = JsonTree.lineColumn(text, parsed.errorSpan.offset)
            alert(getString(R.string.json_caret_issue, position[0], position[1], parsed.error))
            return
        }
        if (requireObject && root.type != JsonType.Object) {
            alert(getString(R.string.json_must_be_object))
            return
        }
        val formatted = JsonFormatter.format(text, root)
        val validator = validator
        lifecycleScope.launch {
            val errors = if (validator == null) 0 else withContext(worker) {
                validator.validate(root).count { it.severity == Severity.Error }
            }
            if (errors == 0) {
                store(formatted)
                return@launch
            }
            MobileAlertDialogBuilder(this@JsonEditorActivity)
                .setTitle(R.string.json_save_anyway_title)
                .setMessage(resources.getQuantityString(R.plurals.json_save_anyway_message, errors, errors))
                .setPositiveButton(R.string.json_save_anyway) { _, _ -> store(formatted) }
                .setNeutralButton(R.string.json_show_problems) { _, _ -> showProblems() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun store(value: String) {
        if (useConfigStore) DataStore.configurationStore.putString(key, value)
        else DataStore.profileCacheStore.putString(key, value)
        setResult(RESULT_OK)
        finish()
    }

    private fun close() {
        if (editor.text.toString() == baseline) {
            finish()
            return
        }
        MobileAlertDialogBuilder(this)
            .setTitle(R.string.unsaved_changes_prompt)
            .setPositiveButton(R.string.save) { _, _ -> save() }
            .setNegativeButton(R.string.discard) { _, _ -> finish() }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun alert(message: String) {
        MobileAlertDialogBuilder(this)
            .setTitle(R.string.json_invalid)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun jumpTo(offset: Int) {
        val content = editor.text
        val position = content.indexer.getCharPosition(offset.coerceIn(0, content.length))
        editor.setSelection(position.line, position.column)
        editor.ensureSelectionVisible()
        editor.requestFocus()
    }

    private fun showProblems() {
        val shown = issues
        if (shown.isEmpty()) {
            Toast.makeText(this, R.string.json_problems_none, Toast.LENGTH_SHORT).show()
            return
        }
        val source = checkedText
        val dialog = BottomSheetDialog(this)
        val list = RecyclerView(this)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
                ItemJsonProblemBinding.inflate(LayoutInflater.from(parent.context), parent, false).root
            ) {}

            override fun getItemCount() = shown.size

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val issue = shown[position]
                val item = ItemJsonProblemBinding.bind(holder.itemView)
                val line = JsonTree.lineColumn(source, issue.span.offset)[0]
                item.text.text = getString(R.string.json_line_issue, line, issue.message)
                item.icon.setImageResource(
                    if (issue.severity == Severity.Error) R.drawable.ic_json_error else R.drawable.ic_baseline_warning_24
                )
                item.icon.setColorFilter(severityColor(issue.severity))
                holder.itemView.setOnClickListener {
                    dialog.dismiss()
                    jumpTo(issue.span.offset)
                }
            }
        }
        val title = TextView(this).apply {
            text = getString(R.string.json_problems, shown.size)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1)
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding / 2)
        }
        dialog.setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(list)
        })
        dialog.show()
    }

    // ---- menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.json_editor_menu, menu)
        undoItem = menu.findItem(R.id.action_json_undo)
        redoItem = menu.findItem(R.id.action_json_redo)
        problemsItem = menu.findItem(R.id.action_json_problems)
        updateUndoRedo()
        updateProblemsItem()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_json_undo -> editor.undo()
            R.id.action_json_redo -> editor.redo()
            R.id.action_json_format -> format()
            R.id.action_json_problems -> showProblems()
            R.id.action_apply -> save()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        close()
        return true
    }

    private fun updateUndoRedo() {
        setItemEnabled(undoItem, editor.canUndo())
        setItemEnabled(redoItem, editor.canRedo())
    }

    private fun updateProblemsItem() {
        problemsItem?.title = getString(R.string.json_problems, issues.size)
    }

    private fun setItemEnabled(item: MenuItem?, enabled: Boolean) {
        item ?: return
        item.isEnabled = enabled
        item.icon?.mutate()?.alpha = if (enabled) 255 else 97
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val text = editor.text.toString()
        if (text.length + baseline.length <= MAX_SAVED_CHARS) {
            outState.putString(STATE_TEXT, text)
            outState.putString(STATE_BASELINE, baseline)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(revalidate)
        editor.release()
        super.onDestroy()
        worker.close()
    }
}
