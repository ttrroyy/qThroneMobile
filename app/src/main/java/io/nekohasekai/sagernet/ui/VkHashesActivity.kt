package app.rawline.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
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
import app.rawline.mobile.R
import app.rawline.mobile.SagerNet
import app.rawline.mobile.bg.carrier.CarrierUiRequests
import app.rawline.mobile.bg.carrier.VkRelayAccount
import app.rawline.mobile.database.ProfileManager
import app.rawline.mobile.database.SagerDatabase
import app.rawline.mobile.outbound.types.Qwdtt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Create VK call hashes, select them and apply them to profiles. */
class VkHashesActivity : ServerScreenActivity() {
    private val ui by lazy { ServerUi(this) }
    private val hashes = mutableListOf<String>()
    private val checked = linkedSetOf<String>()
    private var busy = false
    private var captcha: MobileCaptchaController? = null
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var empty: TextView
    private lateinit var create: TextView
    private lateinit var selectAll: TextView
    private lateinit var applySelected: TextView
    private lateinit var deleteSelected: TextView
    private lateinit var selection: TextView
    private val adapter = Rows()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // Creating hashes may need a fresh VK token: its sign-in opens here, not after leaving the screen.
        captcha = MobileCaptchaController(this)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(MobilePalette.background))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(MobilePalette.screen) }
        val toolbar = Toolbar(this).apply {
            title = "Генерация хешей"
            setTitleTextColor(MobilePalette.text)
            setNavigationIcon(R.drawable.baseline_arrow_back_24); setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        create = ui.button("Создать хеши", primary = true) { add() }
        val hint = ui.text("Отметьте хеши, чтобы применить их к профилям. В профиле с источником «Созданные в клиенте» их можно выбрать прямо в настройках.", 14f, MobilePalette.muted)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(ui.accent)
            indeterminateTintList = android.content.res.ColorStateList.valueOf(ui.accent)
        }
        status = ui.text("", 14f, MobilePalette.muted).apply { visibility = View.GONE }
        val top = ui.column().apply {
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
            addView(create, LinearLayout.LayoutParams(-1, -2))
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
            addView(progress, LinearLayout.LayoutParams(-1, -2))
        }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@VkHashesActivity); adapter = this@VkHashesActivity.adapter
            setPadding(ui.dp(16), 0, ui.dp(16), 0); clipToPadding = false
        }
        empty = ui.text("Хешей пока нет. Нажмите «Создать хеши».", 14f, MobilePalette.muted).apply {
            gravity = Gravity.CENTER; setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(24))
        }
        val content = FrameLayout(this).apply {
            addView(list, FrameLayout.LayoutParams(-1, -1)); addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }
        selection = ui.text("", 14f, MobilePalette.muted)
        selectAll = ui.text("Выбрать все", 15f, ui.accent, bold = true).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = ui.dp(40); setPadding(ui.dp(12), 0, 0, 0); isFocusable = true
            setOnClickListener {
                if (checked.size == hashes.size) checked.clear() else checked.addAll(hashes)
                render()
            }
        }
        applySelected = ui.button("Применить") { apply(hashes.filter { it in checked }) }
        deleteSelected = ui.button("Удалить", danger = true) { remove(hashes.filter { it in checked }) }
        val actions = ui.column().apply {
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(12))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(selection, LinearLayout.LayoutParams(0, -2, 1f))
                addView(selectAll, LinearLayout.LayoutParams(-2, -2))
            }, LinearLayout.LayoutParams(-1, -2))
            addView(LinearLayout(context).apply {
                addView(applySelected, LinearLayout.LayoutParams(0, -2, 1f))
                addView(deleteSelected, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(8) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        }
        root.addView(toolbar)
        root.addView(top)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(actions); setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        onBackPressedDispatcher.addCallback(this) {
            if (checked.isNotEmpty()) { checked.clear(); render() } else finish()
        }
        reload()
    }

    override fun onDestroy() {
        // Leaving while hashes are created drops their VK sign-in, so no other screen asks for it afterwards.
        if (isFinishing && busy) runCatching { CarrierUiRequests.pending() }.getOrNull()?.takeIf { it.hasExtra("tokenState") }?.getStringExtra("id")?.let { id ->
            sendBroadcast(Intent("$packageName.CAPTCHA_RESULT").setPackage(packageName).putExtra("id", id).putExtra("result", "error:cancelled"), "$packageName.SERVICE")
        }
        captcha?.close(); captcha = null
        super.onDestroy()
    }

    private fun render() {
        create.isEnabled = !busy && hashes.size < VkRelayAccount.MAX_HASHES
        selection.text = "Выбрано: ${checked.size} из ${hashes.size}"
        selectAll.text = if (hashes.isNotEmpty() && checked.size == hashes.size) "Снять выбор" else "Выбрать все"
        selectAll.isEnabled = !busy && hashes.isNotEmpty()
        applySelected.isEnabled = !busy && checked.isNotEmpty()
        deleteSelected.isEnabled = !busy && checked.isNotEmpty()
        for (button in listOf(create, selectAll, applySelected, deleteSelected)) button.alpha = if (button.isEnabled) 1f else .45f
        empty.visibility = if (hashes.isEmpty() && !busy) View.VISIBLE else View.GONE
        @Suppress("NotifyDataSetChanged") adapter.notifyDataSetChanged()
    }

    private fun showProgress(text: String?, done: Int = 0, total: Int = 0) {
        status.text = text.orEmpty()
        status.visibility = if (text == null) View.GONE else View.VISIBLE
        progress.visibility = if (text == null) View.GONE else View.VISIBLE
        progress.isIndeterminate = total == 0
        if (total > 0) { progress.max = total; progress.progress = done }
    }

    /** Every change ends by reading the account again, so a half-finished request still shows what VK created. */
    private fun reload(message: String? = null) {
        busy = true; render()
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { VkRelayAccount.cachedHashes() }
            hashes.clear(); hashes.addAll(saved); checked.retainAll(saved.toSet())
            busy = false; showProgress(null); render()
            message?.let { Toast.makeText(this@VkHashesActivity, it, Toast.LENGTH_LONG).show() }
        }
    }

    private fun add() {
        if (busy) return
        val room = (VkRelayAccount.MAX_HASHES - hashes.size).coerceAtMost(6)
        if (room <= 0) return
        val count = ui.input("От 1 до $room", "1", number = true).apply {
            background = ui.well(); setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); setSelectAllOnFocus(true)
            MobilePalette.tintCursor(this)
        }
        val body = ui.column().apply { setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0); addView(count, LinearLayout.LayoutParams(-1, -2)) }
        val dialog = MobileAlertDialogBuilder(this).setTitle("Сколько хешей создать?").setView(body)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton("Создать", null).show()
        val confirm = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        count.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { confirm.isEnabled = s.toString().toIntOrNull() in 1..room }
        })
        confirm.setOnClickListener {
            val total = count.text.toString().toIntOrNull()?.takeIf { it in 1..room } ?: return@setOnClickListener
            dialog.dismiss()
            busy = true; render()
            val before = hashes.size
            showProgress("Подготовка создания: $total", 0, 0)
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        VkRelayAccount.hashes(before + total) { created ->
                            runOnUiThread {
                                hashes.clear(); hashes.addAll(created); render()
                                showProgress("Создано ${created.size - before} из $total", created.size - before, total)
                            }
                        }
                    }
                }
                reload(result.exceptionOrNull()?.let { it.message ?: "Не удалось создать хеши" } ?: "Хеши созданы")
            }
        }
    }

    /** Puts the selected hashes into chosen qWDTT, CSQTT and WDTT Plus profiles as their own (manual) hashes. */
    private fun apply(selected: List<String>) {
        if (busy || selected.isEmpty()) return
        lifecycleScope.launch {
            val profiles = withContext(Dispatchers.IO) {
                SagerDatabase.proxyDao.getAll().filter { it.outbound is Qwdtt || it.outbound is app.rawline.mobile.outbound.types.Freeturn }.sortedBy { it.displayName().lowercase() }
            }
            if (profiles.isEmpty()) {
                Toast.makeText(this@VkHashesActivity, "Нет профилей qWDTT, CSQTT, WDTT Plus или FreeTurn", Toast.LENGTH_LONG).show(); return@launch
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
                                (profile.outbound as? app.rawline.mobile.outbound.types.Freeturn)?.let { freeturn ->
                                    freeturn.links = selected.joinToString("\n") { app.rawline.mobile.outbound.types.Freeturn.CALL_LINK + it }
                                    freeturn.hashSource = "manual"
                                    return@map profile.putOutbound(freeturn)
                                }
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
                busy = true; render(); showProgress("Удаление хешей…")
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
        val text = ui.text("", 14f, mono = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
        val copy = ui.iconButton(R.drawable.ic_mobile_copy_outline, "Копировать") {}
        val drop = ui.iconButton(R.drawable.ic_navigation_close, "Удалить") {}
        init {
            view.addView(check, LinearLayout.LayoutParams(-2, -2))
            view.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            view.addView(copy, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
            view.addView(drop, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
        }
    }

    private inner class Rows : RecyclerView.Adapter<Row>() {
        override fun getItemCount() = hashes.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Row(LinearLayout(this@VkHashesActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(8), ui.dp(4), ui.dp(4), ui.dp(4)); minimumHeight = ui.dp(52)
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) }
            background = ui.card().background
            isClickable = true; MobileFeedback.apply(this)
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
