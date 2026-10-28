package app.qthrone.mobile

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.database.ProxyEntity
import app.qthrone.mobile.database.ProxyGroup
import app.qthrone.mobile.database.SagerDatabase
import app.qthrone.mobile.outbound.types.Socks
import app.qthrone.mobile.ui.ImportTargetActivity
import app.qthrone.mobile.ui.MainActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MobileImportUiRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun <T> ui(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (check()) return
            Thread.sleep(50)
        }
        fail("Dashboard did not finish layout")
    }

    @Test fun cancellingDestinationDoesNotCreateOrImportAnything() {
        val groups = SagerDatabase.groupDao.allGroups().map { it.id }
        val profiles = SagerDatabase.proxyDao.getAll().map { it.id }
        val name = "${UUID.randomUUID()}.json"
        val payload = File(context.cacheDir, "import-target/$name").apply {
            parentFile!!.mkdirs()
            writeText(JSONObject().put("kind", "text").put("content", "socks://192.0.2.1:1080#cancelled").toString())
        }
        val activity = instrumentation.startActivitySync(Intent(context, ImportTargetActivity::class.java)
            .putExtra("payload", name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ImportTargetActivity
        try {
            ui {
                val texts = descendants(activity.window.decorView).filterIsInstance<TextView>()
                assertTrue(texts.any { it.text == context.getString(R.string.qmobile_new_group) })
                assertTrue(texts.any { it.text == context.getString(R.string.qmobile_existing_group) })
                activity.finish()
            }
            instrumentation.waitForIdleSync()
            assertEquals(groups, SagerDatabase.groupDao.allGroups().map { it.id })
            assertEquals(profiles, SagerDatabase.proxyDao.getAll().map { it.id })
            await { !payload.exists() }
        } finally { ui { activity.finish() }; payload.delete() }
    }

    @Test fun confirmedFileImportOutlivesTheDestinationScreen() {
        val group = ProxyGroup(name = "Confirmed file destination")
        group.id = SagerDatabase.groupDao.insert(group)
        val source = File(context.cacheDir, "${UUID.randomUUID()}.txt").apply { writeText("socks://192.0.2.1:1080#confirmed-file") }
        val payload = File(context.cacheDir, "import-target/${UUID.randomUUID()}.json").apply {
            parentFile!!.mkdirs()
            writeText(JSONObject().put("kind", "file").put("content", Uri.fromFile(source).toString()).put("groupId", group.id).toString())
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {
            ui { ImportTargetActivity.consume(activity, payload.name) }
            await { SagerDatabase.proxyDao.getByGroup(group.id).size == 1 }
            assertEquals("confirmed-file", SagerDatabase.proxyDao.getByGroup(group.id).single().displayName())
            assertFalse(payload.exists())
        } finally {
            ui { activity.finish() }
            SagerDatabase.groupDao.deleteById(group.id)
            source.delete(); payload.delete()
        }
    }

    @Test fun bottomServerReceivesWholeRowTouchWithLegacyControlsHidden() {
        val dashboard = context.getSharedPreferences("dashboard", Context.MODE_PRIVATE)
        val collapsed = dashboard.getStringSet("collapsedGroups", emptySet()).orEmpty().toSet()
        val selected = DataStore.selectedProxy
        val group = ProxyGroup(name = "Bottom row regression")
        group.id = SagerDatabase.groupDao.insert(group)
        val rows = (1..7).map { index -> ProxyEntity(groupId = group.id, userOrder = index.toLong()).putOutbound(Socks().apply {
            name = "bottom-fixture-$index"; server = "192.0.2.1"; serverPort = 1080
        }) }
        val ids = SagerDatabase.proxyDao.insert(rows)
        dashboard.edit().putStringSet("collapsedGroups", SagerDatabase.groupDao.allGroups().filter { it.id != group.id }.map { it.id.toString() }.toSet()).commit()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {
            fun last() = descendants(activity.window.decorView).filterIsInstance<TextView>().firstOrNull { it.text == "bottom-fixture-7" }
            await { ui { last() != null } }
            ui {
                assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.stats).visibility)
                var parent = last()!!.parent
                while (parent !is ScrollView) parent = parent.parent
                parent.fullScroll(View.FOCUS_DOWN)
            }
            instrumentation.waitForIdleSync()
            val bounds = Rect()
            await { ui { (last()!!.parent.parent as View).getGlobalVisibleRect(bounds) && bounds.height() > 20 } }
            val x = (bounds.right - 64 * context.resources.displayMetrics.density).coerceAtLeast(bounds.left.toFloat() + 1)
            val y = bounds.centerY().toFloat()
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            }
            await { DataStore.selectedProxy == ids.last() }
        } finally {
            ui { activity.finish() }
            DataStore.selectedProxy = selected
            SagerDatabase.groupDao.deleteById(group.id)
            dashboard.edit().putStringSet("collapsedGroups", collapsed).commit()
        }
    }
}
