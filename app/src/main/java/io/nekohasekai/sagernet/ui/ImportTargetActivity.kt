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
                val subscription = if (kind == "text") SubscribeFlows.subscribeLink(content)?.url
                    ?: content.takeIf { it.startsWith("https://") || it.startsWith("http://") } else null
                subscription?.let { putExtra(GroupSettingsActivity.EXTRA_GROUP_SUBSCRIPTION_LINK, it) }
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
            val group = withContext(Dispatchers.IO) { GroupRepo.get(id) } ?: return@launch
            GroupRepo.setCurrent(id)
            if (created && group.isSubscription) SubscriptionClient.refreshGroup(id, false)
            else when (kind) {
                "manual" -> startActivity(Intent(this@ImportTargetActivity, app.qthrone.mobile.ui.profile.settingsActivityFor(content))
                    .putExtra("qmobileProfileType", content).putExtra("qmobileGroupId", id))
                "file" -> SubscribeFlows.importFile(this@ImportTargetActivity, Uri.parse(content), id)
                "batch" -> {
                    val items = org.json.JSONArray(content)
                    SubscribeFlows.importTexts(this@ImportTargetActivity, (0 until items.length()).map { items.getString(it) }, id)
                }
                else -> SubscribeFlows.importText(this@ImportTargetActivity, content, id)
            }
            finish()
        }
    }

    override fun onDestroy() {
        if (isFinishing && ::payload.isInitialized) payload.delete()
        super.onDestroy()
    }

    companion object {
        fun start(activity: FragmentActivity, kind: String, content: String) = runOnDefaultDispatcher {
            val directory = File(activity.cacheDir, "import-target").apply { mkdirs() }
            val file = File(directory, "${UUID.randomUUID()}.json")
            try {
                file.writeText(JSONObject().put("kind", kind).put("content", content).toString())
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
