package app.rawline.mobile.bg.carrier

import app.rawline.mobile.outbound.types.Qwdtt
import org.junit.Assert.*
import org.junit.Test

class NativeHashPolicyTest {
    @Test fun refreshUsesQwdttHashCeilingAndCsqttIndependentWorkerLimit() {
        assertEquals(81,NativeHashPolicy.preservedWorkers(108,81))
        // A chosen count stays when a new hash raises the limit (27 of one hash is not "always maximum").
        assertEquals(27,NativeHashPolicy.preservedWorkers(27,54))
        assertEquals(54,NativeHashPolicy.preservedWorkers(54,81))
        for(type in listOf("qwdtt","csqtt")) {
            val old=if(type=="csqtt") app.rawline.mobile.outbound.types.Csqtt() else Qwdtt()
            old.name="workers";old.server="192.0.2.1";old.password="fixture";old.hashSource="manual"
            old.hashes=listOf("a","b","c","d");old.workers=108
            val incoming=if(type=="csqtt") app.rawline.mobile.outbound.types.Csqtt() else Qwdtt()
            incoming.name=old.name;incoming.server=old.server;incoming.password=old.password
            // Provider now supplies three slots; the surviving trailing slot duplicates one of them.
            incoming.hashes=listOf("d","e","f");incoming.workers=9
            NativeHashPolicy.preserve(incoming,listOf(old))
            assertEquals(listOf("d","e","f"),incoming.hashes)
            // CSQTT 2.5 normalizes the saved count independently of hash slots.
            assertEquals(if(type=="csqtt") 100 else 81,incoming.workers)
        }
    }
    @Test fun subscriptionHashesAndExistingRowSurviveRepeatedRefreshForBothTransports() {
        for (old in listOf(profile(),app.rawline.mobile.outbound.types.Csqtt().apply {
            name="Example";server="192.0.2.1";password="test";hashes=listOf("local_hash")
        })) {
            repeat(2) {
                val incoming=if(old is app.rawline.mobile.outbound.types.Csqtt) app.rawline.mobile.outbound.types.Csqtt() else Qwdtt()
                assertTrue(incoming.parseFromJson(old.exportToJson()))
                incoming.hashes=listOf("subscription_default")
                incoming.workers=18
                NativeHashPolicy.preserve(incoming,listOf(old))
                assertEquals(listOf("subscription_default"),incoming.hashes)
                assertEquals(old.deviceId,incoming.deviceId)
                val previous=app.rawline.mobile.group.OldEntry(7,"old-content",NativeHashPolicy.subscriptionIdentity(old),"old")
                val index=app.rawline.mobile.group.ContentIndex(listOf(previous))
                val next=app.rawline.mobile.group.NewEntry("new").apply {
                    id=8;content="new-content";identity=NativeHashPolicy.subscriptionIdentity(incoming)
                }
                index.noteArrival(next.content)
                val plan=app.rawline.mobile.group.SubscriptionReconcile.reconcile(listOf(previous),listOf(next),index)
                assertEquals(listOf(7L),plan.order)
                assertEquals(listOf(7L to 8L),plan.updates)
                assertEquals(listOf(8L),plan.stale)
            }
        }
    }
    @Test fun subscriptionReplacesLeadingSlotsAndKeepsOnlyUnclaimedManualSlots() {
        for(type in listOf("qwdtt","csqtt")) {
            val old=if(type=="csqtt") app.rawline.mobile.outbound.types.Csqtt() else Qwdtt()
            old.name="Example";old.hashes=listOf("sub-old","manual-2","manual-3","manual-4")
            for(count in listOf(1,3,4)) {
                val incoming=if(type=="csqtt") app.rawline.mobile.outbound.types.Csqtt() else Qwdtt()
                incoming.name="Example";incoming.hashes=(1..count).map { "sub-new-$it" }
                NativeHashPolicy.preserve(incoming,listOf(old))
                assertEquals((1..count).map { "sub-new-$it" }+old.hashes.drop(count),incoming.hashes)
                old.hashes=incoming.hashes
            }
        }
        assertEquals(listOf("a","b"),NativeHashPolicy.mergeSlots(emptyList(),listOf("a","b"),4))
    }
    @Test fun newImportsUseSameIdentityAcrossGroupsAndServerChanges() {
        assertEquals(NativeHashPolicy.defaultDeviceId("same-phone"),NativeHashPolicy.defaultDeviceId("same-phone"))
        assertNotEquals(NativeHashPolicy.defaultDeviceId("same-phone"),NativeHashPolicy.defaultDeviceId("another-phone"))
    }
    private fun profile() = Qwdtt().apply {
        name = "Example"; server = "192.0.2.1"; password = "test"
        hashes = listOf("manual_hash_123456789")
    }
    @Test fun sourceSurvivesSubscriptionServerAndHashChanges() {
        val old = profile().apply { hashSource = "manual" }
        val incoming = profile().apply { server = "192.0.2.2"; hashes = listOf("new_hash_123456789"); hashSource = "auto" }
        NativeHashPolicy.preserve(incoming,listOf(old))
        assertEquals("manual",incoming.hashSource)
        assertTrue(incoming.hashSourceChosen)
        assertEquals(old.deviceId,incoming.deviceId)
    }
    @Test fun automaticSourceSurvivesSubscriptionUpdate() {
        val incoming = profile()
        NativeHashPolicy.preserve(incoming,listOf(profile().apply { hashSource = "auto" }))
        assertEquals("auto",incoming.hashSource)
    }
    @Test fun csqttDeviceIdentitySurvivesReplacedServer() {
        val old=app.rawline.mobile.outbound.types.Csqtt().apply { name="Same server";deviceId="existing-phone" }
        val incoming=app.rawline.mobile.outbound.types.Csqtt().apply { name="Same server";server="192.0.2.55" }
        NativeHashPolicy.preserve(incoming,listOf(old))
        assertEquals("existing-phone",incoming.deviceId)
        assertTrue(incoming.deviceIdProvided)
    }
    @Test fun manualHashesSurviveAutomaticModeSerialization() {
        val original = profile().apply { hashSource = "auto" }
        val restored = Qwdtt()
        assertTrue(restored.parseFromJson(original.exportToJson()))
        restored.hashSource = "manual"
        assertEquals(original.hashes,restored.hashes)
        assertEquals(original.exportIdentity().toCompact(),restored.exportIdentity().toCompact())
    }
    @Test fun ambiguousProfilesDoNotCopyAnotherProfilesChoice() {
        val incoming = profile()
        NativeHashPolicy.preserve(incoming,listOf(profile(),profile().apply { hashSource = "auto" }))
        assertFalse(incoming.hashSourceChosen)
    }
    @Test fun newProfilesStartLikeTheOriginalAppsAndRefreshKeepsTheUsersModeAndTransport() {
        val qwdtt=Qwdtt();assertTrue(qwdtt.parseFromJson(app.rawline.mobile.outbound.json.JsonInput.parseObject("""{"type":"qwdtt","peer":"192.0.2.1:56000","password":"fixture"}""")))
        assertEquals("wg",qwdtt.mode);assertTrue(qwdtt.turnTcp)
        val csqtt=app.rawline.mobile.outbound.types.Csqtt()
        assertTrue(csqtt.parseFromJson(app.rawline.mobile.outbound.json.JsonInput.parseObject("""{"type":"csqtt","server":"192.0.2.1","server_port":46000,"password":"fixture","hashes":["exampleHash123456789"]}""")))
        assertFalse(csqtt.turnTcp)
        for(type in listOf("qwdtt","csqtt")) {
            val old=if(type=="csqtt") app.rawline.mobile.outbound.types.Csqtt() else Qwdtt()
            old.name="Example";old.server="192.0.2.1";old.password="fixture";old.hashes=listOf("user_hash_123456789")
            old.mode="raw";old.turnTcp=type=="csqtt";old.workers=27
            val incoming=if(type=="csqtt") app.rawline.mobile.outbound.types.Csqtt() else Qwdtt()
            incoming.name="Example";incoming.server=old.server;incoming.password=old.password
            incoming.mode="wg";incoming.turnTcp=type!="csqtt";incoming.workers=9
            NativeHashPolicy.preserve(incoming,listOf(old))
            assertEquals("raw",incoming.mode)
            assertEquals(old.turnTcp,incoming.turnTcp)
            assertEquals(if(type=="csqtt") 20 else 27,incoming.workers)
            assertEquals(listOf("user_hash_123456789"),incoming.hashes)
            assertEquals(NativeHashPolicy.subscriptionIdentity(old),NativeHashPolicy.subscriptionIdentity(incoming))
        }
    }
    @Test fun userSlotsSurviveRefreshAndTheSubscriptionOnlyReplacesItsOwnCount() {
        for(type in listOf("qwdtt","csqtt")) {
            fun profile(vararg hashes:String)=(if(type=="csqtt") app.rawline.mobile.outbound.types.Csqtt() else Qwdtt()).apply {
                name="Example";server="192.0.2.1";password="fixture";hashSource="manual";this.hashes=hashes.toList()
            }
            // The subscription gives one hash; the user adds three of their own.
            var stored=profile("sub-1","user-2","user-3","user-4")
            var incoming=profile("sub-1")
            NativeHashPolicy.preserve(incoming,listOf(stored))
            assertEquals(listOf("sub-1","user-2","user-3","user-4"),incoming.hashes)
            // The subscription now gives two: they replace the first two slots, the user's rest stays.
            stored=incoming;incoming=profile("sub-new-1","sub-new-2")
            NativeHashPolicy.preserve(incoming,listOf(stored))
            assertEquals(listOf("sub-new-1","sub-new-2","user-3","user-4"),incoming.hashes)
            // A subscription without hashes leaves every slot to the user.
            stored=incoming;incoming=profile()
            NativeHashPolicy.preserve(incoming,listOf(stored))
            assertEquals(listOf("sub-new-1","sub-new-2","user-3","user-4"),incoming.hashes)
        }
    }
    @Test fun handEditedHashesAndTransportOptionsSurviveRefresh() {
        val old=app.rawline.mobile.outbound.types.Csqtt().apply {
            name="Example";server="192.0.2.1";password="fixture";hashes=listOf("user_hash_1234567890")
            manualHashesOverride=true;srtpMode="dtls";turnTransport="tcp_tls";vkAccessToken="token";workers=40
        }
        val incoming=app.rawline.mobile.outbound.types.Csqtt().apply {
            name="Example";server="192.0.2.1";password="fixture";hashes=listOf("sub_hash_1234567890")
        }
        NativeHashPolicy.preserve(incoming,listOf(old))
        assertEquals(old.hashes,incoming.hashes)
        assertTrue(incoming.manualHashesOverride)
        assertEquals("dtls",incoming.srtpMode);assertEquals("tcp_tls",incoming.turnTransport)
        assertEquals("token",incoming.vkAccessToken);assertEquals(40,incoming.workers)
        val restored=app.rawline.mobile.outbound.types.Csqtt()
        assertTrue(restored.parseFromJson(incoming.exportToJson()))
        assertTrue(restored.manualHashesOverride)
        val plus=app.rawline.mobile.outbound.types.WdttPlus().apply { name="Plus";hashes=listOf("a");workers=27;tcpOnly=true;captchaMode="wv" }
        val plusIncoming=app.rawline.mobile.outbound.types.WdttPlus().apply { name="Plus";hashes=listOf("a","b") }
        NativeHashPolicy.preserve(plusIncoming,listOf(plus))
        assertEquals(27,plusIncoming.workers);assertTrue(plusIncoming.tcpOnly);assertEquals("wv",plusIncoming.captchaMode)
    }
}
