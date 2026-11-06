package app.rawline.mobile.ui

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import app.rawline.mobile.R
import app.rawline.mobile.utils.Theme
import app.rawline.mobile.widget.applyTopInset

abstract class ThemedActivity : AppCompatActivity, androidx.preference.PreferenceFragmentCompat.OnPreferenceDisplayDialogCallback {
    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    companion object {
        // The scrims androidx.activity uses for 3-button navigation before API 29 (from 29 the system draws one).
        private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }

    var themeResId = 0
    var uiMode = 0
    open val isDialog = false

    /** Dark status bar icons (a light toolbar colour). */
    var lightStatusBar = false
        private set

    /** The new style's icons live in res/drawable-mcc999: only its screens ask for that country code, notifications and widgets never do. */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(if (MobileStyle.new) newBase.createConfigurationContext(Configuration(newBase.resources.configuration).apply { mcc = 999 }) else newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!isDialog) {
            Theme.apply(this)
        } else {
            Theme.applyDialog(this)
        }
        Theme.applyNightTheme()
        if (!isDialog) enableEdgeToEdge(statusBarStyle(), navigationBarStyle())

        super.onCreate(savedInstanceState)
        MobileScreenMotion.fullFrameRate(window)
        if (!isDialog) MobileScreenMotion.open(this)
        if (!isDialog) MobileScreenMotion.fadeAfterRecreate(this)
        if (MobileStyle.new && !isDialog) window.setBackgroundDrawable(MobileHomeBackdrop())

        supportFragmentManager.registerFragmentLifecycleCallbacks(object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(manager: androidx.fragment.app.FragmentManager, fragment: androidx.fragment.app.Fragment, view: android.view.View, state: Bundle?) {
                if(fragment is androidx.preference.PreferenceFragmentCompat) MobileSettingsStyle.apply(fragment,view)
            }
            override fun onFragmentStarted(manager: androidx.fragment.app.FragmentManager, fragment: androidx.fragment.app.Fragment) {
                val dialog=(fragment as? androidx.fragment.app.DialogFragment)?.dialog as? androidx.appcompat.app.AlertDialog ?: return
                MobileAlertDialogBuilder.style(dialog)
            }
        },true)
        uiMode = resources.configuration.uiMode
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        MobileFeedback.onTouchDown(window.decorView, event)
        return super.dispatchTouchEvent(event)
    }

    override fun finish() {
        super.finish()
        if (!isDialog) MobileScreenMotion.close(this)
    }

    override fun onPreferenceDisplayDialog(caller: androidx.preference.PreferenceFragmentCompat, pref: androidx.preference.Preference): Boolean =
        when(pref) {
            is androidx.preference.EditTextPreference -> { MobilePreferenceDialog.show(caller,pref); true }
            is androidx.preference.ListPreference -> MobileSelector.show(this,pref)
            else -> false
        }

    /** Status bar icons in contrast to the toolbar colour (dark icons on light bars such as the white theme's). */
    private fun statusBarStyle(): SystemBarStyle {
        val toolbar = MaterialColors.getColor(this, R.attr.colorPrimary, Color.BLACK)
        lightStatusBar = ColorUtils.calculateLuminance(toolbar) > 0.5
        return if (lightStatusBar) {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        } else {
            SystemBarStyle.dark(Color.TRANSPARENT)
        }
    }

    /** Navigation bar icons follow the window background; 3-button navigation keeps a scrim. */
    private fun navigationBarStyle() = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { !MobilePalette.light }

    override fun onContentChanged() {
        super.onContentChanged()
        findViewById<AppBarLayout>(R.id.appbar)?.apply {
            applyTopInset()
            setBackgroundColor(MobilePalette.toolbar)
        }
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)?.apply {
            setBackgroundColor(MobilePalette.toolbar)
            setTitleTextColor(MobilePalette.text)
            navigationIcon?.setTint(MobilePalette.text)
        }
    }

    override fun setTheme(resId: Int) {
        super.setTheme(resId)

        themeResId = resId
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (newConfig.uiMode != uiMode) {
            uiMode = newConfig.uiMode
            ActivityCompat.recreate(this)
        }
    }

    fun snackbar(@StringRes resId: Int): Snackbar = snackbar("").setText(resId)
    fun snackbar(text: CharSequence): Snackbar = snackbarInternal(text).apply {
        val density=resources.displayMetrics.density
        setTextColor(MobilePalette.text)
        setActionTextColor(MobilePalette.accent(this@ThemedActivity))
        view.backgroundTintList=null
        view.background=android.graphics.drawable.GradientDrawable().apply {
            setColor(if(MobileStyle.new) MobilePalette.frosted else MobilePalette.surface)
            cornerRadius=MobilePalette.cardDp*density
            setStroke(1,MobilePalette.outline)
        }
        view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).apply {
            maxLines = 10
            textSize=14f
        }
        view.findViewById<TextView>(com.google.android.material.R.id.snackbar_action)?.apply {
            textSize=14f
            MobileFeedback.apply(this)
        }
    }

    internal open fun snackbarInternal(text: CharSequence): Snackbar =
        Snackbar.make(findViewById(android.R.id.content), text, Snackbar.LENGTH_LONG)

}
