package app.qthrone.mobile

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.qthrone.mobile.database.ProxyEntity
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.database.ProxyGroup
import app.qthrone.mobile.database.SagerDatabase
import app.qthrone.mobile.outbound.types.Socks
import app.qthrone.mobile.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Reproduces the 2,200-server collapsed subscription from the reported ANR without network traffic. */
@RunWith(AndroidJUnit4::class)
class LargeSubscriptionUiRegressionTest {
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
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(100)
        }
        fail("Large subscription did not reach the expected UI state")
    }
    private fun screenshot(name: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    @Test fun collapsedAndExpandedLargeSubscriptionsKeepTheUiBoundedAndResponsive() {
        val previousMode=DataStore.mobileListMode
        DataStore.mobileListMode="list"
        val dashboard = context.getSharedPreferences("dashboard", Context.MODE_PRIVATE)
        val previousCollapsed = dashboard.getStringSet("collapsedGroups", emptySet()).orEmpty().toSet()
        val group = ProxyGroup(name = "Large subscription regression", url = "https://example.invalid/subscription")
        group.id = SagerDatabase.groupDao.insert(group)
        val profiles = (1..2200).map { index ->
            ProxyEntity(groupId = group.id, userOrder = index.toLong()).putOutbound(Socks().apply {
                name = "large-sub-fixture-$index"; server = "192.0.2.1"; serverPort = 1080
            })
        }
        val ids = SagerDatabase.proxyDao.insert(profiles)
        dashboard.edit().putStringSet("collapsedGroups", previousCollapsed + group.id.toString()).commit()
        var activity: MainActivity? = null
        try {
            val launched = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            activity = launched
            fun texts() = descendants(launched.window.decorView).filterIsInstance<TextView>()
            fun fixtureRows() = texts().filter { it.text.toString().startsWith("large-sub-fixture-") }
            await { ui { texts().any { it.text.toString() == group.name } } }
            ui { assertTrue("Collapsed subscriptions must not inflate their server rows", fixtureRows().isEmpty()) }
            screenshot("large-subscription-collapsed.png")
            ui {
                val title = texts().first { it.text.toString() == group.name }
                assertTrue((title.parent.parent as View).performClick())
            }
            await { ui { fixtureRows().isNotEmpty() } }
            ui { assertTrue("Expanded groups must bound the number of inflated server rows", fixtureRows().size <= 101) }
            screenshot("large-subscription-expanded.png")
            ui { texts().single { it.text.toString() == context.getString(R.string.dashboard_next_servers) }.performClick() }
            await { ui { fixtureRows().any { it.text.toString() == "large-sub-fixture-101" } } }
            ui { assertTrue("Changing pages must replace, rather than accumulate server views", fixtureRows().size <= 101) }
            // Each write invalidates Room while the screen is visible, like arriving latency results.
            repeat(40) { index ->
                SagerDatabase.proxyDao.updateLatency(ids[index], index + 20, 1L, null)
                val heartbeat = CountDownLatch(1)
                Handler(Looper.getMainLooper()).post { heartbeat.countDown() }
                assertTrue("Latency refresh blocked Android's main thread", heartbeat.await(4, TimeUnit.SECONDS))
            }
            ui { assertTrue("Latency refresh must keep the rendered page bounded", fixtureRows().size <= 101) }
            assertEquals(2200, SagerDatabase.proxyDao.getByGroup(group.id).size)
        } finally {
            activity?.let { ui { it.finish() } }
            DataStore.mobileListMode=previousMode
            SagerDatabase.groupDao.deleteById(group.id)
            dashboard.edit().putStringSet("collapsedGroups", previousCollapsed).commit()
        }
    }
}
