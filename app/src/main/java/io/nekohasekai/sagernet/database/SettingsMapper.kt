package app.qthrone.mobile.database

import app.qthrone.mobile.Key
import app.qthrone.mobile.SagerNet
import app.qthrone.mobile.outbound.BuildContext
import app.qthrone.mobile.outbound.Outbound
import app.qthrone.mobile.outbound.OutboundFactory
import app.qthrone.mobile.outbound.config.GeneratorSettings
import app.qthrone.mobile.outbound.config.MemberCheck
import app.qthrone.mobile.outbound.config.RoutingInput
import app.qthrone.mobile.outbound.config.SelectorGroup
import app.qthrone.mobile.outbound.config.SelectorMember
import app.qthrone.mobile.outbound.config.SelectorStore
import app.qthrone.mobile.outbound.json.JsonArray
import app.qthrone.mobile.outbound.json.jsonObjectOf
import app.qthrone.mobile.outbound.types.AutoSelector
import app.qthrone.mobile.outbound.types.Custom
import app.qthrone.mobile.route.RouteDns
import app.qthrone.core.mobile.Mobile
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * DataStore -> the config generator's settings and build-time globals, and RouteManager / the profile tables -> its
 * routing input. The UI process (config export) and the :bg process (running instances, URL / speed tests) map them
 * identically, except that only :bg, where the core is loaded, lets the core check auto-selector members.
 * Every desktop key maps onto the field that names it; the Android-only inputs are the service mode, the per-app
 * lists and the system HTTP proxy switch and bypass list.
 */
object SettingsMapper {

    /**
     * The route profile of current_route_id (the first profile when it is gone), the rule-set list, the tracked
     * groups of auto-selectors and, in :bg, the core's member check.
     */
    fun routingInput(profile: app.qthrone.mobile.route.RouteProfile = RouteManager.current()): RoutingInput = RoutingInput(
        profile,
        RouteManager.catalog(),
        selectorStore(),
        if (SagerNet.application.isBgProcess) CoreMemberCheck else null,
    )

    /** GroupsRepo / ProfilesRepo for the auto-selector plan; each group is read once per store. */
    fun selectorStore(): SelectorStore = DatabaseSelectorStore()

    /** The outbound layer's lookups into the database (autoselector.cpp:9-15 DisplayAddress); once per process. */
    fun installOutboundHooks() {
        AutoSelector.groupNames = AutoSelector.GroupNames { gid -> SagerDatabase.groupDao.getById(gid)?.displayName() }
    }

    /** The DNS fields come from [dns], the routing profile's own DNS (RouteDns::Current), when given. */
    fun generatorSettings(dns: RouteDns? = null): GeneratorSettings {
        val mixedEnabled = !DataStore.mixedInboundDisabled
        val clashApi = DataStore.coreBoxClashApi
        val routeDns = dns ?: RouteDnsSettings.global()
        return GeneratorSettings(
            logLevel = DataStore.logLevel,
            ntpEnabled = DataStore.enableNtp,
            ntpServer = DataStore.ntpServerAddress,
            ntpServerPort = DataStore.ntpServerPort,
            ntpInterval = DataStore.ntpInterval,
            ntpOutbound = DataStore.ntpOutbound,
            useMozillaCerts = DataStore.useMozillaCerts,
            dnsInPort = DataStore.coreDnsInPort,
            mixedInboundEnabled = mixedEnabled,
            inboundAddress = DataStore.inboundAddress.ifBlank { SettingsRegistry.LOOPBACK_ADDRESS },
            mixedPort = DataStore.inboundSocksPort,
            httpPort = DataStore.inboundHttpPort,
            mixedAuth = DataStore.inboundAuth,
            mixedUsername = DataStore.inboundUser,
            mixedPassword = DataStore.inboundPass,
            customInboundJson = DataStore.customInbound,
            vpnMode = DataStore.serviceMode == Key.MODE_VPN,
            tunMtu = DataStore.vpnMtu,
            // strict_route is fixed on Android (decision D7).
            tunStrictRoute = true,
            tunIPv4Cidr = DataStore.vpnTunIpv4Cidr,
            tunIPv6Cidr = DataStore.vpnTunIpv6Cidr,
            ipv6Enabled = DataStore.vpnIpv6,
            bypassLan = !DataStore.disablePrivateRangeBypass,
            lanSharingEnabled = DataStore.lanSharingEnabled,
            privateRanges = DataStore.vpnPrivateRanges,
            localLanCidrs = localLanCidrs(),
            perAppEnabled = DataStore.proxyApps,
            perAppBypass = DataStore.bypass,
            perAppPackages = GeneratorSettings.splitLines(DataStore.individual),
            perAppAllowedUids = if(DataStore.proxyApps) app.qthrone.mobile.outbound.config.VpnAppGuard.allowedUids(
                app.qthrone.mobile.utils.PackageCache.snapshot().uidMap,
                GeneratorSettings.splitLines(DataStore.individual).toSet(),DataStore.bypass) else emptyList(),
            httpProxyEnabled = DataStore.appendHttpProxy,
            httpProxyBypassDomains = GeneratorSettings.splitLines(DataStore.httpProxyBypass),
            remoteDns = routeDns.remoteDns,
            remoteDnsDisableIpv6 = routeDns.remoteDnsDisableIpv6,
            directDns = routeDns.directDns,
            underlyingDns = DataStore.coreBoxUnderlyingDns,
            dnsCacheCapacity = DataStore.dnsCacheCapacity,
            dnsDisableCache = DataStore.dnsDisableCache,
            dnsDisableExpire = DataStore.dnsDisableExpire,
            dnsPersistCache = DataStore.dnsPersistCache,
            dnsReverseMapping = DataStore.dnsReverseMapping,
            dnsObject = DataStore.dnsObject,
            dnsFinalOut = routeDns.dnsFinalOut,
            dnsOptimistic = DataStore.dnsOptimistic,
            dnsOptimisticTimeout = DataStore.dnsOptimisticTimeout,
            dnsQueryTimeout = DataStore.dnsQueryTimeout,
            dnsUseHosts = DataStore.dnsUseHosts,
            dnsPredefinedEnable = DataStore.dnsPredefinedEnable,
            dnsPredefinedRules = DataStore.dnsPredefinedRules,
            fakeDns = routeDns.fakeDns,
            fakeIpDisableIpv6 = routeDns.fakeIpDisableIpv6,
            resolveDomainStrategy = DataStore.domainStrategy,
            rulesetMirror = DataStore.rulesetMirror,
            adblockEnable = DataStore.adblockEnable,
            enableDnsRouting = routeDns.enableDnsRouting,
            enableTunRouting = DataStore.enableTunRouting,
            blockQuic = DataStore.blockQuic,
            trafficStats = DataStore.enableStats,
            apiPort = DataStore.coreBoxApiPort,
            apiSecret = DataStore.coreBoxApiSecret,
            clashApiEnabled = clashApi > 0,
            clashApiListen = DataStore.coreBoxClashListenAddr,
            clashApiPort = if (clashApi > 0) clashApi else -clashApi,
            clashApiSecret = DataStore.coreBoxClashApiSecret,
            xrayLogLevel = DataStore.xrayLogLevel,
            testUrl = DataStore.testUrl,
            directTestUrl = DataStore.directTestUrl,
            enableWarp = DataStore.enableWarp,
            warpMode = DataStore.warpMode,
            warpEp = DataStore.warpEp,
            warpPrivateKey = DataStore.warpPrivateKey,
            warpPublicKey = DataStore.warpPublicKey,
            warpIfcAddrs = DataStore.warpIfcAddrs,
            warpReserved = DataStore.warpReserved,
            warpMasqueEp = DataStore.warpMasqueEp,
            warpMasquePrivateKey = DataStore.warpMasquePrivateKey,
            warpMasquePeerPublicKey = DataStore.warpMasquePeerPublicKey,
            warpMasqueIfcAddrs = DataStore.warpMasqueIfcAddrs,
            warpMasqueSni = DataStore.warpMasqueSni,
            warpMasqueHttpMode = DataStore.warpMasqueHttpMode,
        )
    }

    /**
     * DataStore -> BuildContext: the preset globals and the domain strategies, with direct_dns_disable_ipv6 from [dns],
     * the routing profile's own DNS, when given. TLS spoof is never mapped (decision D8).
     */
    fun buildContext(dns: RouteDns? = null): BuildContext = BuildContext(
        skipCert = DataStore.skipCert,
        utlsFingerprint = DataStore.utlsFingerprint,
        muxDefaultOn = DataStore.muxDefaultOn,
        muxProtocol = DataStore.muxProtocol,
        muxConcurrency = DataStore.muxConcurrency,
        muxPadding = DataStore.muxPadding,
        muxMode = DataStore.muxMode,
        muxMaxConnections = DataStore.muxMaxConnections,
        muxMinStreams = DataStore.muxMinStreams,
        muxBrutal = DataStore.muxBrutal,
        muxBrutalUpMbps = DataStore.muxBrutalUpMbps,
        muxBrutalDownMbps = DataStore.muxBrutalDownMbps,
        fragmentImplementation = DataStore.fragmentImplementation,
        fragmentDefaultOn = DataStore.fragmentDefaultOn,
        fragmentSize = DataStore.fragmentSize.ifBlank { SettingsRegistry.FRAGMENT_SIZE.default },
        fragmentSleep = DataStore.fragmentSleep.ifBlank { SettingsRegistry.FRAGMENT_SLEEP.default },
        tlsTricksDefaultOn = DataStore.tlsTricksDefaultOn,
        h2IdleTimeout = DataStore.h2IdleTimeout,
        h2KeepAlivePeriod = DataStore.h2KeepAlivePeriod,
        h2StreamReceiveWindow = DataStore.h2StreamReceiveWindow,
        h2ConnectionReceiveWindow = DataStore.h2ConnectionReceiveWindow,
        h2MaxConcurrentStreams = DataStore.h2MaxConcurrentStreams,
        quicInitialPacketSize = DataStore.quicInitialPacketSize,
        quicDisablePathMtuDiscovery = DataStore.quicDisablePathMtuDiscovery,
        xrayMuxConcurrency = DataStore.xrayMuxConcurrency,
        xrayMuxDefaultOn = DataStore.xrayMuxDefaultOn,
        defaultDomainStrategy = DataStore.outboundDomainStrategy,
        directDnsDisableIpv6 = dns?.directDnsDisableIpv6 ?: DataStore.directDnsDisableIpv6,
        useDnsObject = DataStore.useDnsObject,
        xrayUseSystemCA = DataStore.xrayUseSystemCa,
        routeOwnsDns = dns != null,
    )

    /** xray_vless_preference for link and subscription imports (utils.cpp:50-70). */
    fun xrayVlessPreference(): OutboundFactory.XrayVlessPreference =
        OutboundFactory.XrayVlessPreference.entries.getOrNull(DataStore.xrayVlessPreference)
            ?: OutboundFactory.DEFAULT_XRAY_VLESS_PREFERENCE

    private class DatabaseSelectorStore : SelectorStore {
        private val groups = HashMap<Long, SelectorGroup?>()

        override fun group(gid: Long): SelectorGroup? {
            if (groups.containsKey(gid)) return groups[gid]
            return load(gid).also { groups[gid] = it }
        }

        private fun load(gid: Long): SelectorGroup? {
            val group = (if (gid > 0) SagerDatabase.groupDao.getById(gid) else null) ?: return null
            val members = SagerDatabase.proxyDao.getByGroup(gid).map {
                SelectorMember(it.id, it.outbound, it.latency, it.latencyAt, it.testCountry.orEmpty())
            }
            val landing = group.landingProxyId.takeIf { it > 0 } ?: -1L
            val front = group.frontProxyId.takeIf { it > 0 } ?: -1L
            fun outbound(id: Long): Outbound? = if (id > 0) SagerDatabase.proxyDao.getById(id)?.outbound else null
            return SelectorGroup(group.id, group.name, landing, front, outbound(landing), outbound(front), members)
        }
    }

    /**
     * IsValid (generate.cpp:2466-2525) on the loaded core, ten members at a time like invalidProfileIDs: a sing-box
     * member alone in `outbounds` / `endpoints` with the log level, an Xray one alone in an Xray config, a custom Xray
     * full config without its inbounds (a missing geo asset is left to fail at start, where it can be named).
     */
    private object CoreMemberCheck : MemberCheck {
        override fun rejected(members: List<Pair<Long, Outbound>>, ctx: BuildContext): Map<Long, String> {
            if (members.isEmpty()) return emptyMap()
            val logLevel = DataStore.logLevel
            val pool = Executors.newFixedThreadPool(minOf(10, members.size))
            try {
                val verdicts = members.map { (_, outbound) -> pool.submit(Callable { coreError(outbound, ctx, logLevel) }) }
                val rejected = LinkedHashMap<Long, String>()
                for ((index, verdict) in verdicts.withIndex()) verdict.get()?.let { rejected[members[index].first] = it }
                return rejected
            } finally {
                pool.shutdown()
            }
        }

        private fun coreError(outbound: Outbound, ctx: BuildContext, logLevel: String): String? = try {
            when {
                outbound.isXrayFullConfig() -> {
                    val config = (outbound as Custom).configObject()
                    config.remove("inbounds")
                    try {
                        Mobile.checkXrayConfig(config.toCompact())
                        null
                    } catch (e: Exception) {
                        val message = e.message.orEmpty()
                        if (message.contains("geoip.dat") || message.contains("geosite.dat")) null else message
                    }
                }

                outbound.isXray() -> {
                    val built = outbound.buildXray(ctx)
                    if (!built.ok) built.error else {
                        Mobile.checkXrayConfig(jsonObjectOf("outbounds" to JsonArray.of(built.json)).toCompact())
                        null
                    }
                }

                else -> {
                    val key = if (outbound.isEndpoint()) "endpoints" else "outbounds"
                    val config = jsonObjectOf(key to JsonArray.of(outbound.build(ctx).json), "log" to jsonObjectOf("level" to logLevel))
                    Mobile.checkConfig(config.toCompact())
                    null
                }
            }
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }

    /** "192.168.1.0/24" for each IPv4 address of the underlying network. */
    private fun localLanCidrs(): List<String> = runCatching {
        val network = app.qthrone.mobile.SagerNet.underlyingNetwork ?: return emptyList()
        app.qthrone.mobile.SagerNet.connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
            .filter { it.address is java.net.Inet4Address && it.prefixLength in 8..32 }
            .map { link ->
                val bits = java.nio.ByteBuffer.wrap(link.address.address).int and (-1 shl (32 - link.prefixLength))
                java.net.InetAddress.getByAddress(java.nio.ByteBuffer.allocate(4).putInt(bits).array()).hostAddress + "/" + link.prefixLength
            }
    }.getOrDefault(emptyList())
}
