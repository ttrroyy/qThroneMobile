package app.rawline.mobile.ui.profiles

import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.core.view.MenuCompat
import app.rawline.mobile.R
import app.rawline.mobile.bg.test.TestSpec
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.database.GroupSortMethod
import app.rawline.mobile.database.ProxyGroup
import app.rawline.mobile.database.TestBy
import app.rawline.mobile.database.TestShowItems
import app.rawline.mobile.database.TrafficBy
import app.rawline.mobile.database.TypeBy
import app.rawline.mobile.ui.ConfigurationFragment
import app.rawline.mobile.ui.route.RouteQuickSwitch
import app.rawline.mobile.ui.test.TestSessionClient

/** The ⋮ menu of the profiles screen: actions on the current tab's group (the desktop's Groups menu). */
class GroupMenu(private val host: ConfigurationFragment) {

    /** Direction of the next sort, kept for the screen's lifetime (the desktop's proxy_last_order is memory only). */
    var sortDescending = false

    fun setUp(menu: Menu) {
        menu.findItem(R.id.action_misc)?.subMenu?.let { MenuCompat.setGroupDividerEnabled(it, true) }
        keepOpen(menu, R.id.action_sort_ascending) {
            sortDescending = false
            it.isChecked = true
        }
        keepOpen(menu, R.id.action_sort_descending) {
            sortDescending = true
            it.isChecked = true
        }
        keepOpen(menu, R.id.action_show_out_ip) { toggleShown(menu, it) }
        keepOpen(menu, R.id.action_show_speed) { toggleShown(menu, it) }
    }

    /**
     * A toggle that leaves its popup open: MenuBuilder.performItemAction closes the menu after a click unless the
     * item has a collapsible action view whose expansion is refused.
     */
    private fun keepOpen(menu: Menu, id: Int, onClick: (MenuItem) -> Unit) {
        val item = menu.findItem(id) ?: return
        item.actionView = View(host.requireContext())
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
        item.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = false
            override fun onMenuItemActionCollapse(item: MenuItem) = true
        })
        item.setOnMenuItemClickListener {
            onClick(it)
            false
        }
    }

    private fun toggleShown(menu: Menu, item: MenuItem) {
        item.isChecked = !item.isChecked
        val group = host.currentGroup() ?: return
        GroupActions.setShownItems(
            host,
            group.id,
            outIp = menu.findItem(R.id.action_show_out_ip)?.isChecked == true,
            speed = menu.findItem(R.id.action_show_speed)?.isChecked == true,
        )
    }

    /** Before the ⋮ menu opens: what applies to the current group and the running test / service. */
    fun prepare(menu: Menu) {
        val group = host.currentGroup()
        menu.findItem(R.id.action_sort_test_latency)?.isVisible = group?.isSubscription != true
        menu.findItem(R.id.action_update_subscription)?.isVisible = group?.isSubscription == true
        menu.findItem(R.id.action_test_speed_current)?.isEnabled = DataStore.serviceState.connected
        menu.findItem(R.id.action_test_stop)?.isVisible = host.testState.running
        menu.findItem(if (sortDescending) R.id.action_sort_descending else R.id.action_sort_ascending)?.isChecked = true
        if (group != null) {
            menu.findItem(
                when (TestBy.of(group.testSortBy)) {
                    TestBy.LATENCY -> R.id.action_sort_test_latency
                    TestBy.DL_SPEED -> R.id.action_sort_test_download
                    TestBy.UL_SPEED -> R.id.action_sort_test_upload
                    TestBy.IP_OUT -> R.id.action_sort_test_ip_out
                }
            )?.isChecked = true
            menu.findItem(
                when (TrafficBy.of(group.trafficSortBy)) {
                    TrafficBy.TOTAL -> R.id.action_sort_traffic_total
                    TrafficBy.DL -> R.id.action_sort_traffic_downloaded
                    TrafficBy.UL -> R.id.action_sort_traffic_uploaded
                }
            )?.isChecked = true
            val shown = TestShowItems.of(group.testItemsToShow)
            menu.findItem(R.id.action_show_out_ip)?.isChecked = shown.showIp
            menu.findItem(R.id.action_show_speed)?.isChecked = shown.showSpeed
        }
        menu.findItem(if (host.doubleColumn) R.id.action_layout_double else R.id.action_layout_single)?.isChecked = true
    }

    fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_update_all_subscriptions -> GroupActions.updateAll(host)
            R.id.action_test_speed_current -> ProfileTests.startCurrent(host)
            R.id.action_test_stop -> TestSessionClient.stop()
            R.id.action_route_profile -> RouteQuickSwitch.show(host)
            R.id.action_layout_single -> host.setDoubleColumn(false)
            R.id.action_layout_double -> host.setDoubleColumn(true)
            else -> return onGroupItemClick(item, host.currentGroup() ?: return false)
        }
        return true
    }

    private fun onGroupItemClick(item: MenuItem, group: ProxyGroup): Boolean {
        val id = group.id
        when (item.itemId) {
            R.id.action_update_subscription -> GroupActions.updateSubscription(group)
            R.id.action_test_url -> ProfileTests.startGroup(host, TestSpec.KIND_URL, id)
            R.id.action_test_ip -> ProfileTests.startGroup(host, TestSpec.KIND_IP, id)
            R.id.action_test_speed -> ProfileTests.startGroup(host, TestSpec.KIND_SPEED, id)
            R.id.action_clear_test_results -> GroupActions.clearTestResults(host, group)
            R.id.action_remove_duplicates -> GroupActions.removeDuplicates(host, id)
            R.id.action_remove_unavailable -> GroupActions.removeUnavailable(host, id)
            R.id.action_remove_invalid -> GroupActions.removeInvalid(host, group)
            R.id.action_remove_insecure -> GroupActions.removeInsecure(host, id)
            R.id.action_resolve_domains -> GroupActions.resolveDomains(host, group)
            R.id.action_clear_traffic_statistics -> GroupActions.clearTraffic(host, group)

            // the Type column's "Sort By: Type / Security" stores type_sort_by
            R.id.action_sort_type -> sort(id, GroupSortMethod.BY_TYPE) { it.typeSortBy = TypeBy.BY_TYPE.value }
            R.id.action_sort_security -> sort(id, GroupSortMethod.BY_SECURITY) {
                it.typeSortBy = TypeBy.BY_SECURITY.value
            }

            R.id.action_sort_address -> sort(id, GroupSortMethod.BY_ADDRESS)
            R.id.action_sort_name -> sort(id, GroupSortMethod.BY_NAME)
            R.id.action_sort_test_latency -> if (!group.isSubscription) sortByTest(id, TestBy.LATENCY)
            R.id.action_sort_test_download -> sortByTest(id, TestBy.DL_SPEED)
            R.id.action_sort_test_upload -> sortByTest(id, TestBy.UL_SPEED)
            R.id.action_sort_test_ip_out -> sortByTest(id, TestBy.IP_OUT)
            R.id.action_sort_traffic_total -> sortByTraffic(id, TrafficBy.TOTAL)
            R.id.action_sort_traffic_downloaded -> sortByTraffic(id, TrafficBy.DL)
            R.id.action_sort_traffic_uploaded -> sortByTraffic(id, TrafficBy.UL)
            else -> return false
        }
        return true
    }

    private fun sort(groupId: Long, method: GroupSortMethod, save: ((ProxyGroup) -> Unit)? = null) {
        GroupActions.sort(host, groupId, method, sortDescending, save)
    }

    private fun sortByTest(groupId: Long, by: TestBy) {
        sort(groupId, GroupSortMethod.BY_TEST_RESULT) { it.testSortBy = by.value }
    }

    private fun sortByTraffic(groupId: Long, by: TrafficBy) {
        sort(groupId, GroupSortMethod.BY_TRAFFIC) { it.trafficSortBy = by.value }
    }
}
