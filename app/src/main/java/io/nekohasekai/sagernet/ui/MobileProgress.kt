package app.rawline.mobile.ui

import android.content.Context
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.widget.ProgressBar

/** The progress bar of downloads and installs: a rounded track and a rounded fill; in the new style the fill glows from muted to white. */
object MobileProgress {
    fun bar(context: Context): ProgressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        val radius = 8 * context.resources.displayMetrics.density
        val track = GradientDrawable().apply { setColor(MobilePalette.well); cornerRadius = radius }
        val fill = if (MobileStyle.new) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(MobilePalette.muted, MobilePalette.text)).apply { cornerRadius = radius }
            else GradientDrawable().apply { setColor(MobilePalette.accent(context)); cornerRadius = radius }
        progressDrawable = LayerDrawable(arrayOf(track, ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL))).apply {
            setId(0, android.R.id.background); setId(1, android.R.id.progress)
        }
        indeterminateTintList = android.content.res.ColorStateList.valueOf(MobilePalette.text)
        max = 100
    }

    /** The material indicator of a layout drawn the same way: the theme's primary colour can match the dialog behind it. */
    fun style(indicator: com.google.android.material.progressindicator.LinearProgressIndicator) = indicator.apply {
        val density = context.resources.displayMetrics.density
        setIndicatorColor(if (MobileStyle.new) MobilePalette.text else MobilePalette.accent(context))
        trackColor = MobilePalette.well
        trackThickness = (6 * density).toInt(); trackCornerRadius = (3 * density).toInt()
    }
}
