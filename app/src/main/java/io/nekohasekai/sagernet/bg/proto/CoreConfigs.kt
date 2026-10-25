package app.qthrone.mobile.bg.proto

import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.database.ProxyEntity
import app.qthrone.mobile.database.ProxyGroup
import app.qthrone.mobile.database.RouteManager
import app.qthrone.mobile.database.SagerDatabase
import app.qthrone.mobile.database.SettingsMapper
import app.qthrone.mobile.outbound.Outbound
import app.qthrone.mobile.outbound.config.ConfigGenerator
import app.qthrone.mobile.outbound.config.GeneratedConfig
import app.qthrone.mobile.outbound.config.GeneratorSettings
import app.qthrone.mobile.outbound.config.ProfileProvider
import app.qthrone.mobile.outbound.config.RoutingInput
import app.qthrone.mobile.outbound.config.TestCandidate
import app.qthrone.mobile.route.RouteDns

/**
 * The config generator wired to the app: profiles come from the profile table, the settings and the build-time
 * globals from DataStore through [SettingsMapper], the current route profile and the rule-set list from
 * RouteManager (main configs only; test configs read only the profile's own DNS), the landing / front proxy from the
 * profile's group.
 */
object CoreConfigs {

    /** Stored profiles by id, each row parsed once per generation. */
    private class DatabaseProfiles : ProfileProvider {
        private val cache = HashMap<Long, Outbound?>()
        override fun get(id: Long): Outbound? = cache.getOrPut(id) { SagerDatabase.proxyDao.getById(id)?.outbound }
    }

    /** [dns]: the routing profile's own DNS, null for the global one; [settings] must be mapped with the same. */
    fun generator(
        routing: RoutingInput = RoutingInput.DEFAULT,
        settings: GeneratorSettings = SettingsMapper.generatorSettings(),
        dns: RouteDns? = null,
    ): ConfigGenerator = ConfigGenerator(DatabaseProfiles(), settings, SettingsMapper.buildContext(dns), routing)

    /**
     * The main config of [profile]; throws with the generator's message when it cannot be built. [deferRuleSets]
     * is GeneratorSettings.deferRuleSets, for a start the user let go without its rule-sets.
     */
    fun buildMain(profile: ProxyEntity, deferRuleSets: Boolean = false): GeneratedConfig {
        val (landing, front) = groupProxies(profile.groupId)
        val options = SagerDatabase.groupDao.getById(profile.groupId)?.subOptions
        val bound = options?.takeIf { it.routingProvider.isNotBlank() && it.providerRoutingEnabled }
        val route = when {
            bound?.providerRouting == "off" -> app.qthrone.mobile.route.RouteProfile.defaultProfile()
            bound != null -> app.qthrone.mobile.route.ProviderRouting.profile(
                app.qthrone.mobile.outbound.json.JsonInput.parseObjectOrNull(bound.providerRouting) ?: app.qthrone.mobile.outbound.json.JsonObject(), bound.routingProvider)
            else -> app.qthrone.mobile.database.RouteManager.current().copy()
        }
        // Provider routing below still wins over the routing profile's own DNS, which wins over Settings > DNS.
        val dns = route.activeDns()
        var settings = SettingsMapper.generatorSettings(dns).copy(deferRuleSets = deferRuleSets)
        var routing = SettingsMapper.routingInput(route)
        val fullJson = profile.outbound.isXrayFullConfig()
        val providerRouting=app.qthrone.mobile.route.ProviderRouting.stored(route)
        providerRouting?.let { (_, document) ->
            if (fullJson) {
                val custom = profile.outbound as? app.qthrone.mobile.outbound.types.Custom
                if (custom != null) app.qthrone.mobile.route.ProviderGeoAssets.installXray(document,app.qthrone.mobile.bg.XrayGeoAssets.needed(listOf(custom.config)))
                val tunnel = app.qthrone.mobile.route.ProviderRouting.settings(document,settings)
                settings = settings.copy(remoteDns = tunnel.remoteDns, dnsPredefinedRules = tunnel.dnsPredefinedRules, fakeDns = false)
                routing = SettingsMapper.routingInput(app.qthrone.mobile.route.RouteProfile.defaultProfile())
            } else {
                settings = app.qthrone.mobile.route.ProviderRouting.settings(document, settings)
                val catalog = app.qthrone.mobile.route.ProviderGeoAssets.resolve(route, document, routing.catalog)
                routing = RoutingInput(route, catalog, routing.selectors, routing.memberCheck)
            }
        }
        val profiles = DatabaseProfiles()
        var context = SettingsMapper.buildContext(dns)
        if (app.qthrone.mobile.outbound.config.TransportIpPolicy.ipv4Only(profile.outbound, profiles)) {
            settings = app.qthrone.mobile.outbound.config.TransportIpPolicy.settings(settings)
            context = app.qthrone.mobile.outbound.config.TransportIpPolicy.context(context)
            moe.matsuri.nb4a.utils.CoreLog.write("[Info] ${profile.outbound.displayType()} uses IPv4-only tunnel and DNS; saved IPv6 preference is unchanged")
        }
        moe.matsuri.nb4a.utils.CoreLog.write("[Info] "+DnsDiagnostics.summary(settings,providerRouting!=null))
        val generated = ConfigGenerator(profiles, settings, context, routing).build(profile.id, landing, front)
        if (!generated.ok) error(generated.error ?: "config generation failed")
        return generated
    }

    /** One test config for every candidate at once, each under its own group's landing / front proxy. */
    fun buildTest(profileIds: List<Long>): GeneratedConfig {
        val groups = HashMap<Long, Pair<Long, Long>>()
        val candidates = profileIds.map { id ->
            val groupId = SagerDatabase.proxyDao.getById(id)?.groupId ?: -1L
            val (landing, front) = groups.getOrPut(groupId) { groupProxies(groupId) }
            TestCandidate(id, landing, front)
        }
        val dns = RouteManager.customDns(DataStore.currentRouteId)
        return generator(settings = SettingsMapper.generatorSettings(dns), dns = dns).buildTest(candidates)
    }

    /** (landing_proxy_id, front_proxy_id) of a group, -1 when unset (any id <= 0 is "none"). */
    private fun groupProxies(groupId: Long): Pair<Long, Long> {
        val group: ProxyGroup = (if (groupId > 0) SagerDatabase.groupDao.getById(groupId) else null) ?: return -1L to -1L
        val landing = group.landingProxyId.takeIf { it > 0 } ?: -1L
        val front = group.frontProxyId.takeIf { it > 0 } ?: -1L
        return landing to front
    }
}
