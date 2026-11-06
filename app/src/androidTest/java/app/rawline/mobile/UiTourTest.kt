package app.rawline.mobile

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.database.GroupRepo
import app.rawline.mobile.database.ProxyEntity
import app.rawline.mobile.database.ProxyGroup
import app.rawline.mobile.database.RouteManager
import app.rawline.mobile.database.SagerDatabase
import app.rawline.mobile.outbound.types.Socks
import app.rawline.mobile.route.ProviderGeoAssets
import app.rawline.mobile.ui.*
import app.rawline.mobile.ui.profile.profileSettingsIntent
import app.rawline.mobile.ui.profile.settingsActivityFor
import app.rawline.mobile.ui.route.RouteProfileActivity
import app.rawline.mobile.ui.route.RuleSetPickerActivity
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Photographs one screen per run (`-e screen N`), so that a screen that cannot open costs only its own picture. Fixtures only, nothing connects. */
@RunWith(AndroidJUnit4::class)
class UiTourTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private var current: Activity? = null
    private fun open(intent: Intent) {
        val monitor = instrumentation.addMonitor(intent.component?.className, null, false)
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        current = instrumentation.waitForMonitorWithTimeout(monitor, 10_000)
        instrumentation.removeMonitor(monitor)
        Thread.sleep(3500)
    }
    private fun open(activity: Class<out Activity>, build: Intent.() -> Unit = {}) = open(Intent(context, activity).apply(build))
    private fun resumed(): Activity = requireNotNull(current)
    private fun main(id: Int) {
        open(MainActivity::class.java)
        instrumentation.runOnMainSync { (resumed() as MainActivity).displayFragmentWithId(id) }
        Thread.sleep(2500)
    }
    private fun screen(fragment: String) {
        open(MainActivity::class.java)
        instrumentation.runOnMainSync { (resumed() as MainActivity).openSettingsScreen(fragment, "") }
        Thread.sleep(2500)
    }
    /** Taps by the label a person sees, in any window, popups included. */
    private fun tap(label: String) {
        val nodes = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label).orEmpty()
        var node: AccessibilityNodeInfo? = nodes.firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label } ?: nodes.firstOrNull()
        while (node != null && !node.isClickable) node = node.parent
        node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(2000)
    }
    private fun tap(string: Int) = tap(context.getString(string))
    private fun topmost(label: String) = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label).orEmpty()
        .minByOrNull { node -> android.graphics.Rect().also(node::getBoundsInScreen).top }
    private fun tapTopmost(label: String) {
        var node = topmost(label)
        while (node != null && !node.isClickable) node = node.parent
        node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(2000)
    }
    /** A finger across the row that shows [label]; fractions of the screen's width. */
    private fun swipe(label: String, from: Float, to: Float) {
        val bounds = android.graphics.Rect().also { topmost(label)?.getBoundsInScreen(it) }
        val width = context.resources.displayMetrics.widthPixels
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "input swipe ${(width * from).toInt()} ${bounds.centerY()} ${(width * to).toInt()} ${bounds.centerY()} 350")).use { it.readBytes() }
        Thread.sleep(1200)
    }
    private fun fixtures(): Pair<ProxyGroup, ProxyEntity> {
        val existing = SagerDatabase.groupDao.allGroups().firstOrNull { it.name == "api.host-cloud.uk" }
        val group = existing ?: ProxyGroup(name = "api.host-cloud.uk", url = "https://example.invalid/sub").also { it.id = SagerDatabase.groupDao.insert(it) }
        if (existing == null) SagerDatabase.proxyDao.insert(listOf("🇷🇺 ru-tcp-reality", "🇫🇮 fi-xhttp-tls", "🇩🇪 de-gaming-hy2", "plain-server").mapIndexed { index, name ->
            ProxyEntity(groupId = group.id, userOrder = index.toLong()).putOutbound(Socks().apply { this.name = name; server = "192.0.2.1"; serverPort = 1080 })
        })
        return group to SagerDatabase.proxyDao.getByGroup(group.id).first()
    }
    private fun home(mode: String, control: String = "button", bottom: Boolean = false, chip: String = "top", tiles: String = "off") {
        val (group, _) = fixtures()
        DataStore.configurationStore.putString("qmobileHomeControl", control)
        DataStore.configurationStore.putString("qmobileControlPosition", if (bottom) "bottom" else "top")
        DataStore.configurationStore.putString("qmobileChipPosition", chip)
        DataStore.configurationStore.putString("qmobileNewTiles", tiles)
        DataStore.mobileListMode = mode
        GroupRepo.setCurrent(group.id)
        open(MainActivity::class.java)
        ProviderGeoAssets.states.value = mapOf(group.id to ProviderGeoAssets.Status(complete = true))
        Thread.sleep(1000)
    }
    private fun orb(connected: Boolean, bottom: Boolean = false) {
        home("groups", bottom = bottom)
        // The home screen rewrites the state every second from the real service, so it is held here until the picture.
        repeat(24) {
            instrumentation.runOnMainSync {
                fun walk(view: android.view.View) {
                    (view.background as? MobilePowerArtwork)?.let { it.connected = connected; it.connecting = !connected; it.statusText = if (connected) "Подключено" else "Подключение"; it.elapsedText = "00:12:49"; it.workersText = "27/27" }
                    // The speed and the check belong to a live connection, which the emulator has none of.
                    if (connected && view is android.widget.TextView) {
                        val label = view.text.toString()
                        if (label.startsWith("↓") || label.startsWith("↑")) (view.parent as android.view.View).visibility = android.view.View.VISIBLE
                        if (label == context.getString(R.string.qmobile_check_connection)) view.visibility = android.view.View.VISIBLE
                    }
                    if (view is android.view.ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
                }
                walk(resumed().window.decorView)
            }
            Thread.sleep(100)
        }
    }
    private fun frameOf(title: Int): android.graphics.Rect {
        val found = android.graphics.Rect()
        instrumentation.runOnMainSync {
            val views = java.util.ArrayList<android.view.View>()
            resumed().window.decorView.findViewsWithText(views, context.getString(title), android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
            views.first().getGlobalVisibleRect(found)
        }
        return found
    }
    private fun touch(action: Int, x: Float, y: Float, downAt: Long) =
        instrumentation.sendPointerSync(android.view.MotionEvent.obtain(downAt, android.os.SystemClock.uptimeMillis(), action, x, y, 0))
    private fun editor(reset: Boolean = true) {
        fixtures()
        if (reset) { MobileHomeLayout.tilesBottom = false; MobileHomeLayout.controlBottom = false; MobileHomeLayout.chipBottom = false; MobileHomeLayout.extendedTiles = false }
        open(MobileLayoutEditorActivity::class.java)
    }
    private fun shot(name: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(context.filesDir, "tour-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) } } finally { bitmap.recycle() }
    }

    private val screens: List<Pair<String, () -> Unit>> = listOf(
        "home-groups" to { home("groups") },
        "home-list" to { home("list") },
        "home-connecting" to { orb(false) },
        "home-connected" to { orb(true) },
        "home-switches" to { home("groups", "switches") },
        "home-bottom" to { home("groups", bottom = true) },
        "home-bottom-connected" to { orb(true, true) },
        "home-bottom-switches" to { home("groups", "switches", true) },
        "home-chip-bottom" to { home("groups", chip = "bottom", tiles = "on") },
        "home-all-bottom" to { home("groups", "switches", true, "bottom") },
        "home-empty" to {
            DataStore.configurationStore.putString("qmobileHomeControl", "button")
            DataStore.configurationStore.putString("qmobileControlPosition", "top")
            DataStore.configurationStore.putString("qmobileChipPosition", "top")
            DataStore.configurationStore.putString("qmobileNewTiles", "off")
            SagerDatabase.groupDao.allGroups().filter { it.name == "api.host-cloud.uk" }.forEach { SagerDatabase.groupDao.deleteById(it.id) }
            open(MainActivity::class.java)
            Thread.sleep(1000)
        },
        "home-sheet" to { home("groups"); tap("ru-tcp-reality"); Thread.sleep(1500) },
        "home-picker" to { home("groups"); tapTopmost("api.host-cloud.uk") },
        "home-menu" to { home("groups"); tap(R.string.qmobile_quick_menu) },
        "home-notice" to { home("groups"); instrumentation.runOnMainSync { MobileTopNotice.show(resumed(), "Подписка «api.host-cloud.uk» успешно обновлена") }; Thread.sleep(600) },
        "home-add" to { home("groups"); tap(R.string.qmobile_add) },
        "home-tests" to { home("groups"); tap(R.string.qmobile_tests) },
        "home-more" to { home("groups"); tap(R.string.qmobile_more) },
        "settings" to { main(R.id.nav_settings) },
        "about" to { main(R.id.nav_about) },
        "groups" to { fixtures(); main(R.id.nav_group) },
        "routes" to { main(R.id.nav_route) },
        "tools" to { main(R.id.nav_tools) },
        "backup" to { main(R.id.nav_settings); tap(R.string.qmobile_backup); tap("Локально") },
        "general" to { main(R.id.nav_settings); tap(R.string.qmobile_general) },
        "dns" to { screen("app.rawline.mobile.ui.settings.DnsSettingsFragment") },
        "subscription-settings" to { screen("app.rawline.mobile.ui.settings.SubscriptionSettingsFragment") },
        "appearance" to { screen("app.rawline.mobile.ui.settings.AppearanceSettingsFragment") },
        "style-menu" to { screen("app.rawline.mobile.ui.settings.AppearanceSettingsFragment"); tap(R.string.qmobile_ui_style) },
        "routing-settings" to { screen("app.rawline.mobile.ui.settings.RoutingSettingsFragment") },
        "logs" to { main(R.id.nav_settings); tap(R.string.qmobile_logs) },
        "vk" to { open(VkRelayActivity::class.java) },
        "vk-hashes" to { open(VkHashesActivity::class.java) },
        "happ-profile" to { open(ProviderRouteActivity::class.java) },
        "provider-routing" to { val (group, _) = fixtures(); open(ProviderRoutingActivity::class.java) { putExtra("groupId", group.id) } },
        "route-profile" to { RouteManager.ensureDefault(); open(RouteProfileActivity::class.java) { putExtra(RouteProfileActivity.EXTRA_PROFILE_ID, RouteManager.current().id) } },
        "rule-sets" to { open(RuleSetPickerActivity::class.java) },
        "apps" to { open(AppListActivity::class.java) },
        "app-manager" to { open(AppManagerActivity::class.java) },
        "group-editor" to { val (group, _) = fixtures(); open(GroupSettingsActivity::class.java) { putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id) } },
        "subscription-editor" to { val (group, _) = fixtures(); open(SubscriptionEditorActivity::class.java) { putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id) } },
        "profile-editor" to { val (_, proxy) = fixtures(); open(proxy.profileSettingsIntent(context, false)) },
        "profile-select" to { fixtures(); open(ProfileSelectActivity::class.java) },
        "webdav" to { open(WebDAVSettingsActivity::class.java) },
        "autoselector" to { open(AutoSelectorStatusActivity::class.java) },
        "deploy" to { open(DeployActivity::class.java) },
        "deploy-qwdtt" to { open(DeployActivity::class.java) { putExtra("kind", "qwdtt"); putExtra("directEditor", true) } },
        "deploy-csqtt" to { open(DeployActivity::class.java) { putExtra("kind", "csqtt"); putExtra("directEditor", true) } },
        "home-sheet-delete" to { home("groups"); tap("ru-tcp-reality"); Thread.sleep(1500); swipe("fi-xhttp-tls", .8f, .3f) },
        "home-sheet-share" to { home("groups"); tap("ru-tcp-reality"); Thread.sleep(1500); swipe("fi-xhttp-tls", .2f, .8f) },
        "picker-share" to { home("groups"); tapTopmost("api.host-cloud.uk"); swipe("api.host-cloud.uk", .2f, .7f); tap(R.string.action_copy) },
        "home-switches" to {
            DataStore.configurationStore.putString("qmobileHomeControl", "switches")
            DataStore.configurationStore.putString("qmobileHomeTilesMode", "standard")
            home("groups")
        },
    ) + listOf("vless", "trojan", "hysteria", "wireguard", "openvpn", "qwdtt", "csqtt", "wdttplus", "openflux", "chain", "autoselector").map { type ->
        "new-$type" to { fixtures(); open(settingsActivityFor(type)) { putExtra("qmobileProfileType", type); putExtra("qmobileGroupId", fixtures().first.id) } }
    } + listOf(
        "layout-editor" to { editor() },
        "layout-editor-gestures" to {
            editor()
            val tiles = frameOf(R.string.qmobile_layout_tiles)
            val x = tiles.exactCenterX(); val y = tiles.exactCenterY()
            // Hold, drag past the list's middle, let go.
            val downAt = android.os.SystemClock.uptimeMillis()
            touch(android.view.MotionEvent.ACTION_DOWN, x, y, downAt)
            Thread.sleep(900)
            val end = context.resources.displayMetrics.heightPixels * .8f
            for (step in 1..12) { touch(android.view.MotionEvent.ACTION_MOVE, x, y + (end - y) * step / 12, downAt); Thread.sleep(30) }
            touch(android.view.MotionEvent.ACTION_UP, x, end, downAt)
            Thread.sleep(600)
            check(MobileHomeLayout.tilesBottom) { "dragging the tiles down did not move them" }
            val moved = frameOf(R.string.qmobile_layout_tiles)
            check(moved.top > y) { "the tiles are still drawn at the top" }
            // Two quick taps.
            repeat(2) {
                val at = android.os.SystemClock.uptimeMillis()
                touch(android.view.MotionEvent.ACTION_DOWN, moved.exactCenterX(), moved.exactCenterY(), at)
                touch(android.view.MotionEvent.ACTION_UP, moved.exactCenterX(), moved.exactCenterY(), at)
                Thread.sleep(90)
            }
            Thread.sleep(700)
            check(MobileHomeLayout.extendedTiles) { "a double tap did not switch the tiles" }
        },
        "home-after-editor" to { open(MainActivity::class.java); Thread.sleep(1000) },
    )

    @Test fun photographOneScreen() {
        val arguments = InstrumentationRegistry.getArguments()
        val index = arguments.getString("screen")?.toInt() ?: return
        val (name, go) = screens.getOrNull(index) ?: return
        val style = arguments.getString("style") ?: "classic"
        val night = arguments.getString("night")?.toInt() ?: 1
        DataStore.uiStyle = style
        DataStore.nightTheme = night
        app.rawline.mobile.utils.Theme.currentNightMode = night
        DataStore.selectedProxy = fixtures().second.id
        go()
        shot("$style$night-%02d-%s".format(index, name))
    }
}
