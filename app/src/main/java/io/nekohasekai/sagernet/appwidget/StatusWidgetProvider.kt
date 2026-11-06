package app.rawline.mobile.appwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/** 4×1: previous, the profile with its group and speed (tap toggles), next. */
class StatusWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) =
        Widgets.push(context, goAsync())

    // Re-rendering refreshes the ids the speed updates go to.
    override fun onDeleted(context: Context, appWidgetIds: IntArray) = Widgets.push(context, goAsync())

    override fun onDisabled(context: Context) = Widgets.forgetIds()
}
