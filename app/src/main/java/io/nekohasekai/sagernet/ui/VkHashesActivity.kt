package app.qthrone.mobile.ui

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.qthrone.mobile.R
import app.qthrone.mobile.SagerNet
import app.qthrone.mobile.bg.carrier.VkRelayAccount
import app.qthrone.mobile.database.ProfileManager
import app.qthrone.mobile.database.SagerDatabase
import app.qthrone.mobile.outbound.types.Qwdtt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Create VK call hashes, select them and apply them to profiles. */
class VkHashesActivity : ThemedActivity() {
    private val hashes = mutableListOf<String>()
    private val checked = linkedSetOf<String>()
    private var busy = false
    private lateinit var progress: ProgressBar
    private lateinit var empty: TextView
    private lateinit var create: Button
    private lateinit var selectAll: Button
    private lateinit var applySelected: Button
    private lateinit var deleteSelected: Button
    private lateinit var selection: TextView
    private val adapter = Rows()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(MobilePalette.background) }
        val toolbar = Toolbar(this).apply {
            title = "Генерация хешей"
            setTitleTextColor(MobilePalette.text)
            setNavigationIcon(R.drawable.baseline_arrow_back_24); setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; setTextColor(MobilePalette.accent(context))
            backgroundTintList = ColorStateList.valueOf(MobilePalette.surface)
            setOnClickListener { action() }
        }
        create = button("Создать хеши") { add() }
        val hint = TextView(this).apply {
            text = "Выберите количество при создании. Отметьте хеши и примените их к нужным профилям."
            textSize = 14f; setTextColor(MobilePalette.muted); setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; visibility = View.INVISIBLE
        }
        val list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@VkHashesActivity); adapter = this@VkHashesActivity.adapter }
        empty = TextView(this).apply {
            text = "Хешей пока нет. Нажмите «Создать хеши»."
            setTextColor(MobilePalette.muted); gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val content = FrameLayout(this).apply {
            addView(list, FrameLayout.LayoutParams(-1, -1)); addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }
        selection = TextView(this).apply { textSize = 14f; setTextColor(MobilePalette.muted) }
        selectAll = button("Выбрать все") {
            if (checked.size == hashes.size) checked.clear() else checked.addAll(hashes)
            render()
        }
        val selectionRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(16), 0)
            addView(selection, LinearLayout.LayoutParams(0, -2, 1f))
            addView(selectAll, LinearLayout.LayoutParams(-2, -2))
        }
        applySelected = button("Применить к профилям") { apply(hashes.filter { it in checked }) }
        deleteSelected = button("Удалить выбранные") { remove(hashes.filter { it in checked }) }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(8))
            addView(applySelected, LinearLayout.LayoutParams(-1, -2))
            addView(deleteSelected, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(toolbar)
        root.addView(create, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(16); marginEnd = dp(16) })
        root.addView(hint); root.addView(progress, LinearLayout.LayoutParams(-1, dp(4)))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(selectionRow); root.addView(actions); setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        onBackPressedDispatcher.addCallback(this) {
            if (checked.isNotEmpty()) { checked.clear(); render() } else finish()
        }
        reload()
    }

    private fun render() {
        create.isEnabled = !busy && hashes.size < VkRelayAccount.MAX_HASHES
        selection.text = "Выбрано: ${checked.size} из ${hashes.size}"
        selectAll.text = if (hashes.isNotEmpty() && checked.size == hashes.size) "Снять выбор" else "Выбрать все"
        selectAll.isEnabled = !busy && hashes.isNotEmpty()
        applySelected.isEnabled = !busy && checked.isNotEmpty()
        deleteSelected.isEnabled = !busy && checked.isNotEmpty()
        for (button in listOf(create, selectAll, applySelected, deleteSelected)) button.alpha = if (button.isEnabled) 1f else .45f
        progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        empty.visibility = if (hashes.isEmpty() && !busy) View.VISIBLE else View.GONE
        @Suppress("NotifyDataSetChanged") adapter.notifyDataSetChanged()
    }

    /** Every change ends by reading the account again, so a half-finished request still shows what VK created. */
    private fun reload(message: String? = null) {
        busy = true; render()
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { VkRelayAccount.cachedHashes() }
            hashes.clear(); hashes.addAll(saved); checked.retainAll(saved.toSet())
            busy = false; render()
            message?.let { Toast.makeText(this@VkHashesActivity, it, Toast.LENGTH_LONG).show() }
        }
    }

    private fun add() {
        if (busy) return
        val room = (VkRelayAccount.MAX_HASHES - hashes.size).coerceAtMost(6)
        if (room <= 0) return
        val counts = (1..room).map { count -> "$count ${if (count == 1) "хеш" else if (count < 5) "хеша" else "хешей"}" }.toTypedArray()
        MobileAlertDialogBuilder(this).setTitle("Сколько хешей создать?").setNegativeButton(android.R.string.cancel, null).setItems(counts) { _, index ->
            busy = true; render()
            val target = hashes.size + index + 1
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { VkRelayAccount.hashes(target) } }
                reload(result.exceptionOrNull()?.let { it.message ?: "Не удалось создать хеши" } ?: "Хеши созданы")
            }
        }.show()
    }

    /** Puts the selected hashes into chosen qWDTT, CSQTT and WDTT Plus profiles as their own (manual) hashes. */
    private fun apply(selected: List<String>) {
        if (busy || selected.isEmpty()) return
        lifecycleScope.launch {
            val profiles = withContext(Dispatchers.IO) {
                SagerDatabase.proxyDao.getAll().filter { it.outbound is Qwdtt }.sortedBy { it.displayName().lowercase() }
            }
            if (profiles.isEmpty()) {
                Toast.makeText(this@VkHashesActivity, "Нет профилей qWDTT, CSQTT или WDTT Plus", Toast.LENGTH_LONG).show(); return@launch
            }
            val picked = BooleanArray(profiles.size)
            MobileAlertDialogBuilder(this@VkHashesActivity).setTitle("Применить к профилям")
                .setMultiChoiceItems(profiles.map { "${it.displayName()} · ${it.displayType()}" }.toTypedArray(), picked) { dialog, index, on ->
                    picked[index] = on
                    (dialog as androidx.appcompat.app.AlertDialog).getButton(android.content.DialogInterface.BUTTON_POSITIVE).isEnabled = picked.any { it }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Применить") { _, _ ->
                    val targets = profiles.filterIndexed { index, _ -> picked[index] }
                    if (targets.isEmpty()) return@setPositiveButton
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            ProfileManager.updateProfile(targets.map { profile ->
                                val outbound = profile.outbound as Qwdtt
                                // The profile editors' slots: CSQTT has six, the others four.
                                outbound.hashes = selected.take(if (outbound.type == "csqtt") 6 else 4)
                                outbound.hashSource = "manual"; outbound.hashSourceChosen = true
                                // A subscription refresh keeps hashes the user set.
                                outbound.manualHashesOverride = true
                                profile.putOutbound(outbound)
                            })
                        }
                        Toast.makeText(this@VkHashesActivity, "Хеши применены к профилям: ${targets.size}", Toast.LENGTH_LONG).show()
                        checked.clear(); render()
                    }
                }.show().getButton(android.content.DialogInterface.BUTTON_POSITIVE).isEnabled = false
        }
    }

    private fun remove(drop: List<String>) {
        if (busy || drop.isEmpty()) return
        MobileAlertDialogBuilder(this).setTitle("Удаление хешей")
            .setMessage(if (drop.size == 1) "Удалить хеш и завершить его звонок в VK?" else "Удалить выбранные хеши (${drop.size}) и завершить их звонки в VK?")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Удалить") { _, _ ->
                busy = true; render()
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { VkRelayAccount.deleteHashes(drop) } }
                    checked.removeAll(drop.toSet())
                    reload(result.fold({ unfinished ->
                        if (unfinished > 0) "Хеши удалены. Звонков, которые не удалось завершить в VK: $unfinished" else "Хеши удалены"
                    }, { it.message ?: "Не удалось удалить хеши" }))
                }
            }.show()
    }

    private inner class Row(view: LinearLayout) : RecyclerView.ViewHolder(view) {
        val check = CheckBox(this@VkHashesActivity).apply { isClickable = false; isFocusable = false }
        val text = TextView(this@VkHashesActivity).apply {
            typeface = Typeface.MONOSPACE; textSize = 14f; setTextColor(MobilePalette.text)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val copy = ImageButton(this@VkHashesActivity).apply {
            setImageResource(R.drawable.ic_mobile_copy_outline); contentDescription = "Копировать"
            imageTintList = ColorStateList.valueOf(MobilePalette.accent(this@VkHashesActivity))
            background = null
        }
        val drop = ImageButton(this@VkHashesActivity).apply {
            setImageResource(R.drawable.ic_navigation_close); contentDescription = "Удалить"
            imageTintList = ColorStateList.valueOf(MobilePalette.muted)
            background = null
        }
        init {
            view.addView(check, LinearLayout.LayoutParams(-2, -2))
            view.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            view.addView(copy, LinearLayout.LayoutParams(dp(44), dp(44)))
            view.addView(drop, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
    }

    private inner class Rows : RecyclerView.Adapter<Row>() {
        override fun getItemCount() = hashes.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Row(LinearLayout(this@VkHashesActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(8), dp(4)); minimumHeight = dp(56)
            layoutParams = RecyclerView.LayoutParams(-1, -2)
            isClickable = true
            val ripple = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
        })
        override fun onBindViewHolder(holder: Row, position: Int) {
            val hash = hashes[position]
            holder.text.text = hash
            holder.check.isChecked = hash in checked
            holder.check.contentDescription = "Выбрать хеш ${position + 1}"
            holder.drop.isEnabled = !busy
            holder.copy.setOnClickListener {
                if (SagerNet.trySetPrimaryClip(hash)) Toast.makeText(this@VkHashesActivity, "Хеш скопирован", Toast.LENGTH_SHORT).show()
            }
            holder.drop.setOnClickListener { remove(listOf(hash)) }
            holder.itemView.setOnClickListener {
                if (busy) return@setOnClickListener
                if (!checked.remove(hash)) checked.add(hash)
                render()
            }
        }
    }
}
