package app.qthrone.mobile.ui

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
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

/** The account's VK call hashes: + creates, a cross ends one call, a long press selects several, as on the desktop. */
class VkHashesActivity : ThemedActivity() {
    private val hashes = mutableListOf<String>()
    private val checked = linkedSetOf<String>()
    private var selecting = false
    private var busy = false
    private lateinit var toolbar: Toolbar
    private lateinit var progress: ProgressBar
    private lateinit var empty: TextView
    private val adapter = Rows()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(MobilePalette.background) }
        toolbar = Toolbar(this).apply {
            setTitleTextColor(MobilePalette.text)
            setNavigationIcon(R.drawable.baseline_arrow_back_24); setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_ADD -> add()
                    MENU_SELECT_ALL -> { checked.addAll(hashes); render() }
                    MENU_DELETE -> remove(hashes.filter { it in checked })
                    MENU_APPLY -> apply(hashes.filter { it in checked })
                }
                true
            }
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; visibility = View.INVISIBLE
        }
        val list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@VkHashesActivity); adapter = this@VkHashesActivity.adapter }
        empty = TextView(this).apply {
            text = "Хешей пока нет. Нажмите +, чтобы создать."
            setTextColor(MobilePalette.muted); gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val content = FrameLayout(this).apply {
            addView(list, FrameLayout.LayoutParams(-1, -1)); addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }
        root.addView(toolbar); root.addView(progress, LinearLayout.LayoutParams(-1, dp(4)))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        onBackPressedDispatcher.addCallback(this) {
            if (selecting) { checked.clear(); selecting = false; render() } else finish()
        }
        reload()
    }

    private fun render() {
        if (checked.isEmpty()) selecting = false
        toolbar.title = if (selecting) "Выбрано: ${checked.size}" else "Генерация хешей"
        toolbar.menu.clear()
        fun item(id: Int, title: String, res: Int) = toolbar.menu.add(Menu.NONE, id, Menu.NONE, title).apply {
            setIcon(res); icon?.mutate()?.setTint(MobilePalette.text); isEnabled = !busy
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        if (selecting) {
            item(MENU_APPLY, "Применить к профилям", R.drawable.ic_mobile_apply)
            item(MENU_SELECT_ALL, "Выбрать все", R.drawable.ic_baseline_check_circle_24)
            item(MENU_DELETE, "Удалить", R.drawable.ic_mobile_delete)
        } else if (hashes.size < VkRelayAccount.MAX_HASHES) item(MENU_ADD, "Создать хеши", R.drawable.ic_mobile_add)
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
        val counts = (1..room).map(Int::toString).toTypedArray()
        MobileAlertDialogBuilder(this).setTitle("Сколько хешей создать").setItems(counts) { _, index ->
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
                .setMultiChoiceItems(profiles.map { "${it.displayName()} · ${it.displayType()}" }.toTypedArray(), picked) { _, index, on -> picked[index] = on }
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
                }.show()
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
            isClickable = true; isLongClickable = true
            val ripple = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
        })
        override fun onBindViewHolder(holder: Row, position: Int) {
            val hash = hashes[position]
            holder.text.text = hash
            holder.check.visibility = if (selecting) View.VISIBLE else View.GONE
            holder.check.isChecked = hash in checked
            holder.drop.visibility = if (selecting) View.GONE else View.VISIBLE
            holder.drop.isEnabled = !busy
            holder.copy.setOnClickListener {
                if (SagerNet.trySetPrimaryClip(hash)) Toast.makeText(this@VkHashesActivity, "Хеш скопирован", Toast.LENGTH_SHORT).show()
            }
            holder.drop.setOnClickListener { remove(listOf(hash)) }
            holder.itemView.setOnLongClickListener {
                if (busy) return@setOnLongClickListener false
                if (!selecting) { selecting = true; checked.clear() }
                checked.add(hash); render(); true
            }
            holder.itemView.setOnClickListener {
                if (!selecting) return@setOnClickListener
                if (!checked.remove(hash)) checked.add(hash)
                render()
            }
        }
    }

    private companion object {
        const val MENU_ADD = 1
        const val MENU_SELECT_ALL = 2
        const val MENU_DELETE = 3
        const val MENU_APPLY = 4
    }
}
