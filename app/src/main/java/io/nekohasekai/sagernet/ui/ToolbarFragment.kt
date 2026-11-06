package app.rawline.mobile.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.AppBarLayout
import app.rawline.mobile.R
import app.rawline.mobile.utils.Theme
import app.rawline.mobile.widget.applyTopInset

open class ToolbarFragment : Fragment {

    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    override fun onCreateAnimator(transit: Int, enter: Boolean, nextAnim: Int): android.animation.Animator? =
        app.rawline.mobile.ui.MobileScreenMotion.create(this, nextAnim)

    // Null when the layout has no toolbar or the view is not created yet
    var toolbar: Toolbar? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<AppBarLayout>(R.id.appbar)?.applyTopInset()
        toolbar = view.findViewById(R.id.toolbar)
        toolbar?.setBackgroundColor(MobilePalette.toolbar)
        toolbar?.setTitleTextColor(MobilePalette.text)
        toolbar?.setNavigationIcon(R.drawable.baseline_arrow_back_24)
        toolbar?.setNavigationContentDescription(R.string.navigation_back)
        toolbar?.navigationIcon?.setTint(MobilePalette.text)
        // White theme: the toolbar is white, so the menu, title and navigation icon turn dark
        if (Theme.isWhiteTheme()) {
            toolbar?.apply {
                setTitleTextColor(ContextCompat.getColor(requireContext(), R.color.black))
                navigationIcon?.setTint(ContextCompat.getColor(requireContext(), R.color.black))
            }
        }
        toolbar?.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    open fun onKeyDown(ketCode: Int, event: KeyEvent) = false
}
