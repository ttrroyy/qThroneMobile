package app.rawline.mobile.route

import app.rawline.mobile.outbound.json.*
import app.rawline.mobile.database.SubscriptionOptions
import org.junit.Assert.*
import org.junit.Test

class ProviderRouteProfilesTest {
    @Test fun disabledRoutingKeepsTheSubscriptionGeoSource() {
        val doc=jsonObjectOf("Name" to "Provider", "Geoipurl" to "https://example.org/provider.dat")
        val saved=JsonArray.of(doc).toCompact()
        assertEquals(doc,ProviderRouteProfiles.sourceDocument("off",saved))
        assertEquals(doc,ProviderRouteProfiles.sourceDocument(doc.toCompact(),"[]"))
        assertTrue(ProviderRouteProfiles.sourceDocument("off","[]").isEmpty())
    }
    @Test fun viewingOrSavingMetadataDoesNotRequestAProxyRestart() {
        val a=profile()
        val b=ProviderRouteProfiles.tagged(a,"Incy").apply { this["LastUpdated"]=200 }
        assertFalse(ProviderRouteProfiles.requiresReload(true,true,a.toCompact(),b.toCompact()))
        assertFalse(ProviderRouteProfiles.requiresReload(false,false,a.toCompact(),profile("https://other.example").toCompact()))
        assertTrue(ProviderRouteProfiles.requiresReload(true,true,a.toCompact(),profile("https://other.example").toCompact()))
        assertTrue(ProviderRouteProfiles.requiresReload(true,false,a.toCompact(),a.toCompact()))
    }
    private fun profile(dns:String="https://one.example/dns-query",name:String="Same name")=
        jsonObjectOf("Name" to name,"GlobalProxy" to true,"RemoteDNSType" to "DoH","RemoteDNSDomain" to dns,"DirectSites" to JsonArray.of("one.example","two.example"))
    private fun command(provider:String,document:JsonObject)=ProviderRouting.Command(provider,true,false,document)
    @Test fun sameNameDifferentProvidersRemainDistinctAndRefreshCannotOverwriteManualChoice() {
        val happ=profile();val incy=profile("https://two.example/dns-query")
        val imported=ProviderRouteProfiles.merge(emptyList(),"","",listOf(command("Happ",happ),command("Incy",incy)),false)
        assertEquals(2,imported.profiles.size)
        assertEquals(2,ProviderRouteProfiles.choices(imported.profiles,JsonInput.parseObject(imported.selected),imported.provider).size)
        val selected=imported.profiles.first { ProviderRouteProfiles.source(it)=="Happ" }.toCompact()
        val refreshed=ProviderRouteProfiles.merge(imported.profiles,selected,"Happ",listOf(command("Happ",happ),command("Incy",incy)),true)
        assertEquals("Happ",refreshed.provider)
        assertEquals(selected,refreshed.selected)
        assertEquals(2,refreshed.profiles.size)
    }
    @Test fun identicalSettingsCollapseToOneChoiceDespiteObjectOrderNameAndTimestamp() {
        val a=profile().apply { this["LastUpdated"]=1 }
        val b=jsonObjectOf("DirectSites" to JsonArray.of("one.example","two.example"),"RemoteDNSDomain" to "https://one.example/dns-query",
            "GlobalProxy" to true,"RemoteDNSType" to "DoH","Name" to "Other name","LastUpdated" to 100)
        val result=ProviderRouteProfiles.merge(emptyList(),"","",listOf(command("Happ",a),command("Incy",b)),false)
        val choices=ProviderRouteProfiles.choices(result.profiles,JsonInput.parseObject(result.selected),result.provider)
        assertEquals(1,choices.size)
        assertEquals(setOf("Happ","Incy"),choices.single().providers.split(" / ").toSet())
        assertEquals("Other name",choices.single().label) // The actual profile name is never decorated.
    }
    @Test fun changedFormerDuplicateSplitsWithoutLosingTheSelectedSource() {
        val original=profile()
        val first=ProviderRouteProfiles.merge(emptyList(),"","",listOf(command("Happ",original),command("Incy",original)),false)
        val happ=first.profiles.first { ProviderRouteProfiles.source(it)=="Happ" }
        assertEquals(1,ProviderRouteProfiles.choices(first.profiles,happ,"Happ").size)
        val second=ProviderRouteProfiles.merge(first.profiles,happ.toCompact(),"Happ",listOf(command("Incy",profile("https://changed.example/dns-query"))),true)
        assertEquals(2,ProviderRouteProfiles.choices(second.profiles,JsonInput.parseObject(second.selected),second.provider).size)
        assertEquals(happ.toCompact(),second.selected)
    }
    @Test fun legacyIncyProfileSurvivesAndHappIsRecoveredOnSubscriptionRefresh() {
        val old=profile("https://incy.example/dns-query")
        val result=ProviderRouteProfiles.merge(listOf(old),old.toCompact(),"Incy",
            listOf(command("Happ",profile()),command("Incy",old)),false)
        assertEquals(2,result.profiles.size)
        assertEquals("Incy",result.provider)
    }
    @Test fun ruleOrderAndUnknownSettingsAreSignificant() {
        val a=profile();val b=a.copy().apply { this["DirectSites"]=JsonArray.of("two.example","one.example") }
        assertFalse(ProviderRouteProfiles.sameSettings(a,b))
        assertFalse(ProviderRouteProfiles.sameSettings(a,a.copy().apply { this["FutureSetting"]=true }))
    }
    @Test fun staleUpdateIsIgnoredWithinItsOwnSourceOnly() {
        val old=profile().apply { this["LastUpdated"]=200 }
        val first=ProviderRouteProfiles.merge(emptyList(),"","",listOf(command("Happ",old)),false)
        val stale=profile("https://stale.example/dns-query").apply { this["LastUpdated"]=100 }
        val result=ProviderRouteProfiles.merge(first.profiles,first.selected,first.provider,listOf(command("Happ",stale),command("Incy",stale)),true)
        assertEquals(2,result.profiles.size)
        assertEquals(first.selected,result.selected)
    }
    @Test fun manualSelectionFlagSurvivesSerializationAndCopy() {
        val original=SubscriptionOptions(routingProvider="Happ",providerRoutingManual=true)
        assertTrue(SubscriptionOptions.parse(original.toJsonString()).copy().providerRoutingManual)
        assertFalse(SubscriptionOptions.parse("{}").providerRoutingManual)
    }
}
