package app.qthrone.mobile.ktx

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import app.qthrone.mobile.ui.MobileAlertDialogBuilder as MobileAlertDialogBuilder
import app.qthrone.mobile.R

fun Context.alert(text: String): AlertDialog {
    return MobileAlertDialogBuilder(this).setTitle(R.string.error_title)
        .setMessage(text)
        .setPositiveButton(android.R.string.ok, null)
        .create()
}

fun Fragment.alert(text: String) = requireContext().alert(text)

/** Asks before an action: [title] is the question, [message] names what it affects, then Cancel and [action] (a verb). */
fun Context.confirmAction(title: CharSequence, message: CharSequence?, @StringRes action: Int, onConfirm: () -> Unit) {
    MobileAlertDialogBuilder(this)
        .setTitle(title)
        .setMessage(message)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(action) { _, _ -> onConfirm() }
        .show()
}

/** [names] one per line; past [limit] the rest is only counted. */
fun Context.nameList(names: List<String>, limit: Int = 10): String {
    val shown = names.take(limit).joinToString("\n")
    val more = names.size - limit
    return if (more > 0) shown + "\n" + resources.getQuantityString(R.plurals.confirm_more_names, more, more) else shown
}

internal fun Context.resolveActivity(): WrappedHostResolution<Activity> = resolveWrappedHost(
    initial = this,
    hostOrNull = { it as? Activity },
    baseOrNull = { (it as? ContextWrapper)?.baseContext },
)

fun AlertDialog.tryToShow() {
    val initialContext = context
    val resolution = initialContext.resolveActivity()
    val activity = resolution.host
    if (activity == null) {
        Logs.w(
            "AlertDialog.tryToShow skipped: no Activity host; " +
                    "initialContext=${initialContext.javaClass.name}, " +
                    "wrapperDepth=${resolution.wrapperDepth}, " +
                    "loopDetected=${resolution.loopDetected}"
        )
        return
    }
    if (activity.isFinishing) {
        Logs.w(
            "AlertDialog.tryToShow skipped: Activity is finishing; " +
                    "initialContext=${initialContext.javaClass.name}, " +
                    "wrapperDepth=${resolution.wrapperDepth}, " +
                    "activity=${activity.javaClass.name}"
        )
        return
    }
    if (activity.isDestroyed) {
        Logs.w(
            "AlertDialog.tryToShow skipped: Activity is destroyed; " +
                    "initialContext=${initialContext.javaClass.name}, " +
                    "wrapperDepth=${resolution.wrapperDepth}, " +
                    "activity=${activity.javaClass.name}"
        )
        return
    }

    Logs.i(
        "AlertDialog.tryToShow resolved host: " +
                "initialContext=${initialContext.javaClass.name}, " +
                "wrapperDepth=${resolution.wrapperDepth}, " +
                "activity=${activity.javaClass.name}"
    )
    try {
        show()
    } catch (e: Exception) {
        Logs.e("AlertDialog.tryToShow failed while showing on a resolved Activity", e)
    }
}
