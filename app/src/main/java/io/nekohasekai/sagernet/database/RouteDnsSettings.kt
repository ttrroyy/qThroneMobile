package app.qthrone.mobile.database

import app.qthrone.mobile.route.RouteDns

/** RouteDns::Global and RouteDns::Current (RouteProfile.cpp): the DNS settings a config is generated with. */
object RouteDnsSettings {

    /** RouteDns::Global: the DNS settings of Settings › DNS. */
    fun global(): RouteDns = RouteDns(
        remoteDns = DataStore.remoteDns,
        remoteDnsDisableIpv6 = DataStore.remoteDnsDisableIpv6,
        directDns = DataStore.directDns,
        directDnsDisableIpv6 = DataStore.directDnsDisableIpv6,
        dnsFinalOut = DataStore.dnsFinalOut,
        enableDnsRouting = DataStore.enableDnsRouting,
        fakeDns = DataStore.fakedns,
        fakeIpDisableIpv6 = DataStore.fakeipDisableIpv6,
    )

    /**
     * RouteDns::Current: the own DNS of the profile current_route_id names, else [global], paired with whether the
     * profile owns it (routeOwnsDns). Reads the route table, so call it off the main thread.
     */
    fun current(): Pair<RouteDns, Boolean> {
        val custom = RouteManager.customDns(DataStore.currentRouteId)
        return (custom ?: global()) to (custom != null)
    }
}
