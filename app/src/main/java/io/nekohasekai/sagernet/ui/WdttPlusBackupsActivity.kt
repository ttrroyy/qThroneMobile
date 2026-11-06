package app.qthrone.mobile.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import app.qthrone.mobile.R
import app.qthrone.mobile.deploy.DeployServer
import app.qthrone.mobile.deploy.DeployStore
import app.qthrone.mobile.deploy.WdttPlusAdmin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** «Резервные копии и перенос» of WDTT Plus: the server's own scheduled snapshots, managed from the phone. */
class WdttPlusBackupsActivity : ServerScreenActivity() {
    private val ui by lazy { ServerUi(this) }
    private lateinit var body: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var server: DeployServer
    private lateinit var admin: WdttPlusAdmin
    private var info: JSONObject? = null
    private var pendingDocument: String? = null
    private val dates = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    private val save = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val document = pendingDocument; pendingDocument = null
        if (uri == null || document == null) return@registerForActivityResult
        lifecycleScope.launch {
            val written = withContext(Dispatchers.IO) { runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(document.toByteArray(Charsets.UTF_8)) } } }
            Toast.makeText(this@WdttPlusBackupsActivity, if (written.isSuccess) "Копия сохранена в зашифрованном виде" else "Не удалось сохранить файл", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(MobilePalette.background))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(MobilePalette.screen) }
        val toolbar = Toolbar(this).apply {
            setTitleTextColor(MobilePalette.text); setNavigationIcon(R.drawable.baseline_arrow_back_24); setNavigationOnClickListener { finish() }
            title = "Резервные копии и перенос"
        }
        toolbar.menu.add("Обновить").setIcon(R.drawable.ic_mobile_refresh).setShowAsActionFlags(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            .setOnMenuItemClickListener { refresh(); true }
        body = ui.column().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(24)) }
        progress = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        status = ui.text("", 14f, MobilePalette.muted).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(12), 0, 0) }
        root.addView(toolbar)
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        val id = intent.getStringExtra("server") ?: return finish()
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { DeployStore.all().firstOrNull { it.id == id } }.getOrNull() }
            if (loaded == null) { finish(); return@launch }
            server = loaded; admin = WdttPlusAdmin(loaded); toolbar.subtitle = loaded["host"]; refresh()
        }
    }

    private fun <T> request(call: () -> T, done: (T) -> Unit) {
        progress.visibility = View.VISIBLE; render()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(call) }
            progress.visibility = View.GONE
            result.onSuccess(done).onFailure { status.text = it.message ?: it.javaClass.simpleName; render() }
        }
    }

    private fun refresh() { if (this::admin.isInitialized) request({ admin.backups() }) { info = it; status.text = ""; render() } }

    private fun time(seconds: Long) = if (seconds > 0) dates.format(Date(seconds * 1000)) else "—"

    private fun render() {
        body.removeAllViews()
        body.addView(ui.text("Копии хранятся на VPS, а расписание работает независимо от приложения.", 14f, MobilePalette.muted).apply { setPadding(ui.dp(4), 0, 0, ui.dp(8)) })
        val current = info
        if (current != null) {
            val policy = current.optJSONObject("policy") ?: JSONObject()
            val runtime = current.optJSONObject("state") ?: JSONObject()
            body.addView(ui.card().apply {
                addView(ui.text("Автоматические копии", 18f, bold = true))
                addView(ui.text(if (policy.optBoolean("enabled")) "Каждые ${policy.optInt("interval_hours", 24)} ч, хранится ${policy.optInt("retention_count", 14)}"
                    else "Создаются только вручную и перед опасными операциями", 14f, MobilePalette.muted))
                addView(LinearLayout(context).apply {
                    setPadding(0, ui.dp(12), 0, ui.dp(12))
                    addView(ui.stat("Последняя", time(runtime.optLong("last_success_at"))), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(ui.stat("Следующая", time(current.optLong("next_run_at"))), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(ui.stat("Объём", ui.bytes(current.optLong("total_bytes"))), LinearLayout.LayoutParams(0, -2, 1f))
                })
                runtime.optString("last_error").takeIf(String::isNotBlank)?.let { addView(ui.text("Ошибка: $it", 13f, android.graphics.Color.rgb(232, 120, 112))) }
                addView(LinearLayout(context).apply {
                    addView(ui.button("Расписание") { schedule(policy) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
                    addView(ui.button("Создать копию", primary = true) { request({ admin.createBackup() }) { info = it; render() } }, LinearLayout.LayoutParams(0, -2, 1f))
                })
            })
            body.addView(ui.section("История"))
            val backups = current.optJSONArray("backups")
            if (backups == null || backups.length() == 0) body.addView(ui.text("Копий пока нет", 14f, MobilePalette.muted))
            else for (index in 0 until backups.length()) body.addView(backupCard(backups.getJSONObject(index)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        }
        body.addView(progress); body.addView(status)
    }

    private fun backupCard(backup: JSONObject) = ui.card().apply {
        val id = backup.optString("id")
        addView(ui.text(time(backup.optLong("created_at")), 17f, bold = true))
        addView(ui.text(listOf(backup.optString("reason"), "сервер " + backup.optString("server_version"), ui.bytes(backup.optLong("size_bytes")))
            .filter(String::isNotBlank).joinToString(" · "), 13f, MobilePalette.muted))
        addView(ui.text(if (backup.optBoolean("valid")) "Проверена: в порядке" else "Повреждена: " + backup.optString("error"), 13f,
            if (backup.optBoolean("valid")) android.graphics.Color.rgb(110, 200, 120) else android.graphics.Color.rgb(232, 120, 112)).apply { setPadding(0, ui.dp(4), 0, ui.dp(10)) })
        addView(LinearLayout(context).apply {
            addView(ui.button("Проверить") { request({ admin.verifyBackup(id) }) { refresh() } }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) })
            addView(ui.button("Скачать") { export(id) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) })
            addView(ui.button("Удалить", danger = true) {
                MobileAlertDialogBuilder(context).setMessage("Удалить копию ${time(backup.optLong("created_at"))}?").setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("Удалить") { _, _ -> request({ admin.deleteBackup(id) }) { info = it; render() } }.show()
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
    }

    private fun schedule(policy: JSONObject) {
        val sheet = MobileSheetDialog(this)
        val form = ui.column(20).apply { setBackgroundColor(MobilePalette.surface) }
        form.addView(ui.text("Автоматические копии", 22f, bold = true))
        val enabled = android.widget.CheckBox(this).apply { text = "Включены"; setTextColor(MobilePalette.text); isChecked = policy.optBoolean("enabled") }
        form.addView(enabled)
        val interval = ui.input("Интервал, ч (6–168)", policy.optInt("interval_hours", 24).toString(), number = true)
        val retention = ui.input("Количество (2–90)", policy.optInt("retention_count", 14).toString(), number = true)
        form.addView(interval); form.addView(retention); form.addView(ui.gap(12))
        form.addView(ui.button("Сохранить", primary = true) {
            val hours = interval.text.toString().toIntOrNull()?.takeIf { it in 6..168 } ?: return@button Toast.makeText(this, "Интервал: от 6 до 168 ч", Toast.LENGTH_SHORT).show()
            val count = retention.text.toString().toIntOrNull()?.takeIf { it in 2..90 } ?: return@button Toast.makeText(this, "Количество: от 2 до 90", Toast.LENGTH_SHORT).show()
            sheet.dismiss(); request({ admin.configureBackups(enabled.isChecked, hours, count) }) { info = it; render() }
        })
        sheet.setContentView(ScrollView(this).apply { addView(form) }); sheet.show()
    }

    /** As WDTT Plus downloads a copy: its portable backup file, encrypted with a password of at least 8 characters. */
    private fun export(id: String) {
        val password = ui.input("Пароль файла (не меньше 8 символов)").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        MobileAlertDialogBuilder(this).setTitle("Скачать копию").setView(LinearLayout(this).apply { setPadding(ui.dp(20), 0, ui.dp(20), 0); addView(password, LinearLayout.LayoutParams(-1, -2)) })
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton("Скачать") { _, _ ->
                val chars = password.text.toString().toCharArray()
                if (chars.size < 8) { Toast.makeText(this, "Пароль должен содержать не меньше 8 символов", Toast.LENGTH_LONG).show(); return@setPositiveButton }
                request({ encrypt(portable(admin.exportBackup(id)), chars) }) { document ->
                    pendingDocument = document; save.launch("wdtt-plus-backup-${server["host"]}-$id.json")
                }
            }.show()
    }

    private fun portable(snapshot: JSONObject): String {
        require(snapshot.optString("format") == "wdtt-server-snapshot") { "Неподдерживаемый формат серверной копии" }
        val files = snapshot.optJSONArray("files") ?: throw IllegalArgumentException("В копии нет файлов")
        val data = (0 until files.length()).map { files.getJSONObject(it) }.associate { it.getString("path") to String(android.util.Base64.decode(it.getString("data_b64"), android.util.Base64.DEFAULT), Charsets.UTF_8) }
        val passwords = data["passwords.json"] ?: throw IllegalArgumentException("В копии нет passwords.json")
        val wgKeys = data["wg-keys.dat"] ?: throw IllegalArgumentException("В копии нет wg-keys.dat")
        fun b64(value: String) = android.util.Base64.encodeToString(value.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val db = JSONObject(passwords)
        val result = JSONObject().put("format", "wdtt-server-backup").put("version", 4)
            .put("created_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date(snapshot.optLong("created_at") * 1000))).put("source_host", server["host"])
            .put("passwords_json_b64", b64(passwords)).put("passwords_json_sha256", sha(passwords))
            .put("password_count", db.optJSONObject("passwords")?.length() ?: 0).put("device_count", snapshot.optInt("device_count"))
            .put("owner_profile_source", "server")
            .put("wg_keys_dat_b64", b64(wgKeys)).put("wg_keys_dat_sha256", sha(wgKeys))
        data["outbound-profile.env"]?.let { result.put("outbound_profile_env_b64", b64(it)).put("outbound_profile_env_sha256", sha(it)) }
        data["backup-policy.json"]?.let { result.put("backup_policy_json_b64", b64(it)).put("backup_policy_json_sha256", sha(it)) }
        return result.toString(2)
    }

    /** WdttTransferCodec.encryptServerBackup: PBKDF2-HMAC-SHA256 (210000), AES-256-GCM over the gzipped document. */
    private fun encrypt(value: String, password: CharArray): String {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes); val iv = ByteArray(12).also(random::nextBytes)
        val spec = PBEKeySpec(password, salt, 210_000, 256)
        val key = try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") } finally { spec.clearPassword(); password.fill('\u0000') }
        val compressed = ByteArrayOutputStream().use { out -> GZIPOutputStream(out).use { it.write(value.toByteArray(Charsets.UTF_8)) }; out.toByteArray() }
        val encrypted = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }.doFinal(compressed)
        return JSONObject().put("format", "wdtt-plus-transfer").put("version", 1).put("kind", "server-backup")
            .put("createdAt", System.currentTimeMillis()).put("sourceVersion", "qThroneMobile")
            .put("kdf", "PBKDF2-HMAC-SHA256").put("iterations", 210_000)
            .put("salt", android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP)).put("iv", android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
            .put("data", android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)).toString()
    }
}
