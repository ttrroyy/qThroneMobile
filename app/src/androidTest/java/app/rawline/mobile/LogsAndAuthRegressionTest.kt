package app.rawline.mobile

import android.content.Intent
import android.graphics.Bitmap
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.rawline.mobile.aidl.ISagerNetService
import app.rawline.mobile.aidl.ISagerNetServiceCallback
import app.rawline.mobile.ui.LogcatFragment
import app.rawline.mobile.ui.MainActivity
import app.rawline.mobile.ui.MobilePalette
import app.rawline.mobile.ui.OpenConnectAuthDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import moe.matsuri.nb4a.utils.CoreLog
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class LogsAndAuthRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun <T> ui(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }
    private fun await(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(100)
        }
        fail("Android UI did not reach the expected state")
    }
    private fun launch() = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun screenshot(name: String) {
        val file = File(context.filesDir, name)
        requireNotNull(instrumentation.uiAutomation.takeScreenshot()).useBitmap { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) { try { block(this) } finally { recycle() } }

    private class AuthService : ISagerNetService.Stub() {
        @Volatile var request = """{"Session":"fixture-session","Tag":"proxy","Challenge":{"ID":"fixture-request","Banner":"Test VPN","Message":"Choose group and enter code","Error":"","Browser":null,"Form":{"Fields":[{"SubmissionKey":"form:group:0","Name":"group_list","Label":"VPN group","Kind":"select","Value":"one","Options":[{"Value":"one","Label":"Group One"},{"Value":"two","Label":"Group Two"}]},{"SubmissionKey":"form:code:1","Name":"password","Label":"One-time code","Kind":"password","Value":""}]}}}"""
        @Volatile var reply: JSONObject? = null
        override fun openConnectAuth() = request
        override fun replyOpenConnectAuth(session: String, tag: String, challengeId: String, valuesJson: String, cancel: Boolean): String {
            check(session == "fixture-session" && tag == "proxy" && challengeId == "fixture-request")
            check(!cancel)
            reply = JSONObject(valuesJson); request = ""; return ""
        }
        override fun getState() = 0
        override fun getProfileName() = "fixture"
        override fun registerCallback(callback: ISagerNetServiceCallback, id: Int) {}
        override fun unregisterCallback(callback: ISagerNetServiceCallback) {}
        override fun resetTraffic(profileIds: LongArray) {}
        override fun urlTest() = -1
        override fun updateRuleSets() = "{}"
        override fun autoSelectorStatus(withMembers: Boolean) = "{}"
        override fun autoSelectorRecheck() {}
        override fun autoSelectorSelect(memberProfileId: Long) {}
    }

    @Test fun openConnectGroupAndCodeReachTheirOriginalSubmissionKeys() {
        val activity = launch()
        val service = AuthService()
        lateinit var controller: OpenConnectAuthDialog
        var job: Job? = null
        try {
            ui { controller = OpenConnectAuthDialog(activity) { service }; job = controller.start() }
            await { ui { controller.visibleDialog?.isShowing == true } }
            screenshot("openconnect-auth-form.png")
            val dialog = ui { controller.visibleDialog!! }
            val inputs = ui { descendants(dialog.window!!.decorView) }
            val group = inputs.filterIsInstance<MaterialButton>().single { it.text.toString() == "Group One" }
            ui { group.performClick() }
            await { ui { controller.visibleChoices?.isShowing == true } }
            screenshot("openconnect-auth-groups.png")
            ui {
                val list = controller.visibleChoices!!.listView
                list.performItemClick(list.getChildAt(1), 1, list.adapter.getItemId(1))
            }
            val code = inputs.filterIsInstance<EditText>().single()
            ui {
                assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, code.inputType and InputType.TYPE_MASK_VARIATION)
                assertEquals(MobilePalette.accent(activity), code.backgroundTintList!!.defaultColor)
                code.setText("123456")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            await { service.reply != null }
            assertEquals("two", service.reply!!.getString("form:group:0"))
            assertEquals("123456", service.reply!!.getString("form:code:1"))
            await { ui { controller.visibleDialog == null } }
        } finally {
            ui { job?.cancel(); activity.finish() }
        }
    }

    @Test fun liveLogsFollowTheBottomButRespectManualScrolling() {
        val activity = launch()
        val fragment = LogcatFragment()
        try {
            CoreLog.clear()
            for (i in 0 until 200) CoreLog.write("[Info] log-ui-fixture-$i")
            ui { activity.displayFragment(fragment) }
            await { ui { fragment.view != null && fragment.binding.textview.text.contains("log-ui-fixture-199") } }
            await { ui { !fragment.binding.scroolview.canScrollVertically(1) } }
            ui {
                assertFalse(fragment.binding.scroolview.isVerticalScrollBarEnabled)
                fragment.binding.scroolview.scrollTo(0, 0)
            }
            CoreLog.write("[Info] log-ui-manual-scroll")
            await { ui { fragment.binding.textview.text.contains("log-ui-manual-scroll") } }
            ui { assertEquals(0, fragment.binding.scroolview.scrollY) }
            ui { fragment.binding.scroolview.scrollTo(0, Int.MAX_VALUE) }
            CoreLog.write("[Info] log-ui-follow-bottom")
            await { ui { fragment.binding.textview.text.contains("log-ui-follow-bottom") && !fragment.binding.scroolview.canScrollVertically(1) } }
            screenshot("logs-follow-bottom.png")
        } finally { ui { activity.finish() } }
    }
}
