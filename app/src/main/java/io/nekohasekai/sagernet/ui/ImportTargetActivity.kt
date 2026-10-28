package app.qthrone.mobile.ui

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.qthrone.mobile.R
import app.qthrone.mobile.database.GroupRepo
import app.qthrone.mobile.group.SubscriptionClient
import app.qthrone.mobile.ktx.onMainDispatcher
import app.qthrone.mobile.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A private cache file keeps large imports below Android's Binder transaction limit. */
class ImportTargetActivity : ThemedActivity() {
    private lateinit var payload: File
    private var kind = ""
    private var content = ""
    private var transferred = false
    private var suggestedName = ""
    private val createGroup = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val id = result.data?.getLongExtra(GroupSettingsActivity.EXTRA_GROUP_ID, 0L) ?: 0L
            if (id > 0) importInto(id, created = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("payload").orEmpty()
        if (!name.matches(Regex("[0-9a-f-]{36}\\.json"))) { finish(); return }
        payload = File(cacheDir, "import-target/$name")
        val document = runCatching { JSONObject(payload.readText()) }.getOrNull()
        if (document == null) { finish(); return }
        kind = document.getString("kind"); content = document.getString("content")
        suggestedName = document.optString("name")
        fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MobilePalette.background)
        }
        ViewCompat.setOnApplyWindowInsetsListener(body) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(20) + bars.left, dp(16) + bars.top, dp(20) + bars.right, dp(16) + bars.bottom)
            insets
        }
        body.addView(TextView(this).apply {
            setText(R.string.qmobile_import_target); textSize = 22f; setTextColor(MobilePalette.text)
            setPadding(0, dp(12), 0, dp(24))
        })
        fun choice(label: Int, click: () -> Unit) {
            body.addView(TextView(this).apply {
                setText(label); textSize = 17f; setTextColor(MobilePalette.text)
                gravity = android.view.Gravity.CENTER_VERTICAL
                minHeight = dp(56); setPadding(dp(16), dp(12), dp(16), dp(12)); isFocusable = true
                background = GradientDrawable().apply {
                    setColor(MobilePalette.surface); cornerRadius = dp(16).toFloat(); setStroke(1, MobilePalette.outline)
                }
                MobileFeedback.apply(this); setOnClickListener { click() }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
        choice(R.string.qmobile_new_group) {
            createGroup.launch(Intent(this, SubscriptionEditorActivity::class.java).apply {
                val parsed = if (kind == "text") Uri.parse(content) else null
                val link = if (kind == "text") SubscribeFlows.subscribeLink(content) else null
                val subscription = link?.url ?: parsed?.takeIf { it.scheme in listOf("http", "https") && it.userInfo == null }
                    ?.buildUpon()?.fragment(null)?.build()?.toString()
                subscription?.let { putExtra(GroupSettingsActivity.EXTRA_GROUP_SUBSCRIPTION_LINK, it) }
                val name = suggestedName.ifBlank { link?.name.orEmpty().ifBlank { parsed?.fragment.orEmpty() } }
                if (name.isNotBlank()) putExtra(GroupSettingsActivity.EXTRA_GROUP_NAME, name)
            })
        }
        choice(R.string.qmobile_existing_group) { selectGroup() }
        choice(android.R.string.cancel) { finish() }
        setContentView(body)
    }

    private fun selectGroup() {
        lifecycleScope.launch {
            val groups = withContext(Dispatchers.IO) { GroupRepo.all() }
            if (isFinishing) return@launch
            var selected = -1
            val dialog = MobileAlertDialogBuilder(this@ImportTargetActivity)
                .setTitle(R.string.qmobile_existing_group)
                .setSingleChoiceItems(groups.map { it.displayName() }.toTypedArray(), -1) { _, which -> selected = which }
                .setPositiveButton(android.R.string.ok) { _, _ -> groups.getOrNull(selected)?.let { importInto(it.id) } }
                .setNegativeButton(android.R.string.cancel, null).create()
            dialog.setOnShowListener {
                val ok = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                ok.isEnabled = false
                dialog.listView.setOnItemClickListener { _, _, which, _ -> selected = which; ok.isEnabled = true }
            }
            dialog.show()
        }
    }

    private fun importInto(id: Long, created: Boolean = false) {
        lifecycleScope.launch {
            val exists = withContext(Dispatchers.IO) {
                if (GroupRepo.get(id) == null) false else {
                    payload.writeText(JSONObject().put("kind", kind).put("content", content)
                        .put("groupId", id).put("created", created).toString())
                    true
                }
            }
            if (!exists) return@launch
            // File/QR/provider parsing must outlive this destination screen.
            transferred = true
            startActivity(Intent(this@ImportTargetActivity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_IMPORT_TARGET, payload.name))
            finish()
        }
    }

    override fun onDestroy() {
        if (isFinishing && !transferred && ::payload.isInitialized) payload.delete()
        super.onDestroy()
    }

    companion object {
        fun consume(activity: MainActivity, name: String) = runOnDefaultDispatcher {
            if (!name.matches(Regex("[0-9a-f-]{36}\\.json"))) return@runOnDefaultDispatcher
            val file = File(activity.cacheDir, "import-target/$name")
            try {
                val document = JSONObject(file.readText())
                val id = document.optLong("groupId")
                // Only the private file written after confirmation may bypass the destination picker.
                val group = if (id > 0) GroupRepo.get(id) else null
                if (group == null) return@runOnDefaultDispatcher
                val content = document.getString("content")
                val kind = document.getString("kind")
                val uri = Uri.parse(content)
                val subscriptionSource = kind == "text" && (SubscribeFlows.subscribeLink(content) != null ||
                    (uri.scheme in listOf("http", "https") && uri.userInfo == null))
                GroupRepo.setCurrent(id)
                onMainDispatcher {
                    activity.displayFragmentWithId(R.id.nav_mobile_home)
                    if (document.optBoolean("created") && group.isSubscription && subscriptionSource) SubscriptionClient.refreshGroup(id, false)
                    else when (kind) {
                        "manual" -> activity.startActivity(Intent(activity, app.qthrone.mobile.ui.profile.settingsActivityFor(content))
                            .putExtra("qmobileProfileType", content).putExtra("qmobileGroupId", id))
                        "file" -> SubscribeFlows.importFile(activity, Uri.parse(content), id)
                        "batch" -> {
                            val items = org.json.JSONArray(content)
                            SubscribeFlows.importTexts(activity, (0 until items.length()).map { items.getString(it) }, id)
                        }
                        else -> SubscribeFlows.importText(activity, content, id)
                    }
                }
            } catch (error: Exception) {
                onMainDispatcher { android.widget.Toast.makeText(activity, R.string.provider_crypto_import_error, android.widget.Toast.LENGTH_LONG).show() }
            } finally { file.delete() }
        }

        fun start(activity: FragmentActivity, kind: String, content: String, name: String = "") = runOnDefaultDispatcher {
            val directory = File(activity.cacheDir, "import-target").apply { mkdirs() }
            val file = File(directory, "${UUID.randomUUID()}.json")
            try {
                file.writeText(JSONObject().put("kind", kind).put("content", content).put("name", name).toString())
                onMainDispatcher {
                    if (activity.isFinishing || activity.isDestroyed) file.delete()
                    else activity.startActivity(Intent(activity, ImportTargetActivity::class.java).putExtra("payload", file.name))
                }
            } catch (error: Exception) {
                file.delete()
                onMainDispatcher { android.widget.Toast.makeText(activity, R.string.provider_crypto_import_error, android.widget.Toast.LENGTH_LONG).show() }
            }
        }
    }
}
