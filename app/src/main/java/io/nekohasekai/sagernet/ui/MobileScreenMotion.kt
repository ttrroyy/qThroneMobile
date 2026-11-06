package app.rawline.mobile.ui

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.os.Build
import android.view.View
import android.view.animation.AnimationUtils
import androidx.fragment.app.Fragment
import app.rawline.mobile.R

/** Screen changes: the classic style fades quickly everywhere; the new style rises softly, screens and windows alike. */
object MobileScreenMotion {
    /** Some phones hold an app at 60 Hz unless its window asks for the fastest mode of the screen. */
    fun fullFrameRate(window: android.view.Window) {
        @Suppress("DEPRECATION") val display = window.windowManager.defaultDisplay ?: return
        val mode = display.mode
        val fastest = display.supportedModes.filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }.maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = fastest.modeId }
    }

    fun create(fragment: Fragment, nextAnim: Int): Animator? {
        val view = fragment.view ?: return null
        val incoming = nextAnim == R.anim.settings_enter || nextAnim == R.anim.settings_pop_enter
        if (nextAnim !in listOf(R.anim.settings_enter, R.anim.settings_exit, R.anim.settings_pop_enter, R.anim.settings_pop_exit)) return null
        view.translationX = 0f
        val fade = ObjectAnimator.ofFloat(view, View.ALPHA, if (incoming) 0f else 1f, if (incoming) 1f else 0f)
        if (!MobileStyle.new) return fade.apply { duration = 90 }
        fade.duration = if (incoming) 180 else 120
        val rise = view.resources.displayMetrics.density * 12
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        return AnimatorSet().apply {
            val soft = AnimationUtils.loadInterpolator(view.context, android.R.interpolator.fast_out_slow_in)
            interpolator = soft
            if (incoming) playTogether(fade, ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, rise, 0f).setDuration(220))
            else play(fade)
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { view.translationY = 0f; view.setLayerType(View.LAYER_TYPE_NONE, null) }
            })
        }
    }

    @Suppress("DEPRECATION")
    fun open(activity: Activity) {
        val enter = if (MobileStyle.new) R.anim.screen_soft_enter else R.anim.settings_enter
        val exit = if (MobileStyle.new) R.anim.screen_soft_exit else R.anim.settings_exit
        val popEnter = if (MobileStyle.new) R.anim.screen_soft_pop_enter else R.anim.settings_pop_enter
        val popExit = if (MobileStyle.new) R.anim.screen_soft_pop_exit else R.anim.settings_pop_exit
        if (Build.VERSION.SDK_INT >= 34) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, enter, exit)
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, popEnter, popExit)
        } else activity.overridePendingTransition(enter, exit)
    }

    @Suppress("DEPRECATION")
    fun close(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 34) return
        if (MobileStyle.new) activity.overridePendingTransition(R.anim.screen_soft_pop_enter, R.anim.screen_soft_pop_exit)
        else activity.overridePendingTransition(R.anim.settings_pop_enter, R.anim.settings_pop_exit)
    }
}
