package app.qthrone.mobile

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.database.GroupRepo
import app.qthrone.mobile.database.ProxyEntity
import app.qthrone.mobile.database.ProxyGroup
import app.qthrone.mobile.database.RouteManager
import app.qthrone.mobile.database.SagerDatabase
import app.qthrone.mobile.outbound.types.Socks
import app.qthrone.mobile.route.ProviderGeoAssets
import app.qthrone.mobile.ui.*
import app.qthrone.mobile.ui.profile.profileSettingsIntent
import app.qthrone.mobile.ui.profile.settingsActivityFor
import app.qthrone.mobile.ui.route.RouteProfileActivity
import app.qthrone.mobile.ui.route.RuleSetPickerActivity
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Photographs one screen per run (`-e screen N`), so that a screen that cannot open costs only its own picture. Fixtures only, nothing connects. */
@RunWith(AndroidJUnit4::class)
class UiTourTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun open(intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Thread.sleep(3500)
    }
    private fun open(activity: Class<out Activity>, build: Intent.() -> Unit = {}) = open(Intent(context, activity).apply(build))
    private fun resumed(): Activity {
        var found: Activity? = null
        instrumentation.runOnMainSync { found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull() }
        return requireNotNull(found)
    }
    private fun main(id: Int) {
        open(MainActivity::class.java)
        instrumentation.runOnMainSync { (resumed() as MainActivity).displayFragmentWithId(id) }
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
    private fun fixtures(): Pair<ProxyGroup, ProxyEntity> {
        val existing = SagerDatabase.groupDao.allGroups().firstOrNull { it.name == "api.host-cloud.uk" }
        val group = existing ?: ProxyGroup(name = "api.host-cloud.uk", url = "https://example.invalid/sub").also { it.id = SagerDatabase.groupDao.insert(it) }
        if (existing == null) SagerDatabase.proxyDao.insert(listOf("🇷🇺 ru-tcp-reality", "🇫🇮 fi-xhttp-tls", "🇩🇪 de-gaming-hy2", "plain-server").mapIndexed { index, name ->
            ProxyEntity(groupId = group.id, userOrder = index.toLong()).putOutbound(Socks().apply { this.name = name; server = "192.0.2.1"; serverPort = 1080 })
        })
        return group to SagerDatabase.proxyDao.getByGroup(group.id).first()
    }
    private fun home(mode: String) {
        val (group, _) = fixtures()
        DataStore.mobileListMode = mode
        GroupRepo.setCurrent(group.id)
        open(MainActivity::class.java)
        ProviderGeoAssets.states.value = mapOf(group.id to ProviderGeoAssets.Status(complete = true))
        Thread.sleep(1000)
    }
    private fun orb(connected: Boolean) {
        home("groups")
        instrumentation.runOnMainSync {
            fun walk(view: android.view.View) {
                (view.background as? MobilePowerArtwork)?.let { it.connected = connected; it.connecting = !connected; it.statusText = if (connected) "Подключено" else "Подключение"; it.elapsedText = "00:12:49" }
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
            walk(resumed().window.decorView)
        }
        Thread.sleep(1600)
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
        "home-sheet" to { home("groups"); val size = context.resources.displayMetrics; instrumentation.uiAutomation.executeShellCommand("input swipe ${size.widthPixels / 2} ${size.heightPixels - 120} ${size.widthPixels / 2} 300 400").close(); Thread.sleep(1500) },
        "home-notice" to { home("groups"); instrumentation.runOnMainSync { MobileTopNotice.show(resumed(), "Подписка «api.host-cloud.uk» успешно обновлена") }; Thread.sleep(600) },
        "home-add" to { home("groups"); tap(R.string.qmobile_add) },
        "home-tests" to { home("groups"); tap(R.string.qmobile_tests) },
        "home-more" to { home("groups"); tap(R.string.qmobile_more) },
        "settings" to { main(R.id.nav_settings) },
        "about" to { main(R.id.nav_about) },
        "groups" to { fixtures(); main(R.id.nav_group) },
        "routes" to { main(R.id.nav_route) },
        "tools" to { main(R.id.nav_tools) },
        "backup" to { main(R.id.nav_settings); tap("Backup and restore") },
        "general" to { main(R.id.nav_settings); tap("General settings") },
        "dns" to { main(R.id.nav_settings); tap("DNS settings") },
        "subscription-settings" to { main(R.id.nav_settings); tap("Subscription settings") },
        "appearance" to { main(R.id.nav_settings); tap("Appearance") },
        "updates" to { main(R.id.nav_settings); tap("Updates") },
        "logs" to { main(R.id.nav_settings); tap("Logs") },
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
    ) + listOf("vless", "trojan", "hysteria", "wireguard", "openvpn", "qwdtt", "csqtt", "wdttplus", "openflux", "chain", "autoselector").map { type ->
        "new-$type" to { fixtures(); open(settingsActivityFor(type)) { putExtra("qmobileProfileType", type); putExtra("qmobileGroupId", fixtures().first.id) } }
    }

    @Test fun photographOneScreen() {
        val arguments = InstrumentationRegistry.getArguments()
        val index = arguments.getString("screen")?.toInt() ?: return
        val (name, go) = screens.getOrNull(index) ?: return
        val style = arguments.getString("style") ?: "classic"
        val night = arguments.getString("night")?.toInt() ?: 1
        DataStore.uiStyle = style
        DataStore.nightTheme = night
        app.qthrone.mobile.utils.Theme.currentNightMode = night
        DataStore.selectedProxy = fixtures().second.id
        go()
        shot("$style$night-%02d-%s".format(index, name))
    }
}
