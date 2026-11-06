package app.rawline.mobile.update

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.rawline.mobile.BuildConfig
import app.rawline.mobile.R
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.databinding.LayoutUpdateBinding
import app.rawline.mobile.ktx.launchCustomTab
import app.rawline.mobile.ui.ThemedActivity
import app.rawline.mobile.update.UpdateManager.State
import kotlinx.coroutines.launch

/** The updater UI (a dialog): check result, download progress, verification and install, driven by [UpdateManager]. */
class UpdateActivity : ThemedActivity() {

    companion object {
        private const val EXTRA_DISCOVERED_OFFER = "discoveredOffer"

        fun start(context: Context, offer: UpdateChecker.Offer? = null) {
            val showOffer = offer != null && UpdateManager.showOffer(offer)
            context.startActivity(Intent(context, UpdateActivity::class.java).putExtra(EXTRA_DISCOVERED_OFFER, showOffer))
        }
    }

    override val isDialog = true

    private lateinit var binding: LayoutUpdateBinding
    private var awaitingPermission: UpdateChecker.Offer? = null

    private val unknownSources = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val offer = awaitingPermission ?: return@registerForActivityResult
        if (canInstall()) {
            awaitingPermission = null
            UpdateManager.download(offer)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme.applyStyle(R.style.ThemeOverlay_SagerNet_Dialog_WrapHeight, true)
        binding = LayoutUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setFinishOnTouchOutside(false)
        if (savedInstanceState == null) {
            val state = UpdateManager.state.value
            if (state is State.Idle || state is State.UpToDate ||
                state is State.Available && !intent.getBooleanExtra(EXTRA_DISCOVERED_OFFER, false) ||
                state is State.Failed && state.offer == null
            ) {
                UpdateManager.check()
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UpdateManager.state.collect { render(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        UpdateManager.uiVisible = true
    }

    override fun onStop() {
        UpdateManager.uiVisible = false
        super.onStop()
    }

    private fun canInstall() = Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    private fun render(state: State) {
        binding.currentVersion.text = getString(R.string.update_current_version, BuildConfig.VERSION_NAME)
        binding.currentVersion.visibility = if (state is State.Checking || state is State.UpToDate ||
            state is State.Failed && state.offer == null) View.VISIBLE else View.GONE
        awaitingPermission?.let {
            if (state is State.Available) return renderPermission(it)
            awaitingPermission = null
        }
        binding.progressText.visibility = View.GONE
        when (state) {
            State.Idle -> finish()
            State.Checking -> {
                show(getString(R.string.update_checking), null, progress = true)
                buttons(negative = getString(android.R.string.cancel) to {
                    UpdateManager.cancel()
                    finish()
                })
            }

            State.UpToDate -> {
                val channel = if (DataStore.allowBetaUpdate) R.string.update_channel_beta else R.string.update_channel_stable
                show(
                    getString(R.string.update_up_to_date),
                    getString(channel),
                )
                buttons(positive = getString(android.R.string.ok) to {
                    UpdateManager.dismiss()
                    finish()
                })
            }

            is State.Available -> renderOffer(state.offer)

            is State.Downloading -> {
                show(getString(R.string.update_downloading), null, progress = false)
                binding.progress.max = 1000
                binding.progress.progress = if (state.total > 0) (state.done * 1000 / state.total).toInt() else 0
                binding.progressText.apply {
                    visibility = View.VISIBLE
                    text = getString(
                        R.string.update_progress,
                        Formatter.formatShortFileSize(context, state.done),
                        Formatter.formatShortFileSize(context, state.total)
                    )
                }
                buttons(
                    negative = getString(android.R.string.cancel) to { UpdateManager.cancel() },
                    positive = getString(R.string.update_action_hide) to { finish() },
                )
            }

            is State.Verifying -> {
                show(getString(R.string.update_verifying), null, progress = true)
                buttons(positive = getString(R.string.update_action_hide) to { finish() })
            }

            is State.Installing -> {
                show(getString(R.string.update_installing), getString(R.string.update_installing_hint), progress = true)
                buttons(
                    negative = getString(R.string.update_action_close) to {
                        UpdateManager.dismiss()
                        finish()
                    },
                    positive = getString(R.string.update_action_hide) to { finish() },
                )
            }

            is State.Failed -> {
                val offer = state.offer
                show(getString(if (offer == null) R.string.update_check_failed else R.string.update_failed), state.message)
                buttons(
                    neutral = getString(R.string.update_action_browser) to { openReleasePage(offer) },
                    negative = getString(R.string.update_action_close) to {
                        UpdateManager.dismiss()
                        finish()
                    },
                    positive = getString(R.string.update_action_retry) to {
                        if (offer != null && offer.installable) startUpdate(offer) else UpdateManager.check()
                    },
                )
            }
        }
    }

    private fun renderOffer(offer: UpdateChecker.Offer) {
        val message = buildString {
            val newVersion = if (offer.release.prerelease) R.string.update_new_version_pre else R.string.update_new_version
            appendLine(getString(newVersion, offer.versionName))
            offer.expected?.let { appendLine(getString(R.string.update_size, Formatter.formatShortFileSize(this@UpdateActivity, it.size))) }
            offer.browserReason?.let {
                appendLine()
                appendLine(getString(R.string.update_browser_only, it))
            }
            val notes = UpdateReleaseNotes.visible(offer.release.body)
            if (notes.isNotEmpty()) {
                appendLine()
                appendLine(notes.take(4000))
            }
            if (offer.installable) {
                appendLine()
                append(getString(R.string.update_restart_note))
            }
        }.trim()
        show(getString(R.string.update_dialog_title), message)
        val later = getString(R.string.update_action_later) to {
            UpdateManager.dismiss()
            finish()
        }
        if (offer.installable) {
            buttons(
                neutral = getString(R.string.update_action_browser) to { openReleasePage(offer) },
                negative = later,
                positive = getString(R.string.update_action_update) to { startUpdate(offer) },
            )
        } else {
            buttons(
                negative = later,
                positive = getString(R.string.update_action_browser) to {
                    openReleasePage(offer)
                    UpdateManager.dismiss()
                    finish()
                },
            )
        }
    }

    private fun renderPermission(offer: UpdateChecker.Offer) {
        show(getString(R.string.update_permission_title), getString(R.string.update_permission_message))
        buttons(
            negative = getString(android.R.string.cancel) to {
                awaitingPermission = null
                renderOffer(offer)
            },
            positive = getString(R.string.open_settings) to { requestPermission() },
        )
    }

    private fun startUpdate(offer: UpdateChecker.Offer) {
        if (canInstall()) {
            UpdateManager.download(offer)
        } else {
            awaitingPermission = offer
            renderPermission(offer)
        }
    }

    private fun requestPermission() {
        try {
            unknownSources.launch(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri())
            )
        } catch (e: Exception) {
            Toast.makeText(this, R.string.update_permission_message, Toast.LENGTH_LONG).show()
        }
    }

    private fun openReleasePage(offer: UpdateChecker.Offer?) {
        launchCustomTab(offer?.release?.htmlUrl ?: UpdateChecker.RELEASES_PAGE)
    }

    /** [progress]: null hides the bar, true is indeterminate. */
    private fun show(title: String, message: String?, progress: Boolean? = null) {
        progressBar(progress)
        binding.title.text = title
        binding.message.text = message.orEmpty()
        val width=(resources.displayMetrics.widthPixels*.8f - 48*resources.displayMetrics.density).toInt()
        binding.message.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        binding.messageScroll.layoutParams=binding.messageScroll.layoutParams.apply {
            height=binding.message.measuredHeight.coerceAtMost((resources.displayMetrics.heightPixels*.45f).toInt())
            (this as? android.widget.LinearLayout.LayoutParams)?.weight=0f
        }
        binding.messageScroll.visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    // The indicator switches mode only while hidden.
    private fun progressBar(indeterminate: Boolean?) {
        val bar = binding.progress
        if (indeterminate == null) {
            bar.visibility = View.GONE
            return
        }
        if (bar.isIndeterminate != indeterminate) {
            bar.visibility = View.GONE
            bar.isIndeterminate = indeterminate
        }
        bar.visibility = View.VISIBLE
    }

    private fun buttons(
        neutral: Pair<String, () -> Unit>? = null,
        negative: Pair<String, () -> Unit>? = null,
        positive: Pair<String, () -> Unit>? = null,
    ) {
        fun Button.bind(action: Pair<String, () -> Unit>?) {
            visibility = if (action == null) View.GONE else View.VISIBLE
            text = action?.first
            setOnClickListener { action?.second?.invoke() }
        }
        binding.neutral.bind(neutral)
        binding.negative.bind(negative)
        binding.positive.bind(positive)
    }
}
