package app.qthrone.mobile.bg.carrier

import app.qthrone.mobile.outbound.types.Qwdtt
import app.qthrone.mobile.outbound.types.WdttPlus
import app.qthrone.mobile.outbound.types.Csqtt
import app.qthrone.mobile.outbound.OutboundFactory

object NativeHashPolicy {
    /** All three clients can share the qWDTT account binding. CSQTT also accepts a link's own token. */
    fun supportsAutomatic(profile: Qwdtt) = profile.type in listOf("qwdtt", "csqtt", "wdttplus")

    fun applyDefaultSource(profile: Qwdtt, source: String?) {
        if (!supportsAutomatic(profile) || profile.hashSourceChosen) return
        profile.hashSource = if (source == "auto") "auto" else "manual"
        profile.hashSourceChosen = true
    }

    /** A launch-only copy keeps account calls out of stored profiles, exports and subscriptions. */
    fun runtimeProfile(profile: Qwdtt, obtainHashes: (Int) -> List<String>): Qwdtt =
        runtimeProfile(profile, obtainHashes) { VkRelayAccount.tokenForCalls() }

    internal fun runtimeProfile(profile: Qwdtt, obtainHashes: (Int) -> List<String>, obtainToken: () -> String): Qwdtt {
        if (!supportsAutomatic(profile)) return profile
        if(profile is Csqtt && profile.hashSource=="auto_js") {
            if(profile.vkAccessToken.isNotBlank()) return profile
            val copy=Csqtt()
            check(copy.parseFromJson(profile.exportToJson()))
            copy.vkAccessToken=obtainToken()
            check(copy.vkAccessToken.isNotBlank() && copy.vkAccessToken.length<=4096 && copy.vkAccessToken.none { it.isWhitespace() || it.isISOControl() }) {
                "Не удалось получить токен привязанного аккаунта VK"
            }
            return copy
        }
        if (profile.hashSource != "auto") return profile
        val copy = OutboundFactory.newByType(profile.type) as Qwdtt
        check(copy.parseFromJson(profile.exportToJson()))
        val count = (if(profile is Csqtt) (copy.workers+9)/10 else (copy.workers+26)/27)
            .coerceIn(1, if (profile.type == "csqtt") 6 else 4)
        val hashes = obtainHashes(count)
        check(hashes.size == count && hashes.distinct().size == count && hashes.all {
            it.matches(Regex("[A-Za-z0-9_-]{16,512}"))
        }) { "VK не вернул необходимые хеши звонков" }
        copy.hashes = hashes.toList()
        copy.hashSource = "manual"
        return copy
    }

    fun subscriptionIdentity(profile: Qwdtt): String = profile.exportIdentity().also {
        it.remove("hashes")
        it.remove("workers")
    }.toCompact()
    fun defaultDeviceId(hwid: String): String = java.util.UUID.nameUUIDFromBytes(
        ("qThrone-native:"+hwid).toByteArray(Charsets.UTF_8)).toString()
    fun preserve(incoming: Qwdtt, previous: List<Qwdtt>) {
        val sameType = previous.filter { it.type == incoming.type }
        val named = if(incoming.name.isNotBlank()) sameType.filter { it.name == incoming.name } else emptyList()
        val matches = if(named.size == 1) named else sameType.filter {
            it.server == incoming.server && it.serverPort == incoming.serverPort && it.password == incoming.password
        }
        matches.singleOrNull()?.let {
            incoming.deviceId=it.deviceId
            incoming.deviceIdProvided=true
            incoming.hashSource = it.hashSource; incoming.hashSourceChosen = true
            incoming.manualHashesOverride = it.manualHashesOverride
            // Slots the user edited stay as they are; otherwise the subscription owns its leading slots
            // and local trailing slots survive until replaced.
            if (it.hashSource == "manual") incoming.hashes = if (it.manualHashesOverride) it.hashes
                else mergeSlots(incoming.hashes,it.hashes,if(incoming.type=="csqtt") 6 else 4)
            // Everything below is the user's choice, kept like hashes and workers.
            incoming.mode=it.mode
            incoming.turnTcp=it.turnTcp
            incoming.captchaMode=it.captchaMode
            if(incoming.type=="qwdtt") {
                incoming.rawPort=it.rawPort;incoming.obfs=it.obfs;incoming.vkAnonPath=it.vkAnonPath;incoming.goDns=it.goDns
            }
            if(incoming is Csqtt && it is Csqtt) {
                incoming.turnTransport=it.turnTransport;incoming.srtpMode=it.srtpMode;incoming.obfs=it.obfs
                incoming.vkAnonPath=it.vkAnonPath;incoming.fingerprint=it.fingerprint;incoming.clientIds=it.clientIds
                incoming.customTurnIps=it.customTurnIps
                // An updated token in the subscription wins; a locally entered one survives an absent one.
                if(incoming.vkAccessToken.isBlank()) incoming.vkAccessToken=it.vkAccessToken
            }
            if(incoming is WdttPlus && it is WdttPlus) {
                incoming.tcpOnly=it.tcpOnly;incoming.turnSni=it.turnSni;incoming.rtMasque=it.rtMasque
                incoming.rtMasqueServerBootstrap=it.rtMasqueServerBootstrap;incoming.fingerprint=it.fingerprint
                incoming.clientIds=it.clientIds;incoming.vkCallsPreflight=it.vkCallsPreflight
            }
            incoming.workers=when(incoming) {
                is Csqtt -> Csqtt.normalizeWorkers(it.workers)
                is WdttPlus -> WdttPlus.normalizeWorkers(it.workers,incoming.maxWorkers)
                else -> preservedWorkers(it.workers,workerLimit(incoming))
            }
        }
    }
    fun workerLimit(profile:Qwdtt):Int {
        if(profile is Csqtt) return 100
        val slots=4
        val hashes=if(profile.hashSource=="auto") slots else profile.hashes.size.coerceIn(1,slots)
        // WDTT Plus groups 9 workers each up to 108 whatever the hash count.
        if(profile is WdttPlus) return (profile.maxWorkers / 9 * 9).takeIf { it >= 9 }?.coerceAtMost(108) ?: 108
        return (hashes*27).coerceAtMost(108)
    }
    /** The user's count stays; it only drops when the new hash slots no longer allow it. */
    fun preservedWorkers(previous:Int,currentLimit:Int):Int = previous.coerceIn(9,currentLimit)/9*9
    fun mergeSlots(subscription:List<String>, previous:List<String>,limit:Int):List<String> {
        val prefix=subscription.take(limit)
        return (prefix+previous.drop(prefix.size)).distinct().take(limit)
    }
}
