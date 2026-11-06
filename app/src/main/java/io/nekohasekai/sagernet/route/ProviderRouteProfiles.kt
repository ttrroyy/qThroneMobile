package app.rawline.mobile.route

import app.rawline.mobile.outbound.json.*

/** Keep each provider's original so a later update can split formerly identical choices safely. */
object ProviderRouteProfiles {
    fun sourceDocument(selection:String, saved:String):JsonObject =
        JsonInput.parseObjectOrNull(selection)?.takeIf { it.isNotEmpty() }
            ?: (JsonInput.parseValue(saved) as? JsonArray)?.filterIsInstance<JsonObject>()?.firstOrNull()
            ?: JsonObject()
    private const val SOURCE="_qthrone_source"
    private fun canonicalProvider(value:String)=when(value.lowercase()) { "happ" -> "Happ";"incy" -> "Incy";else -> value.ifBlank { "Happ" } }
    fun source(document:JsonObject,fallback:String="Happ") = canonicalProvider(document.string(SOURCE).ifBlank { fallback })
    fun tagged(document:JsonObject,provider:String)=document.copy().apply { this[SOURCE]=canonicalProvider(provider) }
    fun externalDocument(document:JsonObject)=document.copy().apply { remove(SOURCE) }
    fun key(document:JsonObject,fallback:String="Happ")=source(document,fallback)+"\u0000"+document.string("Name")
    fun sameSettings(a:JsonObject,b:JsonObject):Boolean {
        fun clean(document:JsonObject)=document.copy().apply { remove("Name");remove("LastUpdated");remove(SOURCE) }
        return clean(a)==clean(b)
    }
    data class Choice(val document:JsonObject,val keys:Set<String>,val label:String,val providers:String)
    fun choices(profiles:List<JsonObject>,selected:JsonObject,fallback:String):List<Choice> {
        val groups=mutableListOf<MutableList<JsonObject>>()
        for(raw in profiles) {
            val document=tagged(raw,source(raw,fallback))
            val matching=groups.firstOrNull { sameSettings(it.first(),document) }
            if(matching==null) groups.add(mutableListOf(document)) else matching.add(document)
        }
        return groups.map { group ->
            val document=group.firstOrNull { key(it)==key(selected,fallback) } ?: group.first()
            Choice(document,group.map { key(it) }.toSet(),document.string("Name").ifBlank { "Default" },
                group.map { source(it) }.distinct().joinToString(" / "))
        }
    }
    data class Result(val profiles:List<JsonObject>,val selected:String,val provider:String)
    fun requiresReload(beforeEnabled:Boolean,afterEnabled:Boolean,before:String,after:String):Boolean {
        if(!beforeEnabled && !afterEnabled) return false
        if(beforeEnabled!=afterEnabled) return true
        if(before==after) return false
        val a=JsonInput.parseObjectOrNull(before);val b=JsonInput.parseObjectOrNull(after)
        return if(a!=null && b!=null) !sameSettings(a,b) else true
    }
    fun merge(existing:List<JsonObject>,selection:String,legacyProvider:String,
        commands:List<ProviderRouting.Command>,manual:Boolean):Result {
        val fallback=legacyProvider.ifBlank { "Happ" }
        val firstImport=selection.isBlank()
        val profiles=existing.map { tagged(it,source(it,fallback)) }.toMutableList()
        var selected=JsonInput.parseObjectOrNull(selection)?.let { tagged(it,source(it,fallback)) }?.toCompact() ?: selection
        for(command in commands) {
            if(command.off) { if(!manual) selected="off";continue }
            val document=command.document?.let { tagged(it,command.provider) } ?: continue
            val index=profiles.indexOfFirst { key(it)==key(document) }
            if(index>=0) {
                val previous=profiles[index]
                val oldStamp=previous.variantLong("LastUpdated");val stamp=document.variantLong("LastUpdated")
                if(stamp>0 && oldStamp>0 && stamp<oldStamp) continue
                profiles[index]=document
                val active=JsonInput.parseObjectOrNull(selected)
                if(active!=null && key(active)==key(previous)) selected=document.toCompact()
            } else profiles.add(document)
            if(selected.isBlank() || (command.activate && !manual && firstImport)) selected=document.toCompact()
        }
        val provider=JsonInput.parseObjectOrNull(selected)?.let { source(it,fallback) } ?: fallback
        return Result(profiles,selected,provider)
    }
}
