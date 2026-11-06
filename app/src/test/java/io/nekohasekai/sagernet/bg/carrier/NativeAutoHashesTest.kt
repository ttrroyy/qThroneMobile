package app.rawline.mobile.bg.carrier

import app.rawline.mobile.outbound.OutboundFactory
import app.rawline.mobile.outbound.types.Qwdtt
import app.rawline.mobile.outbound.types.WdttPlus
import org.junit.Assert.*
import org.junit.Test

class NativeAutoHashesTest {
    @Test fun autoJsCanBeSavedWithoutATokenAndGetsItFromTheSharedBindingOnlyAtLaunch() {
        val saved=app.rawline.mobile.outbound.types.Csqtt().apply {
            server="192.0.2.1"; password="fixture-password";hashSource="auto_js";vkAnonPath="auto_js"
        }
        saved.privateConfig(1080,1081,CarrierBridgeCredentials("fixture","fixture"))
        val before=saved.exportToJson().toCompact()
        var requests=0
        val runtime=NativeHashPolicy.runtimeProfile(saved,{ error("Auto JS must not create API hashes") }) {
            requests++; "vk1.runtime_fixture_token"
        } as app.rawline.mobile.outbound.types.Csqtt
        assertEquals(1,requests)
        assertEquals("vk1.runtime_fixture_token",runtime.vkAccessToken)
        assertEquals("auto_js",runtime.hashSource)
        assertEquals(before,saved.exportToJson().toCompact())
        assertFalse(saved.exportToLink().contains("runtime_fixture"))
        assertEquals(runtime.vkAccessToken,runtime.privateConfig(1080,1081,CarrierBridgeCredentials("fixture","fixture")).string("vk_access_token"))
    }

    @Test fun tokenLinkKeepsItsOwnAccountAndFailedLookupDoesNotMutateTheProfile() {
        val saved=app.rawline.mobile.outbound.types.Csqtt().apply {
            server="192.0.2.1";password="fixture";hashSource="auto_js";vkAnonPath="auto_js";vkAccessToken="vk1.link_fixture"
        }
        assertSame(saved,NativeHashPolicy.runtimeProfile(saved,{ error("Unexpected API call") },{ error("A link's own token takes priority") }))
        saved.vkAccessToken=""
        val before=saved.exportToJson().toCompact()
        try {
            NativeHashPolicy.runtimeProfile(saved,{ error("Unexpected API call") },{ "" })
            fail("An empty account token must stop startup")
        } catch(_:IllegalStateException) {}
        assertEquals(before,saved.exportToJson().toCompact())
    }

    @Test fun explicitAutoApiCannotBeChangedBackToAutoJsByASubscriptionsOldAuthPath() {
        val saved=app.rawline.mobile.outbound.types.Csqtt().apply {
            server="192.0.2.1";password="fixture";hashSource="auto";vkAnonPath="auto_js";vkAccessToken="vk1.provider_fixture"
        }
        val before=saved.exportToJson().toCompact()
        val runtime=NativeHashPolicy.runtimeProfile(saved,::accountHashes,{ error("Auto API must use shared account hashes") })
        assertEquals("manual",runtime.hashSource)
        assertEquals("vkcalls",runtime.vkAnonPath)
        assertEquals(accountHashes(1),runtime.hashes)
        assertEquals(before,saved.exportToJson().toCompact())
    }

    private val transports = listOf("qwdtt", "csqtt", "wdttplus")
    private val manualHash = "manual_call_hash_123456789"
    private fun accountHashes(count: Int) = (1..count).map { "account_call_hash_123456789_$it" }
    private fun profile(type: String) = (OutboundFactory.newByType(type) as Qwdtt).apply {
        server = "192.0.2.1"; password = "fixture-password"
        hashes = listOf(manualHash); workers = 54
    }

    @Test fun autoUsesTheBindingCallsWithoutChangingManualSlotsForEveryClient() {
        for (type in transports) {
            val saved = profile(type).apply { hashSource = "auto" }
            val before = saved.exportToJson().toCompact()
            var requested = 0
            val runtime = NativeHashPolicy.runtimeProfile(saved) {
                requested = it
                accountHashes(it)
            }
            assertEquals(type, runtime.type)
            assertEquals(saved.carrierKind, runtime.carrierKind)
            assertNotSame(saved, runtime)
            val expected=if(type=="csqtt") 5 else 2
            assertEquals(expected, requested)
            assertEquals(accountHashes(expected), runtime.hashes)
            assertEquals("manual", runtime.hashSource)
            assertEquals(before, saved.exportToJson().toCompact())
            assertFalse(saved.exportToJson().toCompact().contains("account_call"))
            // Turning Auto off restores the same saved manual slot, without an account request.
            saved.hashSource = "manual"
            assertSame(saved, NativeHashPolicy.runtimeProfile(saved) { error("Manual mode must not contact VK") })
            assertEquals(listOf(manualHash), saved.hashes)
        }
    }

    @Test fun accountCallCountFollowsWorkersAndEachClientsLimit() {
        for (type in transports) {
            for (workers in listOf(9, 27, 36, 81, 108) + if (type == "csqtt") listOf(126) else emptyList()) {
                val saved = profile(type).apply { hashSource = "auto"; this.workers = workers }
                var requested = 0
                val runtime = NativeHashPolicy.runtimeProfile(saved) {
                    requested = it
                    accountHashes(it)
                }
                val effective=if(type=="csqtt") app.rawline.mobile.outbound.types.Csqtt.normalizeWorkers(workers) else workers
                val count=if(type=="csqtt") ((effective+9)/10).coerceAtMost(6) else (workers+26)/27
                assertEquals("$type, $workers workers", count, requested)
                assertEquals(effective, runtime.workers)
            }
        }
    }

    @Test fun defaultAppliesToEveryNewTransportAndKeepsAnExplicitSource() {
        for (type in transports) {
            val imported = profile(type)
            assertFalse(imported.hashSourceChosen)
            NativeHashPolicy.applyDefaultSource(imported, "auto")
            assertEquals("auto", imported.hashSource)
            assertTrue(imported.hashSourceChosen)
            // A later default change does not change an existing profile's choice.
            NativeHashPolicy.applyDefaultSource(imported, "manual")
            assertEquals("auto", imported.hashSource)

            val explicitManual = profile(type)
            assertTrue(explicitManual.parseFromJson(explicitManual.exportToJson()))
            NativeHashPolicy.applyDefaultSource(explicitManual, "auto")
            assertEquals("manual", explicitManual.hashSource)
            assertEquals(listOf(manualHash), explicitManual.hashes)
        }
    }

    @Test fun subscriptionRefreshKeepsAutomaticSourceAndManualSlotsForAllThreeClients() {
        for (type in transports) {
            val previous = profile(type).apply { hashSource = "auto"; name = "Same subscription node" }
            val incoming = profile(type).apply { name = previous.name; server = "192.0.2.2"; hashes = listOf("new_provider_hash_123456789") }
            NativeHashPolicy.preserve(incoming, listOf(previous))
            assertEquals("auto", incoming.hashSource)
            assertTrue(incoming.hashSourceChosen)
            assertEquals(previous.deviceId, incoming.deviceId)
            val stored = incoming.exportToJson().toCompact()
            NativeHashPolicy.runtimeProfile(incoming, ::accountHashes)
            assertEquals(stored, incoming.exportToJson().toCompact())
        }
    }

    @Test fun wdttPlusRespectsTheServersOwnWorkerLimitInAutoMode() {
        val saved = profile("wdttplus") as WdttPlus
        saved.hashSource = "auto"; saved.maxWorkers = 36; saved.workers = 108
        assertEquals(36, NativeHashPolicy.workerLimit(saved))
        val runtime = NativeHashPolicy.runtimeProfile(saved, ::accountHashes)
        assertEquals(36, runtime.workers)
        assertEquals(2, runtime.hashes.size)
    }

    @Test fun failedAccountLookupNeverFallsBackToManualHashesInAutomaticMode() {
        for (type in transports) {
            val saved = profile(type).apply { hashSource = "auto" }
            val before = saved.exportToJson().toCompact()
            try {
                NativeHashPolicy.runtimeProfile(saved) { error("VK session expired") }
                fail("Expected the account error")
            } catch (error: IllegalStateException) {
                assertEquals("VK session expired", error.message)
            }
            assertEquals(before, saved.exportToJson().toCompact())
        }
    }
}
