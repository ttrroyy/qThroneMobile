package app.rawline.mobile

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.preference.PreferenceFragmentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.rawline.mobile.database.ProxyGroup
import app.rawline.mobile.database.SagerDatabase
import app.rawline.mobile.ui.GroupSettingsActivity
import app.rawline.mobile.ui.SubscriptionEditorActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** A small, offline regression for the editor's clipped last row. */
@RunWith(AndroidJUnit4::class)
class SubscriptionEditorLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun <T> ui(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }
    private fun await(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(50)
        }
        fail("Subscription editor did not finish layout")
    }

    @Test fun sheetFitsTheWindowAndItsLastPreferenceIsVisibleAfterScrolling() {
        val context = instrumentation.targetContext
        val group = ProxyGroup(name = "Editor layout fixture", url = "https://example.invalid/sub")
        group.id = SagerDatabase.groupDao.insert(group)
        var activity: SubscriptionEditorActivity? = null
        try {
            val launched = instrumentation.startActivitySync(Intent(context, SubscriptionEditorActivity::class.java)
                .putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SubscriptionEditorActivity
            activity = launched
            fun fragment() = launched.supportFragmentManager.findFragmentById(R.id.settings) as? PreferenceFragmentCompat
            await { ui { fragment()?.view != null && fragment()!!.listView.height > 0 &&
                (fragment()!!.listView.adapter?.itemCount ?: 0) > 0 } }
            ui {
                val sheet = launched.findViewById<View>(R.id.settings).parent as View
                val parent = sheet.parent as ViewGroup
                val originalHeight = parent.height
                // Resizing the available window reproduces keyboard/small-window measurement.
                for (height in listOf(originalHeight, originalHeight / 2, originalHeight)) {
                    parent.measure(View.MeasureSpec.makeMeasureSpec(parent.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    parent.layout(0, 0, parent.measuredWidth, height)
                    assertTrue("Sheet starts inside the window", sheet.top >= 0)
                    assertEquals("Sheet must end at the visible window bottom", height, sheet.bottom)
                }
                val list = fragment()!!.listView
                list.scrollToPosition(list.adapter!!.itemCount - 1)
            }
            await { ui {
                val list = fragment()!!.listView
                list.findViewHolderForAdapterPosition(list.adapter!!.itemCount - 1) != null && !list.isLayoutRequested
            } }
            ui {
                val list = fragment()!!.listView
                val last = list.findViewHolderForAdapterPosition(list.adapter!!.itemCount - 1)!!.itemView
                val visible = Rect()
                assertTrue("Last preference must be on screen", last.getGlobalVisibleRect(visible))
                assertEquals("Last preference must not be clipped below the screen", last.height, visible.height())
            }
        } finally {
            activity?.let { ui { it.finish() } }
            SagerDatabase.groupDao.deleteById(group.id)
        }
    }
}
