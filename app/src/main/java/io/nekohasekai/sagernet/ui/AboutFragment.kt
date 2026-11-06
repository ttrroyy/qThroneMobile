package app.rawline.mobile.ui

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.util.Linkify
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import androidx.recyclerview.widget.RecyclerView
import com.danielstone.materialaboutlibrary.MaterialAboutFragment
import com.danielstone.materialaboutlibrary.items.MaterialAboutActionItem
import com.danielstone.materialaboutlibrary.model.MaterialAboutCard
import com.danielstone.materialaboutlibrary.model.MaterialAboutList
import com.google.android.material.card.MaterialCardView
import app.rawline.mobile.BuildConfig
import app.rawline.mobile.R
import app.rawline.mobile.SagerNet
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.databinding.LayoutAboutBinding
import app.rawline.mobile.ktx.*
import app.rawline.mobile.update.UpdateActivity
import app.rawline.mobile.update.UpdateChecker
import app.rawline.mobile.widget.applyListInsets

class AboutFragment : ToolbarFragment(R.layout.layout_about) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutAboutBinding.bind(view)

        binding.aboutScroll.applyListInsets()
        toolbar?.setTitle(R.string.menu_about)

        binding.license.maxLines = LICENSE_COLLAPSED_MAX_LINES
        var isLicenseExpanded = false
        binding.licenseToggle.setOnClickListener {
            val scrollY = binding.aboutScroll.scrollY
            isLicenseExpanded = !isLicenseExpanded
            binding.license.maxLines = if (isLicenseExpanded) {
                Int.MAX_VALUE
            } else {
                LICENSE_COLLAPSED_MAX_LINES
            }
            binding.licenseIndicator.text = if (isLicenseExpanded) "▲" else "▼"
            binding.aboutScroll.doOnPreDraw {
                binding.aboutScroll.scrollTo(0, scrollY)
            }
        }

        childFragmentManager.beginTransaction()
            .replace(R.id.about_fragment_holder, AboutContent())
            .commitAllowingStateLoss()

        runOnDefaultDispatcher {
            val license = view.context.assets.open("LICENSE").bufferedReader().readText()
            onMainDispatcher {
                binding.license.text = license
                Linkify.addLinks(binding.license, Linkify.EMAIL_ADDRESSES or Linkify.WEB_URLS)
            }
        }
    }

    companion object {
        private const val LICENSE_COLLAPSED_MAX_LINES = 8
    }

    class AboutContent : MaterialAboutFragment() {

        override fun getMaterialAboutList(activityContext: Context): MaterialAboutList {
            return MaterialAboutList.Builder()
                .addCard(
                    MaterialAboutCard.Builder()
                        .outline(true)
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_baseline_update_24)
                                .text(R.string.app_version)
                                .subText(SagerNet.appVersionNameForDisplay)
                                .setOnClickAction {
                                    requireContext().launchCustomTab(UpdateChecker.RELEASES_PAGE)
                                }
                                .build())
                        .apply {
                            if (BuildConfig.IN_APP_UPDATER) {
                                addItem(
                                    MaterialAboutActionItem.Builder()
                                        .icon(R.drawable.ic_baseline_download_24)
                                        .text(R.string.update_check)
                                        .subText(
                                            if (DataStore.allowBetaUpdate) R.string.update_channel_beta
                                            else R.string.update_channel_stable
                                        )
                                        .setOnClickAction {
                                            UpdateActivity.start(requireContext())
                                        }
                                        .build())
                            }
                        }
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_baseline_layers_24)
                                .text(getString(R.string.version_x, "RawlineCore"))
                                .subText(shortRef(BuildConfig.THRONE_CORE_REF))
                                .setOnClickAction(::copyCoreRef)
                                .setOnLongClickAction(::copyCoreRef)
                                .build())
                        .build())
                .addCard(
                    MaterialAboutCard.Builder()
                        .outline(true)
                        .title(R.string.project)
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_baseline_code_24)
                                .text(R.string.github)
                                .setOnClickAction {
                                    requireContext().launchCustomTab(
                                        "https://github.com/ttrroyy/qThroneMobile"

                                    )
                                }
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.baseline_public_24)
                                .text(R.string.project_website)
                                .setOnClickAction {
                                    requireContext().launchCustomTab(
                                        "https://throneproj.github.io"
                                    )
                                }
                                .build())
                        .build())
                .build()

        }

        /** A commit hash is shortened to 8 characters, a branch or tag name stays as it is. */
        private fun shortRef(ref: String): String =
            if (ref.length == 40 && ref.all { it in '0'..'9' || it in 'a'..'f' }) ref.take(8) else ref

        private fun copyCoreRef() {
            val copied = SagerNet.trySetPrimaryClip(BuildConfig.THRONE_CORE_REF)
            // Android 13+ confirms a clipboard copy itself
            if (copied && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            snackbar(if (copied) R.string.grp_copied else R.string.action_export_err).show()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            view.layoutParams = (view.layoutParams ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )).apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            view.findViewById<RecyclerView>(R.id.mal_recyclerview)?.apply {
                isNestedScrollingEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = (layoutParams ?: ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )).apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }

                val cardStrokeWidth = resources.getDimensionPixelSize(
                    R.dimen.card_stroke_width
                )
                val cardStrokeColor = ContextCompat.getColor(
                    requireContext(),
                    R.color.card_stroke
                )
                val cardCornerRadius = resources.getDimension(
                    R.dimen.card_corner_radius
                )
                fun applyApplicationCardStyle(child: View) {
                    (child as? MaterialCardView)?.apply {
                        strokeWidth = cardStrokeWidth
                        strokeColor = cardStrokeColor
                        radius = cardCornerRadius
                        cardElevation = 0f
                    }
                }

                addOnChildAttachStateChangeListener(
                    object : RecyclerView.OnChildAttachStateChangeListener {
                        override fun onChildViewAttachedToWindow(child: View) {
                            applyApplicationCardStyle(child)
                        }

                        override fun onChildViewDetachedFromWindow(child: View) = Unit
                    }
                )
                for (index in 0 until childCount) {
                    applyApplicationCardStyle(getChildAt(index))
                }
            }
        }
    }

}
