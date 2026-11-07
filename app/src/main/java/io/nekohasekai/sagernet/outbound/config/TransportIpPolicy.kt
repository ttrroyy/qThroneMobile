package app.rawline.mobile.outbound.config

import app.rawline.mobile.outbound.BuildContext
import app.rawline.mobile.outbound.Outbound

/** qWDTT/CSQTT netstacks have IPv4 routes only. Never change saved global settings. */
object TransportIpPolicy {
    fun ipv4Only(profile: Outbound, profiles: ProfileProvider): Boolean =
        profile.type in listOf("qwdtt","csqtt","mrtt","wdttplus","freeturn") || (profile.type == "chain" &&
            TypeAccess.chainHops(profile).any { profiles.get(it)?.type in listOf("qwdtt","csqtt","mrtt","wdttplus","freeturn") })

    fun settings(base: GeneratorSettings) = base.copy(
        ipv6Enabled = false, remoteDnsDisableIpv6 = true, fakeIpDisableIpv6 = true,
        resolveDomainStrategy = "ipv4_only",
    )
    fun context(base: BuildContext) = base.copy(directDnsDisableIpv6 = true, defaultDomainStrategy = "ipv4_only")
}
